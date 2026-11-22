package com.aibox.domain.tool.builtin

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets

/**
 * Chub / TavernAI 角色卡解析器（最小可用版）。
 *
 * 支持两种来源：
 * - 纯 JSON 文件：V2 卡 `{"spec":"chara_card_v2","data":{...}}` 或 V1 卡平铺字段。
 * - PNG 卡片：tEXt chunk 里 key=`chara` 的 base64 JSON（SillyTavern / Chub 通用格式）。
 *
 * 只解析字段、不写库、不碰头像文件；调用方把结果填进角色编辑表单，
 * 用户确认后再保存。字段缺失时留空，绝不编造。
 */
object ChubCardParser {

    data class ParsedCard(
        val name: String = "",
        val description: String = "",
        val personality: String = "",
        val scenario: String = "",
        val firstMes: String = "",
        val exampleDialogue: String = ""
    ) {
        /** 映射到角色设定主体：世界观(scenario) + 性格 + 描述，按序拼接。 */
        fun toSystemPrompt(): String = listOf(
            scenario.takeIf { it.isNotBlank() }?.let { "世界观：$it" },
            personality.takeIf { it.isNotBlank() }?.let { "性格：$it" },
            description.takeIf { it.isNotBlank() }
        ).filterNotNull().joinToString("\n\n").trim()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 从 SAF URI 读取并解析；UTF-8 文本或 PNG 均可。失败返回 null，由调用方提示用户。 */
    suspend fun parse(context: Context, uri: Uri): ParsedCard? = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@withContext null
        // PNG 先查 tEXt chunk；否则当纯文本 JSON。
        val jsonText = if (bytes.size > 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) {
            extractCharaJsonFromPng(bytes)
        } else {
            String(bytes, StandardCharsets.UTF_8)
        } ?: return@withContext null
        parseJson(jsonText)
    }

    private fun parseJson(text: String): ParsedCard? {
        val trimmed = text.trim().trim('\uFEFF')
        val root = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() ?: return null
        val obj = root as? JsonObject ?: return null
        // V2: {"data":{...}}；V1: 平铺
        val data = (obj["data"] as? JsonObject) ?: obj
        fun s(key: String) = data[key]?.jsonPrimitive?.content?.trim().orEmpty()
        val name = s("name")
        val description = s("description")
        if (name.isBlank() && description.isBlank()) return null
        // 数组字段（alternate_greetings 等）暂不处理，保留主字段即可。
        return ParsedCard(
            name = name,
            description = description,
            personality = s("personality"),
            scenario = s("scenario"),
            firstMes = s("first_mes"),
            exampleDialogue = s("mes_example")
        )
    }

    /**
     * 扫描 PNG 的 tEXt/zTXt chunk，找 keyword=`chara`，取其后 base64 解码成 JSON。
     * PNG chunk 结构：4 字节长度 + 4 字节类型 + 数据 + 4 字节 CRC。
     */
    private fun extractCharaJsonFromPng(bytes: ByteArray): String? {
        var pos = 8 // 跳过 PNG 签名
        while (pos + 8 <= bytes.size) {
            val len = readInt(bytes, pos)
            if (len < 0 || pos + 12 + len > bytes.size) break
            val type = String(bytes, pos + 4, 4, StandardCharsets.US_ASCII)
            val dataStart = pos + 8
            if (type == "tEXt") {
                val chunk = bytes.copyOfRange(dataStart, dataStart + len)
                val nul = chunk.indexOf(0)
                if (nul > 0) {
                    val keyword = String(chunk, 0, nul, StandardCharsets.ISO_8859_1)
                    if (keyword.equals("chara", ignoreCase = true)) {
                        val value = String(chunk, nul + 1, chunk.size - nul - 1, StandardCharsets.ISO_8859_1)
                        return runCatching {
                            String(Base64.decode(value.trim(), Base64.DEFAULT), StandardCharsets.UTF_8)
                        }.getOrNull()
                    }
                }
            }
            pos += 12 + len // 长度(4) + 类型(4) + 数据(len) + CRC(4)
            if (type == "IEND") break
        }
        return null
    }

    private fun readInt(b: ByteArray, offset: Int): Int =
        ((b[offset].toInt() and 0xFF) shl 24) or
            ((b[offset + 1].toInt() and 0xFF) shl 16) or
            ((b[offset + 2].toInt() and 0xFF) shl 8) or
            (b[offset + 3].toInt() and 0xFF)
}
