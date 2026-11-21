package org.timepin.app

import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

class Commands(private val db: Store) {
    private val sql = db.rows()

    fun run(book: Book, c: JSONObject, account: Account? = null): JSONObject =
        db.atomic {
            val current = sql.book(book.id) ?: return@atomic result("not_found")
            val justConnected = book.origin.isEmpty() && account != null &&
                current.origin == account.origin && current.user == account.user
            if (!justConnected && (book.user != current.user || book.origin != current.origin))
                return@atomic result("forbidden")
            run(current, c, current.user, current.role, true)
        }

    fun run(book: Book, c: JSONObject, actor: String, role: String, enqueue: Boolean): JSONObject {
        return try {
            db.atomic {
                if (role !in setOf("manager", "editor", "contributor", "reader"))
                    return@atomic result("forbidden")
                Contract.command(c)
                if (c.getString("calendarId") != book.id) return@atomic result("not_found")
                val op = c.getString("op")
                val key = c.optString("operationId")
                fun canEdit(owner: String) =
                    role in setOf("manager", "editor") || (role == "contributor" && owner == actor)
                if (op == "event.get")
                    return@atomic sql.entry(book.id, c.getString("eventId"))?.let {
                        result("ok", "record" to JSONObject(it.json))
                    } ?: result("not_found")
                if (op == "event.query") {
                    val w = c.getJSONObject("window")
                    val slots =
                        db.agenda(
                            listOf(book),
                            LocalDate.parse(w.getString("start")),
                            LocalDate.parse(w.getString("end")),
                            ZoneId.of(w.getString("zone")),
                            w.optString("participantId"),
                        )
                    return@atomic result("ok", "occurrences" to JSONArray(slots.map(Slot::json)))
                }
                if (role == "reader") return@atomic result("forbidden")
                val write = op !in setOf("import.preview", "import.undoPreview")
                val fingerprint = if (write) hash(canonical(c)) else ""
                if (write)
                    sql.receipt(book.id, actor, key)?.let {
                        return@atomic when {
                            it.fingerprint != fingerprint -> result("operation_reused")
                            !JSONArray(it.owners).strings().all(::canEdit) -> result("forbidden")
                            else -> JSONObject(it.reply)
                        }
                    }
                val owners = mutableSetOf<String>()
                val changed = mutableListOf<String>()
                val out =
                    when {
                        op.startsWith("event.") -> {
                            val id = c.getString("eventId")
                            val old = sql.entry(book.id, id)
                            val rec = old?.let { JSONObject(it.json) }
                            when {
                                op == "event.create" && id.startsWith("imp-") -> result("invalid")
                                op != "event.create" && rec == null -> result("not_found")
                                rec != null &&
                                    !canEdit(rec.optString("creatorId")) &&
                                    op != "event.create" -> result("forbidden")
                                rec != null &&
                                    (op == "event.create" ||
                                        rec.getBoolean("deleted") ||
                                        rec.getLong("version") != c.getLong("baseVersion")) ->
                                    result("conflict", "current" to rec)
                                else -> {
                                    val r =
                                        rec
                                            ?: obj(
                                                "id" to id,
                                                "calendarId" to book.id,
                                                "creatorId" to actor,
                                            )
                                    c.optJSONObject("event")?.let { r.put("event", normalize(it)) }
                                    r.put("version", r.optLong("version") + 1)
                                        .put("deleted", op == "event.delete")
                                    db.saveRecord(book.id, r, old?.source ?: 0)
                                    owners.add(r.getString("creatorId"))
                                    changed.add(id)
                                    result("ok", "record" to r)
                                }
                            }
                        }
                        op.startsWith("import.undo") -> {
                            val bid = c.getString("batchId")
                            val batch = sql.batch(book.id, bid) ?: return@atomic result("not_found")
                            val undo = JSONArray()
                            for (h in
                                JSONArray(batch.history).objects().sortedBy {
                                    it.getString("id")
                                }) {
                                val id = h.getString("id")
                                val old = sql.entry(book.id, id)
                                val r = old?.let { JSONObject(it.json) }
                                val version = r?.optLong("version") ?: 0
                                val allowed = r != null && canEdit(r.optString("creatorId"))
                                if (allowed) owners.add(r!!.getString("creatorId"))
                                var next = version
                                val outcome =
                                    when {
                                        !allowed -> "forbidden"
                                        version != h.getLong("after") -> "changed"
                                        else -> {
                                            val previous = h.optJSONObject("previous")
                                            val removed =
                                                previous == null || previous.optBoolean("deleted")
                                            next++
                                            if (write) {
                                                r!!.put("version", next).put("deleted", removed)
                                                previous?.let {
                                                    r.put("event", it.getJSONObject("event"))
                                                }
                                                val source =
                                                    when {
                                                        removed -> -1L
                                                        h.getLong("source") ==
                                                            previous!!.getLong("version") -> next
                                                        else -> h.getLong("source")
                                                    }
                                                db.saveRecord(book.id, r, source)
                                                changed.add(id)
                                            }
                                            if (removed) "removed" else "restored"
                                        }
                                    }
                                undo.put(
                                    obj("eventId" to id, "outcome" to outcome, "version" to next)
                                )
                            }
                            result("ok", "batchId" to bid, "undo" to undo)
                        }
                        else -> {
                            val batch = c.getJSONObject("batch")
                            val seen = mutableSetOf<String>()
                            val history = JSONArray()
                            val outcomes = JSONArray()
                            val bid = "batch-" + hash(book.serverId, actor, key).take(32)
                            for ((i, item) in batch.getJSONArray("rows").objects().withIndex()) {
                                val external = item.optString("externalId")
                                require(external.isEmpty() || seen.add(external)) { "来源编号重复" }
                                val id =
                                    "imp-" +
                                        (if (external.isEmpty())
                                                hash("operation", bid, i.toString())
                                            else
                                                hash(
                                                    "source",
                                                    book.serverId,
                                                    batch.getString("source"),
                                                    external,
                                                ))
                                            .take(32)
                                val event = normalize(item.getJSONObject("event"))
                                val old = sql.entry(book.id, id)
                                val r = old?.let { JSONObject(it.json) }
                                val before = r?.optLong("version") ?: 0
                                val accept = item.optBoolean("acceptCurrent")
                                require(
                                    !accept ||
                                        (external.isNotEmpty() && item.optLong("baseVersion") > 0)
                                )
                                val outcome =
                                    when {
                                        r != null && !canEdit(r.getString("creatorId")) ->
                                            "forbidden"
                                        item.has("baseVersion") &&
                                            item.getLong("baseVersion") != before -> "conflict"
                                        r == null ||
                                            (r.optBoolean("deleted") && old.source == -1L) ->
                                            "created"
                                        r.optBoolean("deleted") ||
                                            (before != old.source && !accept) -> "conflict"
                                        canonical(r.getJSONObject("event")) == canonical(event) ->
                                            "unchanged"
                                        else -> "updated"
                                    }
                                val change = outcome in setOf("created", "updated")
                                val version = before + if (change) 1 else 0
                                val creator = r?.getString("creatorId") ?: actor
                                if (outcome != "forbidden") owners.add(creator)
                                if (write && change) {
                                    history.put(
                                        obj(
                                            "id" to id,
                                            "after" to version,
                                            "source" to (old?.source ?: 0),
                                            "previous" to r,
                                        )
                                    )
                                    db.saveRecord(
                                        book.id,
                                        obj(
                                            "id" to id,
                                            "calendarId" to book.id,
                                            "creatorId" to creator,
                                            "version" to version,
                                            "deleted" to false,
                                            "event" to event,
                                        ),
                                        version,
                                    )
                                    changed.add(id)
                                } else if (write && outcome == "unchanged" && old != null)
                                    sql.save(old.copy(source = version))
                                outcomes.put(
                                    obj(
                                        "index" to i,
                                        "eventId" to id,
                                        "baseVersion" to before,
                                        "version" to version.takeIf { it > 0 },
                                        "outcome" to outcome,
                                    )
                                )
                            }
                            if (write)
                                sql.save(
                                    Batch(
                                        book.id,
                                        bid,
                                        history.toString(),
                                        book.origin.isNotEmpty(),
                                    )
                                )
                            result("ok", "rows" to outcomes, "batchId" to bid.takeIf { write })
                        }
                    }
                if (write && out.getString("code") == "ok") {
                    sql.save(
                        Receipt(
                            book.id,
                            actor,
                            key,
                            fingerprint,
                            JSONArray(owners.toList()).toString(),
                            out.toString(),
                        )
                    )
                    if (enqueue && book.origin.isNotEmpty()) {
                        // Freeze previewed versions so late remote edits remain conflicts.
                        val send = c.copyJson()
                        if (op == "import.commit")
                            for (row in out.getJSONArray("rows").objects()) {
                                val input =
                                    send
                                        .getJSONObject("batch")
                                        .getJSONArray("rows")
                                        .getJSONObject(row.getInt("index"))
                                if (!input.has("baseVersion"))
                                    input.put("baseVersion", row.getLong("baseVersion"))
                            }
                        if (
                            op == "import.undo" &&
                                sql.batch(book.id, c.getString("batchId"))?.synchronizedBatch ==
                                    false
                        ) {
                            for (id in changed) {
                                val r = JSONObject(sql.entry(book.id, id)!!.json)
                                val remove = r.getBoolean("deleted")
                                queue(
                                    book,
                                    obj(
                                        "v" to 1,
                                        "op" to if (remove) "event.delete" else "event.replace",
                                        "calendarId" to book.id,
                                        "operationId" to uid(),
                                        "eventId" to id,
                                        "baseVersion" to r.getLong("version") - 1,
                                        "event" to r.optJSONObject("event").takeUnless { remove },
                                    ),
                                    listOf(id),
                                )
                            }
                        } else queue(book, send, changed)
                    }
                }
                out
            }
        } catch (e: IllegalArgumentException) {
            result("invalid", "detail" to (e.message ?: "参数无效"))
        } catch (e: java.time.DateTimeException) {
            result("invalid", "detail" to "日期或时区无效")
        } catch (e: org.json.JSONException) {
            result("invalid", "detail" to e.message)
        }
    }

    private fun queue(b: Book, c: JSONObject, ids: List<String>) {
        val seq = sql.enqueue(Pending(b.id, c.toString()))
        for (id in ids.distinct()) sql.attach(PendingRef(seq, b.id, id))
    }
}
