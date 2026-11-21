package org.timepin.app

import android.app.job.*
import android.content.ComponentName
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Sync(private val ctx: Context, private val db: Store, private val a: Account) {
    private val sql = db.rows()

    private fun owned(b: Book) = b.origin == a.origin && b.user == a.user

    private fun notifyViews() =
        ctx.contentResolver.notifyChange(android.net.Uri.parse("content://${ctx.packageName}.device"), null)

    fun createCalendar(book: Book) {
        db.atomic {
            require(Account.read(ctx) == a) { "账号已切换，请重新创建日历" }
            require(sql.book(book.id) == null)
            sql.save(book)
            stagePublication(book.id)
        }
        retryWhenOnline(ctx)
    }

    private fun stagePublication(id: String) {
        require(Account.read(ctx) == a) { "账号已切换" }
        val b = sql.book(id) ?: error("日历不存在")
        if (b.origin.isNotEmpty()) {
            require(owned(b))
            return
        }
        val records = JSONArray()
        for (row in sql.live(id)) {
            val r = JSONObject(row.json)
            records.put(obj("id" to row.id, "event" to r.getJSONObject("event"),
                "version" to r.getLong("version"), "sourceVersion" to row.source.coerceAtLeast(0)))
        }
        val payload = obj("op" to "calendar.publish", "operationId" to uid(), "target" to id,
            "name" to b.name, "color" to b.color, "records" to records).toString()
        require(payload.toByteArray().size < 16 * 1024 * 1024) { "发布快照超过实例 16 MiB 请求限制" }
        if (id.startsWith("system-")) SystemCalendars.markSource(db, id)
        sql.save(b.copy(origin = a.origin, user = a.user, publication = payload))
    }

    private fun forget(id: String, why: String) =
        db.atomic {
            val b = sql.book(id) ?: return@atomic
            sql.clearEvents(id)
            sql.clearReceipts(id)
            sql.clearBatches(id)
            sql.hold(id, why)
            val detached = why == "revoked" || why == "signed_out"
            sql.save(
                b.copy(
                    name = if (detached) "已断开的共享日历" else b.name,
                    color = if (detached) "#60766A" else b.color,
                    cursor = "",
                    role = if (detached) "none" else b.role,
                    publication = "",
                )
            )
        }

    private fun pull(id: String): Boolean {
        var dirty = false
        while (true) {
            val old = sql.book(id) ?: return dirty
            val page =
                try {
                    a.api(
                        "sync/pull",
                        obj(
                            "calendarId" to old.serverId,
                            "sources" to true,
                            "cursor" to old.cursor.takeIf { it.isNotEmpty() },
                        ),
                    )
                } catch (e: HttpFailure) {
                    if (e.status == 409 && old.cursor.isNotEmpty()) {
                        forget(id, "needs_review")
                        dirty = true
                        continue
                    }
                    if (e.status == 403) {
                        forget(id, "revoked")
                        return true
                    }
                    throw e
                }
            if (page.getString("status") == "revoked") {
                forget(id, "revoked")
                return true
            }
            db.atomic {
                var b = sql.book(id)!!
                page.optJSONObject("calendar")?.let {
                    b =
                        b.copy(
                            name = it.getString("name"),
                            color = it.getString("color"),
                            role = it.getString("role"),
                        )
                    dirty = true
                }
                for (r in page.getJSONArray("changes").objects()) {
                    val eid = r.getString("id")
                    if (!sql.hasLocalOverlay(id, eid)) {
                        val prev = sql.entry(id, eid)
                        if (
                            prev == null ||
                                JSONObject(prev.json).getLong("version") <= r.getLong("version")
                        )
                            db.saveRecord(id, r, page.optJSONObject("sources")?.optLong(eid) ?: 0)
                        dirty = true
                    }
                }
                val next = b.copy(cursor = page.getString("cursor"))
                if (next != old) sql.save(next)
            }
            if (!page.getBoolean("more")) return dirty
        }
    }

    private fun push(id: String): Boolean {
        var dirty = false
        while (true) {
            val op = sql.head(id) ?: return dirty
            if (op.state != "pending") return dirty
            val reply =
                try {
                    val book = requireNotNull(sql.book(id))
                    a.api("commands", JSONObject(op.command).put("calendarId", book.serverId))
                } catch (e: HttpFailure) {
                    if (e.status == 403) {
                        forget(id, "revoked")
                        return true
                    }
                    throw e
                }
            val code = reply.getString("code")
            val failedRows =
                (reply.array("rows").objects() + reply.array("undo").objects()).any {
                    it.optString("outcome") in setOf("conflict", "forbidden", "changed")
                }
            if (code != "ok" || failedRows) {
                db.atomic {
                    sql.update(
                        op.copy(
                            state = if (failedRows) "conflict" else code,
                            detail = reply.toString(),
                        )
                    )
                }
                return true
            }
            db.atomic {
                sql.detach(op.seq)
                sql.dequeue(op.seq)
                reply.optJSONObject("record")?.let {
                    val eid = it.getString("id")
                    if (!sql.hasLocalOverlay(id, eid)) db.saveRecord(id, it, sql.entry(id, eid)?.source ?: 0)
                }
            }
            dirty = true
        }
    }

    fun syncBook(id: String): Boolean {
        synchronized(syncLock) {
            val b = sql.book(id) ?: return false
            require(owned(b)) { "账号或实例与副本不符" }
            if (b.role == "none") return false
            try {
                if (b.publication.isNotEmpty()) {
                    a.api("manage", JSONObject(b.publication))
                    db.atomic { sql.save(sql.book(id)!!.copy(publication = "")) }
                }
                val changed = pull(id)
                val sent = sql.book(id)?.role != "none" && push(id)
                val pulled = sent && sql.book(id)?.role != "none" && pull(id)
                if (changed || sent || pulled) Widgets.refresh(ctx, setOf(id))
                return changed || sent || pulled
            } catch (e: Exception) {
                Widgets.refresh(ctx, setOf(id))
                // Earlier pages or command receipts may already have committed.
                notifyViews()
                throw e
            }
        }
    }

    fun resolve(id: String, revision: String, choice: String, operationId: String) {
        synchronized(syncLock) {
            require(Account.read(ctx) == a) { "账号已切换，请重新打开" }
            val actor = "resolve:" + hash(a.origin, a.user)
            val fingerprint = hash(revision, choice)
            db.atomic {
                val b = sql.book(id) ?: error("日历不存在")
                require(owned(b) && b.role != "none" && b.publication.isEmpty()) { "日历归属或权限已变化" }
                sql.receipt(id, actor, operationId)?.let {
                    require(it.fingerprint == fingerprint) { "操作编号已用于另一项冲突" }
                    return@atomic
                }
                val drafts = sql.queue(id)
                val head = drafts.firstOrNull() ?: error("冲突已经处理")
                require(head.state != "pending" && queueRevision(drafts) == revision) { "待处理内容已变化，请重新核对" }
                if (choice == "remote") {
                    for (p in drafts) { sql.detach(p.seq); sql.dequeue(p.seq) }
                    sql.clearEvents(id)
                    sql.clearReplicaReceipts(id,actor)
                    sql.clearBatches(id)
                    sql.save(b.copy(cursor = ""))
                } else {
                    require(choice == "local" && drafts.size == 1)
                    val edit = JSONObject(head.command)
                    val current = JSONObject(head.detail).getJSONObject("current")
                    require(edit.getString("op") == "event.replace" && !current.getBoolean("deleted")) { "这项冲突无法直接重试本机改班" }
                    sql.detach(head.seq); sql.dequeue(head.seq)
                    db.saveRecord(id, current)
                    edit.put("operationId", uid()).put("baseVersion", current.getLong("version"))
                    requireOk(Commands(db).run(b, edit))
                }
                sql.save(Receipt(id,actor,operationId,fingerprint,"[]","{}"))
            }
            syncBook(id)
        }
    }

    // Use an installation-specific challenge to recognize an earlier instance even when
    // its original URL has retired. These anonymous requests never carry the login cookie.
    private fun verifiedAliases(candidates: Set<String>): Set<String> {
        val prefs = ctx.getSharedPreferences("replica-identity", Context.MODE_PRIVATE)
        val key = hash(a.user, a.origin)
        if (candidates.isEmpty() && prefs.contains(key)) return emptySet()
        val nonce = prefs.getString("challenge", null) ?: uid().replace("-", "").also {
            check(prefs.edit().putString("challenge", it).commit())
        }
        fun proof(url: String): String? = try {
            request(url, "/api/v1/instance?challenge=" + nonce, calendarServer = true,
                connectTimeoutMs = 2000, readTimeoutMs = 3000).body.optString("proof")
                .takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        } catch (_: java.io.IOException) { null }
          catch (_: HttpFailure) { null }
          catch (_: org.json.JSONException) { null }
        val current = proof(a.endpoint) ?: return emptySet()
        prefs.edit().putString(key, current).apply()
        return candidates.filterTo(mutableSetOf()) { old ->
            val saved = prefs.getString(hash(a.user, old), null)
            (saved ?: proof(old)) == current
        }
    }

    private fun discover(state: JSONObject): Set<String> {
        require(state.getJSONObject("user").getString("id") == a.user)
        val calendars = state.getJSONArray("calendars").objects()
        val books = sql.books()
        val available = calendars.map { it.getString("id") }.toSet()
        val mine = books.filter(::owned).associateBy { it.serverId }
        val previous = books.filter { it.origin.isNotEmpty() && !owned(it) && it.user == a.user &&
            it.serverId in available && it.serverId !in mine }
        val aliases = verifiedAliases(previous.map { it.origin }.toSet())
        val recoverable = previous.filter { it.origin in aliases }.associateBy { it.serverId }
        val hidden = mutableSetOf<String>()
        db.atomic {
            for (meta in calendars) {
                val id = meta.getString("id")
                val prior = (mine[id] ?: recoverable[id])?.let { sql.book(it.id) }
                val book = if (prior == null) {
                    val localId = if (sql.book(id) == null) id else "replica-" + uid()
                    Book(localId, meta.getString("name"), meta.getString("color"),
                        visible = meta.getBoolean("visible"), origin = a.origin, user = a.user,
                        role = meta.getString("role"), remoteId = if (localId == id) "" else id)
                } else {
                    prior.copy(origin = a.origin, role = meta.getString("role"),
                        name = meta.getString("name"), color = meta.getString("color"))
                }
                if (prior != book) sql.save(book)
                if (!meta.getBoolean("visible")) hidden.add(book.id)
            }
        }
        return hidden
    }

    fun all(force: Boolean = true): Boolean = synchronized(syncLock) {
        if (Account.read(ctx) != a) return false
        schedule(ctx)
        val revision = try {
            a.api("changes").getString("revision")
        } catch (e: HttpFailure) {
            if (e.status != 404) throw e
            null
        }
        val refresh = force || revision == null || observedAccount != a || observedRevision != revision
        val state = if (refresh) a.api("state") else null
        val hidden = if (state != null) discover(state) else webHidden
        val pending = sql.pendingBooks().toSet()
        var changed = false
        var failure: Exception? = null
        for (b in sql.books()) {
            if (b.role == "none" || (b.origin.isNotEmpty() && !owned(b))) continue
            try {
                if (b.origin.isEmpty()) db.atomic { stagePublication(b.id) }
                if (b.origin.isEmpty() || refresh || b.id in hidden || b.id in pending || b.cursor.isEmpty() || b.publication.isNotEmpty()) {
                    if (syncBook(b.id)) changed = true
                    if (b.origin.isEmpty()) changed = true
                }
            } catch (e: HttpFailure) {
                if (e.status == 401) throw e
                failure = e
            } catch (e: IllegalArgumentException) {
                failure = e
            }
        }
        if (changed) notifyViews()
        if (state != null && AccountProfiles.sync(ctx, a, state)) notifyViews()
        failure?.let { throw it }
        observedRevision = revision
        observedAccount = a
        webHidden = hidden
        changed
    }

    companion object {
        fun queueRevision(drafts: List<Pending>) = hash(*drafts.map {
            hash(it.seq.toString(), it.command, it.state, it.detail)
        }.toTypedArray())
        private val syncLock = Any()
        fun changeEndpoint(ctx: Context, prior: Account, url: String) = synchronized(syncLock) {
            require(Account.read(ctx) == prior) { "账号已变化，请重新连接" }
            Vault(ctx).set("account", prior.copy(endpoint = url).json())
            retryWhenOnline(ctx)
        }
        private var observedAccount: Account? = null
        private var observedRevision: String? = null
        private var webHidden = emptySet<String>()
        private const val PERIODIC = 501
        private const val RETRY = 502
        private const val PERIOD = 60 * 60 * 1000L

        fun logout(ctx: Context, db: Store, a: Account) {
            synchronized(syncLock) {
                val sync = Sync(ctx, db, a)
                // A publication without an acknowledgement may be the only copy of the
                // original local book. Its frozen snapshot is retried by this account.
                val ids =
                    db.rows()
                        .books()
                        .filter { sync.owned(it) && it.publication.isEmpty() }
                        .map { it.id }
                for (id in ids) sync.forget(id, "signed_out")
                runCatching { a.api("logout", JSONObject()) }
                Vault(ctx).set("account", null)
                AccountProfiles.clear(ctx)
                observedAccount = null
                observedRevision = null
                webHidden = emptySet()
                val scheduler = ctx.getSystemService(JobScheduler::class.java)
                scheduler.cancel(PERIODIC)
                scheduler.cancel(RETRY)
                Widgets.refresh(ctx)
            }
        }

        fun schedule(ctx: Context) {
            val scheduler = ctx.getSystemService(JobScheduler::class.java)
            val prior = scheduler.getPendingJob(PERIODIC)
            if (prior == null || !prior.isPersisted || prior.intervalMillis != PERIOD)
                scheduler.schedule(
                    JobInfo.Builder(PERIODIC, ComponentName(ctx, SyncJob::class.java))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPersisted(true)
                        .setPeriodic(PERIOD, PERIOD / 4)
                        .build()
                )
        }

        fun retryWhenOnline(ctx: Context) {
            val scheduler = ctx.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(RETRY) == null)
                scheduler.schedule(JobInfo.Builder(RETRY, ComponentName(ctx, SyncJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .build())
        }
    }
}

class SyncJob : JobService() {
    private val active = mutableMapOf<Int, JobParameters>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onStartJob(params: JobParameters): Boolean {
        active[params.jobId] = params
        Store.net.execute {
            val failure = runCatching { Account.read(this)?.let { Sync(this, Store.get(this), it).all(false) } }.exceptionOrNull()
            main.post {
                if (active[params.jobId] === params) {
                    active.remove(params.jobId)
                    jobFinished(params, failure is java.io.IOException || failure is HttpFailure && failure.status >= 500)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        active.remove(params.jobId)
        return true
    }
}
