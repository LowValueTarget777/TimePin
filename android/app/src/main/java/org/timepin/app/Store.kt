package org.timepin.app

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

fun uid() = UUID.randomUUID().toString()

fun obj(vararg fields: Pair<String, Any?>) =
    JSONObject().apply { fields.forEach { put(it.first, it.second) } }

fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

fun JSONArray.strings() = (0 until length()).map { getString(it) }

fun JSONObject.copyJson() = JSONObject(toString())

fun JSONObject.array(key: String) = optJSONArray(key) ?: JSONArray()

fun canonical(value: Any?): String =
    when (value) {
        is JSONObject ->
            value.keys().asSequence().sorted().joinToString(",", "{", "}") {
                JSONObject.quote(it) + ":" + canonical(value.get(it))
            }
        is JSONArray ->
            (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }

@Entity(tableName = "books")
data class Book(
    @PrimaryKey val id: String,
    val name: String,
    val color: String,
    val visible: Boolean = true,
    val origin: String = "",
    val user: String = "local",
    val role: String = "manager",
    val cursor: String = "",
    val publication: String = "",
    @ColumnInfo(defaultValue = "''") val remoteId: String = "",
)

val Book.serverId: String get() = remoteId.ifEmpty { id }

@Entity(
    tableName = "events",
    primaryKeys = ["cal", "id"],
    indices = [Index("cal", "live", "hi", "lo")],
)
data class Entry(
    val cal: String,
    val id: String,
    val json: String,
    val source: Long,
    val live: Boolean,
    val lo: String,
    val hi: String,
)

@Entity(tableName = "receipts", primaryKeys = ["cal", "actor", "opId"])
data class Receipt(
    val cal: String,
    val actor: String,
    val opId: String,
    val fingerprint: String,
    val owners: String,
    val reply: String,
)

@Entity(tableName = "batches", primaryKeys = ["cal", "id"])
data class Batch(
    val cal: String,
    val id: String,
    val history: String,
    val synchronizedBatch: Boolean,
)

@Entity(tableName = "outbox", indices = [Index("cal", "seq")])
data class Pending(
    val cal: String,
    val command: String,
    val state: String = "pending",
    val detail: String = "",
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
)

@Entity(tableName = "refs", primaryKeys = ["seq", "id"], indices = [Index("cal", "id")])
data class PendingRef(val seq: Long, val cal: String, val id: String)

data class DraftRow(val seq: Long, val calendarId: String, val name: String, val state: String)
data class DraftChunk(val calendarId: String, val name: String, val role: String, val state: String, val input: String)

@Entity(tableName = "system_observations", primaryKeys = ["cal", "externalId"])
data class SystemObservation(val cal: String, val externalId: String, val fingerprint: ByteArray)

@Dao
interface Rows {
    @Query("SELECT EXISTS(SELECT 1 FROM system_observations WHERE cal=:id)")
    fun hasSystemObservations(id: String): Boolean

    @Query("SELECT * FROM system_observations WHERE cal=:cal AND externalId IN (:ids)")
    fun observations(cal: String, ids: List<String>): List<SystemObservation>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun observe(rows: List<SystemObservation>)

    @Query("DELETE FROM system_observations WHERE cal=:cal") fun clearObservations(cal: String)

    @Query("SELECT * FROM books ORDER BY name,id") fun books(): List<Book>

    @Query("SELECT * FROM books WHERE id=:id") fun book(id: String): Book?

    @Query("DELETE FROM books WHERE id=:id") fun removeBook(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun save(book: Book)

    @Query("SELECT * FROM events WHERE cal=:cal AND id=:id")
    fun entry(cal: String, id: String): Entry?

    @Query("SELECT * FROM events WHERE cal IN (:cal) AND live=1 AND hi>=:lo AND lo<=:hi")
    fun window(cal: List<String>, lo: String, hi: String): List<Entry>

    @Query("SELECT * FROM events WHERE cal=:cal AND live=1 ORDER BY id")
    fun live(cal: String): List<Entry>

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun save(row: Entry)

    @Query("DELETE FROM events WHERE cal=:cal") fun clearEvents(cal: String)

    @Query("DELETE FROM events WHERE cal=:cal AND id=:id") fun erase(cal: String, id: String)

    @Query("SELECT * FROM receipts WHERE cal=:cal AND actor=:actor AND opId=:op")
    fun receipt(cal: String, actor: String, op: String): Receipt?

    @Query("SELECT EXISTS(SELECT 1 FROM receipts WHERE cal=:cal AND fingerprint='calendar.delete')")
    fun wasDeleted(cal: String): Boolean

    @Insert fun save(receipt: Receipt)

    @Query("DELETE FROM receipts WHERE cal=:cal") fun clearReceipts(cal: String)
    @Query("DELETE FROM receipts WHERE cal=:cal AND actor != :resolutionActor")
    fun clearReplicaReceipts(cal: String, resolutionActor: String)

    @Query("SELECT * FROM batches WHERE cal=:cal AND id=:id")
    fun batch(cal: String, id: String): Batch?

    @Query("SELECT * FROM batches WHERE cal=:cal ORDER BY rowid DESC LIMIT 50")
    fun batches(cal: String): List<Batch>

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun save(batch: Batch)

    @Query("DELETE FROM batches WHERE cal=:cal") fun clearBatches(cal: String)

    @Insert fun enqueue(op: Pending): Long

    @Update fun update(op: Pending)

    @Query("SELECT COUNT(*) FROM outbox WHERE cal=:cal") fun queueCount(cal: String): Int

    @Query("SELECT DISTINCT cal FROM outbox WHERE state='pending'") fun pendingBooks(): List<String>

    @Query("SELECT * FROM outbox WHERE cal=:cal ORDER BY seq") fun queue(cal: String): List<Pending>

    @Query("""SELECT p.seq, p.cal AS calendarId, b.name, p.state FROM outbox p
        JOIN books b ON b.id=p.cal WHERE b.origin=:origin AND b.user=:user AND p.seq>:after
        ORDER BY p.seq LIMIT 31""")
    fun accountDrafts(origin: String, user: String, after: Long): List<DraftRow>

    @Query("""SELECT p.cal AS calendarId, b.name, b.role, p.state, substr(p.command,:offset+1,:limit) AS input
        FROM outbox p JOIN books b ON b.id=p.cal WHERE b.origin=:origin AND b.user=:user AND p.seq=:seq""")
    fun accountDraft(origin: String, user: String, seq: Long, offset: Int, limit: Int): DraftChunk?

    @Query("""SELECT substr(p.detail,1,4096) FROM outbox p JOIN books b ON b.id=p.cal
        WHERE b.origin=:origin AND b.user=:user AND b.role!='none' AND p.seq=:seq""")
    fun draftReceiptPreview(origin: String, user: String, seq: Long): String?

    @Query("SELECT * FROM outbox WHERE cal=:cal ORDER BY seq LIMIT 1")
    fun head(cal: String): Pending?

    @Query("""SELECT EXISTS(SELECT 1 FROM refs JOIN outbox ON outbox.seq=refs.seq
          WHERE refs.cal=:cal AND refs.id=:id AND outbox.state NOT IN ('signed_out','revoked','needs_review'))""")
    fun hasLocalOverlay(cal: String, id: String): Boolean

    @Query("SELECT id FROM refs WHERE seq=:seq") fun refs(seq: Long): List<String>

    @Insert fun attach(ref: PendingRef)

    @Query("DELETE FROM refs WHERE seq=:seq") fun detach(seq: Long)

    @Query("DELETE FROM outbox WHERE seq=:seq") fun dequeue(seq: Long)

    @Query("UPDATE outbox SET state=:state,detail='' WHERE cal=:cal")
    fun hold(cal: String, state: String)
}

@Database(
    entities =
        [
            Book::class,
            Entry::class,
            Receipt::class,
            Batch::class,
            Pending::class,
            PendingRef::class,
            SystemObservation::class,
        ],
    version = 3,
    exportSchema = false,
)
abstract class Store : RoomDatabase() {
    abstract fun rows(): Rows

    fun <T> atomic(fn: () -> T): T = runInTransaction(java.util.concurrent.Callable { fn() })

    fun initLocal() = atomic {
        if (rows().books().isEmpty()) rows().save(Book("mobile-" + uid(), "我的日历", "#278B77"))
    }

    fun accountBooks(ctx: Context): List<Book> {
        val books = rows().books()
        if (books.all { it.origin.isEmpty() }) return books
        val account = Account.read(ctx)
        return books.filter {
            it.origin.isEmpty() || (it.origin == account?.origin && it.user == account.user)
        }
    }

    fun saveRecord(cal: String, r: JSONObject, source: Long = 0) {
        if (r.optString("calendarId") != cal) r.put("calendarId", cal)
        val span = r.optJSONObject("event")?.optJSONObject("span")
        val e = r.optJSONObject("event")
        var lo = span?.optString("start")?.take(10) ?: "0001-01-01"
        var hi = span?.optString("end")?.take(10) ?: lo
        if (e?.has("repeat") == true) {
            val until = e.getJSONObject("repeat").optString("until")
            hi =
                if (until.isEmpty()) "9999-12-31"
                else
                    runCatching {
                            LocalDate.parse(until)
                                .plusDays(
                                    LocalDate.parse(hi).toEpochDay() -
                                        LocalDate.parse(lo).toEpochDay()
                                )
                                .toString()
                        }
                        .getOrDefault("9999-12-31")
            for (ex in e.array("exceptions").objects()) ex.optJSONObject("move")?.let {
                lo = minOf(lo, it.getString("start").take(10))
                hi = maxOf(hi, it.getString("end").take(10))
            }
        }
        rows()
            .save(
                Entry(
                    cal,
                    r.getString("id"),
                    r.toString(),
                    source,
                    !r.optBoolean("deleted"),
                    lo,
                    hi,
                )
            )
    }

    companion object {
        val systemImportMigration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS system_observations (cal TEXT NOT NULL, externalId TEXT NOT NULL, fingerprint BLOB NOT NULL, PRIMARY KEY(cal, externalId))")
            }
        }
        val replicaMigration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN remoteId TEXT NOT NULL DEFAULT ''")
            }
        }
        @Volatile private var instance: Store? = null
        val io = Executors.newSingleThreadExecutor()
        val net = Executors.newSingleThreadExecutor()

        fun get(ctx: Context): Store =
            instance
                ?: synchronized(this) {
                    instance
                        ?: Room.databaseBuilder(
                                ctx.applicationContext,
                                Store::class.java,
                                "timepin.db",
                            )
                            .addMigrations(systemImportMigration, replicaMigration)
                            .build()
                            .also { instance = it }
                }
    }
}
