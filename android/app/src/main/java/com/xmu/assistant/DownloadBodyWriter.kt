package com.xmu.assistant

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import okhttp3.Response

/** Validates representation/range boundaries before touching a saved download. */
internal object DownloadBodyWriter {
    private const val COPY_BUFFER_BYTES = 64 * 1024
    private val contentRangePattern = Regex("^bytes\\s+(\\d+)-(\\d+)/(\\d+)$", RegexOption.IGNORE_CASE)

    fun isFilePayload(contentType: String): Boolean {
        val lowered = contentType.lowercase()
        return "text/html" !in lowered && "application/json" !in lowered && "application/xhtml" !in lowered
    }

    fun hasEncodedBody(response: Response): Boolean = response.header("Content-Encoding")
        .orEmpty().split(',').any { it.isNotBlank() && !it.trim().equals("identity", ignoreCase = true) }

    fun write(response: Response, target: File, resumeFrom: Long, preserveOriginal: Boolean) {
        // OkHttp removes this header when it transparently decodes gzip. Any
        // remaining encoding describes bytes that must not become a PDF/ZIP.
        check(!hasEncodedBody(response)) { "下载响应仍为压缩内容，无法安全保存，请稍后重试" }
        checkNotNull(response.body) { "下载响应为空" }
        target.parentFile?.mkdirs()
        if (response.code == 206) {
            writeRange(response, target, resumeFrom, preserveOriginal)
        } else if (preserveOriginal) {
            withTemporaryFile(target) { replacement ->
                copyBody(response, replacement)
                validateLength(response, replacement.length())
                Files.move(replacement.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } else {
            copyBody(response, target)
            validateLength(response, target.length())
        }
    }

    private fun writeRange(response: Response, target: File, resumeFrom: Long, preserveOriginal: Boolean) {
        val range = parseContentRange(response.header("Content-Range"))
        check(range != null && range.start == resumeFrom) { "下载范围无效（期望从 $resumeFrom 字节继续）" }
        check(range.endExclusive == range.total) { "下载范围未到达文件末尾" }
        withTemporaryFile(target) { chunk ->
            copyBody(response, chunk)
            check(chunk.length() == range.endExclusive - range.start) { "下载范围数据不完整" }
            validateLength(response, chunk.length())
            if (preserveOriginal) {
                Files.move(chunk.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } else {
                FileOutputStream(target, resumeFrom > 0).use { output ->
                    chunk.inputStream().use { input -> input.copyTo(output, COPY_BUFFER_BYTES) }
                }
            }
        }
    }

    private fun copyBody(response: Response, target: File) {
        checkNotNull(response.body).byteStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output, COPY_BUFFER_BYTES) }
        }
    }

    private fun validateLength(response: Response, received: Long) {
        val expected = response.header("Content-Length")?.toLongOrNull() ?: return
        check(expected < 0 || received == expected) {
            "下载不完整（收到 $received/$expected 字节），已保留断点续传记录"
        }
    }

    private inline fun withTemporaryFile(target: File, action: (File) -> Unit) {
        val temp = File.createTempFile("xmu-download-", ".download-chunk", target.absoluteFile.parentFile)
        try {
            action(temp)
        } finally {
            temp.delete()
        }
    }

    private data class ContentRange(val start: Long, val endExclusive: Long, val total: Long)

    private fun parseContentRange(value: String?): ContentRange? {
        val match = contentRangePattern.matchEntire(value?.trim().orEmpty()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        if (end < start || total <= end) return null
        return ContentRange(start, end + 1, total)
    }
}
