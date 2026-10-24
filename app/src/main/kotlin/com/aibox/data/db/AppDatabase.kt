package com.aibox.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ModelEntity::class, SessionEntity::class, MessageEntity::class, BenchmarkResultEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun modelDao(): ModelDao
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun benchmarkResultDao(): BenchmarkResultDao

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

        fun get(ctx: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                ctx.applicationContext,
                AppDatabase::class.java,
                "aibox.db"
            )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .fallbackToDestructiveMigration()
            .build().also { INSTANCE = it }
        }
    }
}
