package ru.workspace.pwaiconextractor

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.util.Locale

class IconSaver(
    private val context: Context,
    private val log: (String, Boolean, Boolean) -> Unit,
) {
    fun save(treeUri: Uri, items: List<IconItem>): List<IconItem> {
        val dir = DocumentFile.fromTreeUri(context, treeUri)
        if (dir == null || !dir.canWrite()) {
            log("Нет доступа на запись в выбранную папку", true, false)
            return items
        }

        val selected = items.filter { it.selected && it.bytes != null }
        if (selected.isEmpty()) {
            log("Нет выбранных иконок для сохранения", true, false)
            return items
        }

        log("Начало сохранения ${selected.size} файлов...", false, false)
        val usedNames = mutableSetOf<String>()
        val updated = items.toMutableList()

        selected.forEachIndexed { idx, item ->
            val indexInAll = updated.indexOfFirst { it.id == item.id }
            val ext = guessExtension(item.mimeType, item.url, item.bytes)
            val base = baseName(item, idx + 1)
            val name = uniqueName("$base.$ext", usedNames)
            usedNames.add(name.lowercase(Locale.ROOT))

            log("Сохранение ${idx + 1}/${selected.size}: $name", false, false)
            try {
                val mime = mimeForExt(ext)
                val file = dir.createFile(mime, name)
                if (file == null) {
                    log("Не удалось создать файл: $name", true, false)
                    if (indexInAll >= 0) updated[indexInAll] = updated[indexInAll].copy(saveStatus = "Ошибка создания")
                } else {
                    context.contentResolver.openOutputStream(file.uri).use { out ->
                        if (out == null) throw IllegalStateException("output stream is null")
                        out.write(item.bytes!!)
                        out.flush()
                    }
                    log("Сохранено: $name", false, false)
                    if (indexInAll >= 0) updated[indexInAll] = updated[indexInAll].copy(saveStatus = "Сохранено")
                }
            } catch (e: Exception) {
                log("Ошибка сохранения $name: ${e.message}", true, false)
                if (indexInAll >= 0) updated[indexInAll] = updated[indexInAll].copy(saveStatus = "Ошибка: ${e.message}")
            }
        }

        val ok = updated.count { it.saveStatus == "Сохранено" }
        log("Копирование завершено: $ok из ${selected.size}", false, ok == selected.size)
        return updated
    }

    private fun baseName(item: IconItem, index: Int): String {
        val fromUrl = try {
            val path = Uri.parse(item.url).lastPathSegment ?: ""
            path.substringBeforeLast('.')
        } catch (_: Exception) { "" }
        val cleaned = fromUrl.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
        val prefix = when (item.source) {
            IconSource.MANIFEST -> "manifest"
            IconSource.LINK_ICON -> "icon"
            IconSource.APPLE_TOUCH -> "apple_touch"
            IconSource.OG_IMAGE -> "og"
            IconSource.TWITTER_IMAGE -> "twitter"
            IconSource.IMG_TAG -> "img"
        }
        val sizePart = item.sizeLabel.takeIf { it.isNotBlank() && it != "—" }
            ?.replace(Regex("[^A-Za-z0-9x]+"), "") ?: ""
        val core = cleaned.ifBlank { prefix }
        return buildString {
            append(index.toString().padStart(2, '0'))
            append("_")
            append(core.take(40))
            if (sizePart.isNotBlank()) {
                append("_")
                append(sizePart.take(20))
            }
        }
    }

    private fun uniqueName(name: String, usedLower: Set<String>): String {
        if (name.lowercase(Locale.ROOT) !in usedLower) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while (true) {
            val cand = "${base}_$i$ext"
            if (cand.lowercase(Locale.ROOT) !in usedLower) return cand
            i++
        }
    }

    private fun guessExtension(mime: String?, url: String, bytes: ByteArray?): String {
        mime?.lowercase(Locale.ROOT)?.let {
            when {
                it.contains("svg") -> return "svg"
                it.contains("png") -> return "png"
                it.contains("jpeg") || it.contains("jpg") -> return "jpg"
                it.contains("webp") -> return "webp"
                it.contains("gif") -> return "gif"
                it.contains("icon") || it.contains("x-icon") || it.contains("vnd.microsoft.icon") -> return "ico"
                it.contains("bmp") -> return "bmp"
                it.contains("avif") -> return "avif"
            }
        }
        val path = try { Uri.parse(url).path?.lowercase(Locale.ROOT) ?: "" } catch (_: Exception) { "" }
        val fromUrl = path.substringAfterLast('.', "")
        if (fromUrl in setOf("png", "jpg", "jpeg", "webp", "gif", "svg", "ico", "bmp", "avif")) {
            return if (fromUrl == "jpeg") "jpg" else fromUrl
        }
        if (bytes != null && bytes.size >= 12) {
            if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) return "png"
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return "jpg"
            if (bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[8] == 'W'.code.toByte()) return "webp"
            if (bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()) return "gif"
            if (bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte() && bytes[2] == 0x01.toByte() && bytes[3] == 0x00.toByte()) return "ico"
            val head = try { String(bytes.take(256).toByteArray()) } catch (_: Exception) { "" }
            if (head.contains("<svg", ignoreCase = true) || head.trimStart().startsWith("<?xml")) return "svg"
        }
        return "png"
    }

    private fun mimeForExt(ext: String): String = when (ext.lowercase(Locale.ROOT)) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "ico" -> "image/x-icon"
        "bmp" -> "image/bmp"
        "avif" -> "image/avif"
        else -> "application/octet-stream"
    }
}
