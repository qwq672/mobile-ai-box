package com.aibox.data.repo

import android.content.Context
import android.net.Uri
import com.aibox.data.db.AppDatabase
import com.aibox.data.db.ModelEntity
import com.aibox.data.model.ModelInfo
import com.aibox.util.FileUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class ModelRepository(private val context: Context) {

    private val dao = AppDatabase.get(context).modelDao()

    fun observeAll(): Flow<List<ModelInfo>> =
        dao.observeAll().map { list -> list.map { it.toInfo() } }

    /**
     * 一次性快照：返回当前所有已下载 / 导入的模型。
     *
     * 与 [observeAll] 的区别：本方法是 suspend + 单次取值，便于在 [com.aibox.AIBoxApp]
     * 的崩溃报告 `loadedModelsProvider` 等场景一次性读取，不需要长期订阅 Flow。
     */
    suspend fun listLoaded(): List<ModelInfo> = observeAll().first()

    suspend fun importFromUri(uri: Uri): ModelInfo {
        val file = FileUtils.copyUriToModels(context, uri)
        val info = ModelInfo(
            name = file.nameWithoutExtension,
            filePath = file.absolutePath,
            sizeMb = FileUtils.fileSizeMb(file)
        )
        val id = dao.upsert(info.toEntity(id = 0))
        return info.copy(id = id)
    }

    /** 注册一个已下载好的本地文件 */
    suspend fun registerDownloaded(filePath: String, name: String): ModelInfo {
        val file = java.io.File(filePath)
        val info = ModelInfo(
            name = name,
            filePath = file.absolutePath,
            sizeMb = FileUtils.fileSizeMb(file)
        )
        val id = dao.upsert(info.toEntity(id = 0))
        return info.copy(id = id)
    }

    suspend fun delete(info: ModelInfo) {
        val file = java.io.File(info.filePath)
        if (file.exists()) file.delete()
        dao.delete(info.toEntity())
    }

    suspend fun getById(id: Long): ModelInfo? = dao.getById(id)?.toInfo()
    suspend fun lastUsed(): ModelInfo? = dao.lastUsed()?.toInfo()
    suspend fun touchUsed(id: Long) {
        dao.getById(id)?.let { dao.update(it.copy(lastUsedAt = System.currentTimeMillis())) }
    }
}

// ---- mapping ----
private fun ModelEntity.toInfo() = ModelInfo(
    id, name, filePath, sizeMb, quant, params, arch, addedAt, lastUsedAt, isFavorite
)
private fun ModelInfo.toEntity(id: Long = this.id) = ModelEntity(
    id, name, filePath, sizeMb, quant, params, arch, addedAt, lastUsedAt, isFavorite
)
