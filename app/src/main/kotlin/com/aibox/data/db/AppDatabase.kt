package com.aibox.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ModelEntity::class, SessionEntity::class, MessageEntity::class, BenchmarkResultEntity::class,
        CharacterEntity::class, PersonaEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun modelDao(): ModelDao
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun benchmarkResultDao(): BenchmarkResultDao
    abstract fun characterDao(): CharacterDao
    abstract fun personaDao(): PersonaDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        // v1 → v2: sessions 表新增 lastUsedAt / messageCount / modelPath 字段
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN lastUsedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sessions ADD COLUMN messageCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sessions ADD COLUMN modelPath TEXT")
            }
        }

        // v2 → v3: 新增 benchmark_results 表（P2 基准测试系统持久化）
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS benchmark_results (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        modelPath TEXT NOT NULL,
                        backendName TEXT NOT NULL,
                        modelFormat TEXT,
                        specPpSize INTEGER NOT NULL,
                        specTgSize INTEGER NOT NULL,
                        specRepCount INTEGER NOT NULL,
                        specThreads INTEGER,
                        specKvQuant TEXT NOT NULL,
                        ppTps REAL NOT NULL,
                        tgTps REAL NOT NULL,
                        ttftMs INTEGER NOT NULL,
                        totalTimeMs INTEGER NOT NULL,
                        peakMemoryBytes INTEGER NOT NULL,
                        energyMilliJoules INTEGER,
                        deviceSoC TEXT NOT NULL,
                        thermalState TEXT NOT NULL,
                        backgroundCpuUsage INTEGER NOT NULL,
                        batteryLevel INTEGER NOT NULL,
                        chargingState INTEGER NOT NULL,
                        totalRamMb INTEGER NOT NULL,
                        freeRamMb INTEGER NOT NULL,
                        logFilePath TEXT,
                        failed INTEGER NOT NULL,
                        errorMessage TEXT,
                        timestamp INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_benchmark_results_modelPath ON benchmark_results(modelPath)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_benchmark_results_backendName ON benchmark_results(backendName)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_benchmark_results_timestamp ON benchmark_results(timestamp)"
                )
            }
        }

        // v3 → v4: messages 表新增 attachmentsJson（多模态附件）
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN attachmentsJson TEXT NOT NULL DEFAULT ''")
            }
        }

        // v4 → v5: messages 表新增 partsJson / versionsJson / versionIndex（时间线 + 版本历史）
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN partsJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN versionsJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN versionIndex INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v5 → v6: 角色扮演（会话模式 + AI 角色 + 用户身份）
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN mode TEXT NOT NULL DEFAULT 'CHAT'")
                db.execSQL("ALTER TABLE sessions ADD COLUMN characterId INTEGER")
                db.execSQL("ALTER TABLE sessions ADD COLUMN personaId INTEGER")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS characters (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        emoji TEXT NOT NULL,
                        description TEXT NOT NULL,
                        systemPrompt TEXT NOT NULL,
                        greeting TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        lastUsedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS personas (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        lastUsedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        // v6 → v7: messages 表新增 parentUserVersion（用户消息版本 ↔ 助手回复分支）
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN parentUserVersion INTEGER NOT NULL DEFAULT -1")
            }
        }

        // v7 → v8: characters 表新增卡片图片 / 头像模式 / 性格 / 世界观（角色卡片体验）
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE characters ADD COLUMN cardImageUri TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE characters ADD COLUMN avatarImageUri TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE characters ADD COLUMN avatarUseCard INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE characters ADD COLUMN personality TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE characters ADD COLUMN worldview TEXT NOT NULL DEFAULT ''")
            }
        }

        fun get(ctx: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                ctx.applicationContext,
                AppDatabase::class.java,
                "aibox.db"
            )
            .addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8
            )
            // 绝不销毁数据：移除 fallbackToDestructiveMigration()。
            // - 升级：显式迁移链 1→8 完整，正常升级全部走迁移，保留全部对话与工具历史。
            // - 若将来升到 v8 漏写迁移：Room 抛 IllegalStateException（可被日志发现、补迁移修复），
            //   而不是静默清空整库。数据完好优先于「带错 schema 强行启动」。
            // - 降级：同样不设 OnDowngrade 销毁兜底——宁可抛错让数据留在磁盘上可被 Recovery 取回，
            //   也不静默清空。这是「任何情况下都不因版本变化丢数据」的硬约束。
            .build().also { INSTANCE = it }
        }
    }
}
