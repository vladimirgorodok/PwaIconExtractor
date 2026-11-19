package ru.workspace.pwaiconextractor

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Извлекает все вложения-изображения из базы Teable через Apps API.
 * Токен используется только в заголовках запросов и никогда не пишется в журнал.
 */
class TeableExtractor(
    private val log: (String, Boolean, Boolean) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private data class Attachment(
        val url: String,
        val name: String,
        val mimetype: String,
        val token: String,
        val path: String,
        val table: String,
    )

    fun extract(input: String, token: String): List<IconItem> {
        val baseId = resolveBaseId(input, token) ?: return emptyList()
        log("База Teable: $baseId", false, false)

        val tables = getJsonArray("/base/$baseId/table", token) ?: return emptyList()
        log("Таблиц в базе: ${tables.length()}", false, false)
        if (tables.length() == 0) {
            log("В базе нет таблиц", true, false)
            return emptyList()
        }

        val attachments = mutableListOf<Attachment>()
        for (i in 0 until tables.length()) {
            val table = tables.optJSONObject(i) ?: continue
            val tableId = table.optString("id", "")
            val tableName = table.optString("name", tableId)
            if (tableId.isBlank()) continue

            val fields = getJsonArray("/table/$tableId/field", token) ?: continue
            val attachmentFields = mutableListOf<String>()
            for (j in 0 until fields.length()) {
                val f = fields.optJSONObject(j) ?: continue
                if (f.optString("type", "") == "attachment") {
                    attachmentFields.add(f.optString("name", f.optString("id", "")))
                }
            }
            log("Таблица «$tableName»: полей вложений — ${attachmentFields.size}", false, false)
            if (attachmentFields.isEmpty()) continue

            var skip = 0
            while (true) {
                val path = "/table/$tableId/record?take=1000&skip=$skip"
                val resp = getJsonObject(path, token) ?: break
                val records = resp.optJSONArray("records") ?: JSONArray()
                for (k in 0 until records.length()) {
                    val record = records.optJSONObject(k) ?: continue
                    val values = record.optJSONObject("fields") ?: continue
                    for (fieldName in attachmentFields) {
                        val cell = values.opt(fieldName) ?: continue
                        val list = when (cell) {
                            is JSONArray -> cell
                            is JSONObject -> JSONArray().put(cell)
                            else -> continue
                        }
                        for (a in 0 until list.length()) {
                            val att = list.optJSONObject(a) ?: continue
                            val name = att.optString("name", "attachment")
                            val mime = att.optString("mimetype", "")
                            val url = att.optString("url", "")
                            if (!isImage(name, mime, url)) continue
                            attachments.add(
                                Attachment(
                                    url = url,
                                    name = name,
                                    mimetype = mime,
                                    token = att.optString("token", ""),
                                    path = att.optString("path", ""),
                                    table = tableName,
                                )
                            )
                        }
                    }
                }
                if (records.length() < 1000) break
                skip += 1000
            }
        }

        log("Найдено вложений-изображений: ${attachments.size}", false, false)
        if (attachments.isEmpty()) {
            log("Изображений в Teable не найдено", true, false)
            return emptyList()
        }

        // Подписываем URL там, где прямого url нет (приватные вложения).
        val needSign = attachments.filter { it.url.isBlank() && it.token.isNotBlank() && it.path.isNotBlank() }
        if (needSign.isNotEmpty()) {
            log("Подпись URL для ${needSign.size} вложений...", false, false)
            val signed = signUrls(baseId, needSign, token)
            attachments.forEach { a ->
                if (a.url.isBlank()) {
                    val signedUrl = signed[a.token]
                    if (signedUrl != null) attachments[attachments.indexOf(a)] = a.copy(url = signedUrl)
                }
            }
        }

        val unique = LinkedHashMap<String, Attachment>()
        attachments.forEach { if (it.url.isNotBlank() && !unique.containsKey(it.url)) unique[it.url] = it }
        log("Уникальных изображений: ${unique.size}. Загрузка...", false, false)

        val items = mutableListOf<IconItem>()
        unique.values.forEachIndexed { index, a ->
            log("Загрузка ${index + 1}/${unique.size}: ${a.name}", false, false)
            val bytes = fetchImage(a.url, token)
            val bitmap = bytes?.let { decode(it) }?.asImageBitmap()
            if (bytes == null) log("Ошибка загрузки: ${a.name}", true, false)
            items.add(
                IconItem(
                    id = UUID.randomUUID().toString(),
                    url = a.url,
                    sizeLabel = bitmap?.let { "${it.width}x${it.height}" }
                        ?: a.mimetype.substringAfterLast('/', "—"),
                    mimeType = a.mimetype.ifBlank { null },
                    source = IconSource.TEABLE,
                    bytes = bytes,
                    bitmap = bitmap,
                    selected = bytes != null,
                )
            )
        }

        val ok = items.count { it.bytes != null }
        log("Teable: загружено $ok из ${items.size}", false, true)
        return items
    }

    private fun resolveBaseId(input: String, token: String): String? {
        val s = input.trim()
        val direct = Regex("""\bbse[A-Za-z0-9]+\b""").find(s)?.value
        if (direct != null) return direct

        log("Определение базы Teable по токену...", false, false)
        val bases = getJsonArray("/base/access/all", token) ?: return null
        if (bases.length() == 0) {
            log("Токену не доступна ни одна база Teable", true, false)
            return null
        }
        if (bases.length() == 1) {
            return bases.optJSONObject(0)?.optString("id", "").takeIf { it.isNotBlank() }
        }
        log("Доступно баз: ${bases.length()}. Используется первая.", false, false)
        for (i in 0 until minOf(10, bases.length())) {
            val b = bases.optJSONObject(i) ?: continue
            log("  ${b.optString("name", "")} — ${b.optString("id", "")}", false, false)
        }
        return bases.optJSONObject(0)?.optString("id", "").takeIf { it.isNotBlank() }
    }

    private fun signUrls(
        baseId: String,
        list: List<Attachment>,
        token: String,
    ): Map<String, String> {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("path", it.path)
                    .put("token", it.token)
                    .put("mimetype", it.mimetype.ifBlank { "image/png" })
            )
        }
        val body = JSONObject().put("attachments", arr).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        return try {
            val req = Request.Builder()
                .url("https://app.teable.ai/api/base/$baseId/sign-attachment-urls")
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    log("Не удалось подписать URL: HTTP ${resp.code}", true, false)
                    return emptyMap()
                }
                val signed = JSONObject(resp.body?.string().orEmpty()).optJSONArray("attachments")
                    ?: return emptyMap()
                val out = mutableMapOf<String, String>()
                for (i in 0 until signed.length()) {
                    val o = signed.optJSONObject(i) ?: continue
                    val t = o.optString("token", "")
                    val u = o.optString("url", "")
                    if (t.isNotBlank() && u.isNotBlank()) out[t] = u
                }
                out
            }
        } catch (e: Exception) {
            log("Ошибка подписи URL: ${e.message}", true, false)
            emptyMap()
        }
    }

    private fun fetchImage(url: String, token: String): ByteArray? {
        for (withAuth in listOf(false, true)) {
            try {
                val b = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36")
                    .header("Accept", "image/*,*/*;q=0.8")
                if (withAuth) b.header("Authorization", "Bearer $token")
                client.newCall(b.build()).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.bytes()?.let { return it }
                }
            } catch (_: Exception) { }
        }
        return null
    }

    private fun decode(bytes: ByteArray) = try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (_: Exception) { null }

    private fun isImage(name: String, mime: String, url: String): Boolean {
        if (mime.lowercase(Locale.ROOT).startsWith("image/")) return true
        val ext = (name.substringAfterLast('.', "").ifBlank { url.substringAfterLast('.', "") })
            .lowercase(Locale.ROOT)
        return ext in setOf("png", "jpg", "jpeg", "webp", "gif", "svg", "ico", "bmp", "avif")
    }

    private fun getJsonArray(path: String, token: String): JSONArray? {
        val obj = getJsonObject(path, token) ?: return null
        return obj.optJSONArray("data") ?: obj.optJSONArray("records")
    }

    private fun getJsonObject(path: String, token: String): JSONObject? = try {
        val req = Request.Builder()
            .url("https://app.teable.ai/api$path")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                log("Teable API $path: HTTP ${resp.code}", true, false)
                null
            } else {
                val text = resp.body?.string().orEmpty()
                if (text.trimStart().startsWith("[")) {
                    JSONObject().put("data", JSONArray(text))
                } else JSONObject(text)
            }
        }
    } catch (e: Exception) {
        log("Ошибка Teable API: ${e.message}", true, false)
        null
    }
}
