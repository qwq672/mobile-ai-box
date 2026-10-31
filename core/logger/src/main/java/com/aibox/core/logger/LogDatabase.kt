package com.aibox.core.logger
import androidx.room.*
@Entity(tableName = "logs")
public data class LogEntryEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0L, val timestamp: Long, val level: String, val module: String, val tag: String, val message: String, val threadName: String, val throwableSummary: String?, val extrasJson: String)
@Dao @JvmSuppressWildcards
public interface LogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertEntity(entry: LogEntryEntity): Long
    @Query("DELETE FROM logs WHERE timestamp < :cutoff") suspend fun purgeOlderThan(cutoff: Long): Int
    @Query("SELECT * FROM logs WHERE :minLevelSeverity <= (CASE level WHEN 'VERBOSE' THEN 0 WHEN 'DEBUG' THEN 1 WHEN 'INFO' THEN 2 WHEN 'WARN' THEN 3 WHEN 'ERROR' THEN 4 WHEN 'CRASH' THEN 5 ELSE 2 END) ORDER BY timestamp DESC LIMIT :limit") suspend fun queryByMinSeverity(minLevelSeverity: Int, limit: Int): List<LogEntryEntity>
}
public class LogDaoWrapper(private val dao: LogDao) {
    suspend fun insert(entry: LogEntry): Long = dao.insertEntity(entry.toEntity())
    suspend fun purgeOlderThan(cutoff: Long): Int = dao.purgeOlderThan(cutoff)
    suspend fun query(filter: LogFilter): List<LogEntry> { return dao.queryByMinSeverity(filter.minLevel.severity, filter.limit).map { it.toDomain() } }
    suspend fun queryPage(filter: LogFilter, pageSize: Int, offset: Int): List<LogEntry> { return query(filter).drop(offset).take(pageSize) }
}
@Database(entities = [LogEntryEntity::class], version = 1, exportSchema = false)
public abstract class LogDatabase : RoomDatabase() {
    public abstract fun logDao(): LogDao
    public fun wrappedDao(): LogDaoWrapper = LogDaoWrapper(logDao())
    public companion object { public fun create(ctx: android.content.Context): LogDatabase = Room.databaseBuilder(ctx.applicationContext, LogDatabase::class.java, "aibox-logs.db").fallbackToDestructiveMigration().build() }
}
public fun LogEntry.toEntity(): LogEntryEntity = LogEntryEntity(id, timestamp, level.name, module.name, tag, message, threadName, throwable?.message, extras.entries.joinToString("|") { (k, v) -> "${k.replace("|","_").replace("=","_")}=${v.replace("|","_").replace("=","_")}" })
public fun LogEntryEntity.toDomain(): LogEntry { val extras: Map<String,String> = try { if (extrasJson.isEmpty()) emptyMap() else extrasJson.split("|").associate { val i = it.indexOf("="); if (i > 0) it.substring(0,i) to it.substring(i+1) else "" to "" } } catch (_: Throwable) { emptyMap() }; return LogEntry(id, timestamp, runCatching { LogLevel.valueOf(level) }.getOrDefault(LogLevel.INFO), runCatching { LogModule.valueOf(module) }.getOrDefault(LogModule.UNKNOWN), tag, message, threadName, throwableSummary?.let { Throwable(it) }, extras) }
