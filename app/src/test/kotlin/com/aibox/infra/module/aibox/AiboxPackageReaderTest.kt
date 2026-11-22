package com.aibox.infra.module.aibox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AiboxPackageReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val reader = AiboxPackageReader()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun makePackage(
        manifest: String,
        files: Map<String, ByteArray> = emptyMap(),
        extra: Map<String, ByteArray> = emptyMap()
    ): File {
        val file = tmp.newFile("test.aibox")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toByteArray())
            zip.closeEntry()
            (files + extra).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `valid model bundle inspects and extracts`() {
        val payload = "model".toByteArray()
        val hash = sha256(payload)
        val manifest = """
        {
          "schema_version": 1,
          "id": "com.example.sd.model",
          "type": "model-bundle",
          "version": "1.0.0",
          "payload": [
            {"role": "diffusion", "path": "payload/model.gguf", "sha256": "$hash"}
          ]
        }
        """.trimIndent()
        val pkg = makePackage(manifest, mapOf("payload/model.gguf" to payload))

        val result = reader.inspect(pkg)
        assertTrue(result is AiboxResult.Ok)
        val inspected = (result as AiboxResult.Ok).value
        assertEquals("com.example.sd.model", inspected.manifest.id)
        assertEquals(AiboxPackageType.MODEL_BUNDLE, inspected.manifest.type)
        assertTrue(!inspected.signaturePresent)

        val dest = tmp.newFolder("out")
        val extracted = reader.extract(pkg, dest)
        assertTrue(extracted is AiboxResult.Ok)
        val root = (extracted as AiboxResult.Ok).value.rootDir
        assertEquals("model", File(root, "payload/model.gguf").readText())
    }

    @Test
    fun `zip slip path rejected`() {
        val manifest = """
        {
          "schema_version": 1,
          "id": "com.example.evil",
          "type": "model-bundle",
          "version": "1.0.0"
        }
        """.trimIndent()
        val pkg = makePackage(manifest, extra = mapOf("../evil.so" to "x".toByteArray()))
        val result = reader.inspect(pkg)
        assertTrue(result is AiboxResult.Err)
        assertEquals(AiboxErrorCode.UNSAFE_PATH, (result as AiboxResult.Err).code)
    }

    @Test
    fun `checksum mismatch rejected`() {
        val manifest = """
        {
          "schema_version": 1,
          "id": "com.example.bad",
          "type": "model-bundle",
          "version": "1.0.0",
          "payload": [
            {"role": "model", "path": "payload/model.gguf", "sha256": "${"0".repeat(64)}"}
          ]
        }
        """.trimIndent()
        val pkg = makePackage(manifest, mapOf("payload/model.gguf" to "model".toByteArray()))
        val result = reader.inspect(pkg)
        assertTrue(result is AiboxResult.Err)
        assertEquals(AiboxErrorCode.CHECKSUM_MISMATCH, (result as AiboxResult.Err).code)
    }

    @Test
    fun `native payload requires signature`() {
        val manifest = """
        {
          "schema_version": 1,
          "id": "com.example.native",
          "type": "native-payload",
          "version": "1.0.0"
        }
        """.trimIndent()
        val pkg = makePackage(manifest)
        val result = reader.inspect(pkg)
        assertTrue(result is AiboxResult.Err)
        assertEquals(AiboxErrorCode.SIGNATURE_REQUIRED, (result as AiboxResult.Err).code)
    }

    @Test
    fun `missing payload file rejected`() {
        val manifest = """
        {
          "schema_version": 1,
          "id": "com.example.missing",
          "type": "model-bundle",
          "version": "1.0.0",
          "payload": [
            {"role": "model", "path": "payload/model.gguf", "sha256": "${"0".repeat(64)}"}
          ]
        }
        """.trimIndent()
        val pkg = makePackage(manifest)
        val result = reader.inspect(pkg)
        assertTrue(result is AiboxResult.Err)
        assertEquals(AiboxErrorCode.PAYLOAD_MISSING, (result as AiboxResult.Err).code)
    }
}
