package com.pocketllm.data.repo

import android.content.Context
import android.net.Uri
import com.pocketllm.data.db.AppDatabase
import com.pocketllm.data.db.ModelEntity
import com.pocketllm.data.model.ModelInfo
import com.pocketllm.util.FileUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ModelRepository(private val context: Context) {

    private val dao = AppDatabase.get(context).modelDao()

    fun observeAll(): Flow<List<ModelInfo>> =
        dao.observeAll().map { list -> list.map { it.toInfo() } }

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
