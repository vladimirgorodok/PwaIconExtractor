package ru.workspace.pwaiconextractor

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

data class DiscoveredIcon(
    val url: String,
    val sizeLabel: String,
    val mimeType: String?,
    val source: IconSource,
)

class IconExtractor(
    private val log: (String, Boolean, Boolean) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun extract(pageUrl: String): List<IconItem> {
        val normalized = normalizePageUrl(pageUrl)
        log("Начало извлечения: $normalized", false, false)

        val html = fetchText(normalized)
        if (html == null) {
            log("Не удалось загрузить страницу", true, false)
            return emptyList()
        }
        log("Страница загружена (${html.length} символов)", false, false)

        val discovered = LinkedHashMap<String, DiscoveredIcon>()

        fun add(d: DiscoveredIcon) {
            val key = d.url.trim()
            if (key.isBlank()) return
            if (key.startsWith("data:", ignoreCase = true)) return
            if (!discovered.containsKey(key)) discovered[key] = d
        }

        // 1) Manifest
        val manifestHref = findManifestHref(html)
        if (manifestHref != null) {
            val manifestUrl = resolveUrl(normalized, manifestHref)
            if (manifestUrl != null) {
                log("Найден manifest: $manifestUrl", false, false)
                val manifestText = fetchText(manifestUrl)
                if (manifestText != null) {
                    val manifestIcons = parseManifestIcons(manifestText, manifestUrl)
                    manifestIcons.forEach { add(it) }
                    log("Иконок в manifest: ${manifestIcons.size}", false, false)
                } else {
                    log("Не удалось загрузить manifest", true, false)
                }
            }
        } else {
            log("Manifest не найден в HTML", false, false)
        }

        // 2) <link rel="icon">, apple-touch-icon, etc.
        val linkIcons = parseLinkIcons(html, normalized)
        linkIcons.forEach { add(it) }
        log("Найдено иконок в <link>: ${linkIcons.size}", false, false)

        // 3) meta og:image / twitter:image
        val metaImages = parseMetaImages(html, normalized)
        metaImages.forEach { add(it) }
        if (metaImages.isNotEmpty()) log("Найдено изображений в meta: ${metaImages.size}", false, false)

        // 4) <img> tags
        val imgIcons = parseImgTags(html, normalized)
        imgIcons.forEach { add(it) }
        log("Найдено изображений в <img>: ${imgIcons.size}", false, false)

        val all = discovered.values.toList()
        val limited = if (all.size > 300) {
            log("Найдено ${all.size} изображений, ограничено до 300", false, false)
            all.take(300)
        } else all

        log("Всего уникальных ресурсов: ${limited.size}. Загрузка...", false, false)

        val items = mutableListOf<IconItem>()
        limited.forEachIndexed { index, d ->
            log("Загрузка ${index + 1}/${limited.size}: ${shortUrl(d.url)}", false, false)
            val bytes = fetchBytes(d.url, normalized)
            val bitmap = bytes?.let { decodeBitmap(it) }?.asImageBitmap()
            if (bytes == null) {
                log("Ошибка загрузки: ${shortUrl(d.url)}", true, false)
            }
            items.add(
                IconItem(
                    id = UUID.randomUUID().toString(),
                    url = d.url,
                    sizeLabel = d.sizeLabel.ifBlank { bitmap?.let { "${it.width}x${it.height}" } ?: "—" },
                    mimeType = d.mimeType,
                    source = d.source,
                    bytes = bytes,
                    bitmap = bitmap,
                    selected = bytes != null,
                )
            )
        }

        val ok = items.count { it.bytes != null }
        log("Извлечение завершено: успешно $ok из ${items.size}", false, true)
        return items
    }

    /** Загружает все изображения медиатеки Base44 по app_id через Apps API. */
    fun extractBase44Assets(appId: String, apiKey: String): List<IconItem> {
        log("Запрос медиатеки Base44, app_id: $appId", false, false)
        val assets = fetchAssets(appId, apiKey) ?: return emptyList()
        log("Получено файлов в медиатеке: ${assets.length()}", false, false)

        val imageAssets = mutableListOf<Triple<String, String, String>>()
        for (i in 0 until assets.length()) {
            val obj = assets.optJSONObject(i) ?: continue
            val fileName = obj.optString("file_name", "")
            val url = obj.optString("file_url", "")
            val type = obj.optString("file_type", "")
            if (url.isBlank() || !isImageAsset(fileName, url, type)) continue
            imageAssets.add(Triple(url, fileName, type))
        }
        log("Изображений в медиатеке: ${imageAssets.size}", false, false)
        if (imageAssets.isEmpty()) {
            log("Изображений не найдено", true, false)
            return emptyList()
        }

        val items = mutableListOf<IconItem>()
        imageAssets.forEachIndexed { index, (url, fileName, type) ->
            log("Загрузка ${index + 1}/${imageAssets.size}: $fileName", false, false)
            val bytes = fetchBytes(url, "https://app.base44.com/")
            val bitmap = bytes?.let { decodeBitmap(it) }?.asImageBitmap()
            if (bytes == null) log("Ошибка загрузки: $fileName", true, false)
            items.add(
                IconItem(
                    id = UUID.randomUUID().toString(),
                    url = url,
                    sizeLabel = bitmap?.let { "${it.width}x${it.height}" }
                        ?: type.uppercase(Locale.ROOT).ifBlank { "—" },
                    mimeType = null,
                    source = IconSource.BASE44,
                    bytes = bytes,
                    bitmap = bitmap,
                    selected = bytes != null,
                )
            )
        }
        val ok = items.count { it.bytes != null }
        log("Медиатека загружена: успешно $ok из ${items.size}", false, true)
        return items
    }

    private fun fetchAssets(appId: String, apiKey: String): JSONArray? {
        val url = "https://app.base44.com/api/apps/$appId/assets"
        val attempts = listOf(
            "Authorization" to "Bearer $apiKey",
            "api_key" to apiKey
        )
        for ((header, value) in attempts) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header(header, value)
                    .header("Accept", "application/json")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        return try {
                            JSONArray(body)
                        } catch (_: Exception) {
                            log("Не удалось разобрать ответ медиатеки", true, false)
                            null
                        }
                    }
                    log("Ошибка Base44 API: HTTP ${resp.code}", true, false)
                }
            } catch (e: Exception) {
                log("Ошибка запроса Base44 API: ${e.message}", true, false)
            }
        }
        return null
    }

    private fun isImageAsset(fileName: String, url: String, type: String): Boolean {
        val ext = type.lowercase(Locale.ROOT).ifBlank {
            (fileName.substringAfterLast('.', "").ifBlank { url.substringAfterLast('.', "") })
                .lowercase(Locale.ROOT)
        }
        return ext in setOf("png", "jpg", "jpeg", "webp", "gif", "svg", "ico", "bmp", "avif", "heic")
    }

    companion object {
        /** Извлекает app_id из ссылки на файл Base44, из app.base44.com/apps/<id> или из чистого ID. */
        fun extractAppId(input: String): String? {
            val s = input.trim()
            if (Regex("^[0-9a-fA-F]{24}$").matches(s)) return s

            val patterns = listOf(
                Regex("""media\.base44\.com/(?:images|files)/public/([0-9a-fA-F]{24})/""", RegexOption.IGNORE_CASE),
                Regex("""app\.base44\.com/apps/([0-9a-fA-F]{24})""", RegexOption.IGNORE_CASE),
                Regex("""([0-9a-fA-F]{24})""")
            )
            for (p in patterns) {
                val m = p.find(s)
                if (m != null) return m.groupValues[1]
            }
            return null
        }
    }

    private fun normalizePageUrl(input: String): String {
        var u = input.trim()
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) {
            u = "https://$u"
        }
        return u
    }

    private fun fetchText(url: String): String? = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                log("HTTP ${resp.code} для ${shortUrl(url)}", true, false)
                null
            } else resp.body?.string()
        }
    } catch (e: Exception) {
        log("Ошибка запроса ${shortUrl(url)}: ${e.message}", true, false)
        null
    }

    private fun fetchBytes(url: String, referer: String): ByteArray? {
        // Некоторые сайты (Teable/S3) отдают изображения только с правильным Referer.
        val attempts = listOf(referer, "")
        for (ref in attempts) {
            val result = try {
                val builder = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
                    .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                if (ref.isNotBlank()) builder.header("Referer", ref)
                client.newCall(builder.build()).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.bytes() else null
                }
            } catch (_: Exception) {
                null
            }
            if (result != null) return result
        }
        return null
    }

    private fun decodeBitmap(bytes: ByteArray) = try {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    } catch (_: Exception) { null }

    private fun resolveUrl(base: String, href: String): String? = try {
        URI(base).resolve(href.trim()).toString()
    } catch (_: Exception) { null }

    private fun shortUrl(url: String): String = if (url.length > 80) url.take(77) + "..." else url

    private fun findManifestHref(html: String): String? {
        val tags = findTags(html, "link")
        for (tag in tags) {
            val attrs = parseAttrs(tag)
            val rel = attrs["rel"]?.lowercase(Locale.ROOT) ?: continue
            if (rel == "manifest") return attrs["href"]
        }
        return null
    }

    private fun parseLinkIcons(html: String, base: String): List<DiscoveredIcon> {
        val out = mutableListOf<DiscoveredIcon>()
        for (tag in findTags(html, "link")) {
            val attrs = parseAttrs(tag)
            val rel = attrs["rel"]?.lowercase(Locale.ROOT) ?: continue
            val href = attrs["href"] ?: continue
            val abs = resolveUrl(base, href) ?: continue
            val sizes = attrs["sizes"] ?: ""
            val type = attrs["type"]
            val source = when {
                rel.contains("apple-touch-icon") -> IconSource.APPLE_TOUCH
                rel.contains("icon") -> IconSource.LINK_ICON
                else -> continue
            }
            out.add(DiscoveredIcon(abs, sizes, type, source))
        }
        return out
    }

    private fun parseMetaImages(html: String, base: String): List<DiscoveredIcon> {
        val out = mutableListOf<DiscoveredIcon>()
        for (tag in findTags(html, "meta")) {
            val attrs = parseAttrs(tag)
            val key = (attrs["property"] ?: attrs["name"] ?: "").lowercase(Locale.ROOT)
            val content = attrs["content"] ?: continue
            val source = when (key) {
                "og:image", "og:image:url", "og:image:secure_url" -> IconSource.OG_IMAGE
                "twitter:image", "twitter:image:src" -> IconSource.TWITTER_IMAGE
                else -> continue
            }
            val abs = resolveUrl(base, content) ?: continue
            out.add(DiscoveredIcon(abs, "", null, source))
        }
        return out
    }

    private fun parseImgTags(html: String, base: String): List<DiscoveredIcon> {
        val out = mutableListOf<DiscoveredIcon>()
        for (tag in findTags(html, "img")) {
            val attrs = parseAttrs(tag)
            val src = attrs["src"] ?: attrs["data-src"] ?: continue
            val abs = resolveUrl(base, src) ?: continue
            val w = attrs["width"]
            val h = attrs["height"]
            val size = if (!w.isNullOrBlank() && !h.isNullOrBlank()) "${w}x${h}" else ""
            out.add(DiscoveredIcon(abs, size, null, IconSource.IMG_TAG))
        }
        return out
    }

    private fun parseManifestIcons(jsonText: String, manifestUrl: String): List<DiscoveredIcon> {
        val out = mutableListOf<DiscoveredIcon>()
        try {
            val obj = JSONObject(jsonText)
            val icons = obj.optJSONArray("icons") ?: JSONArray()
            for (i in 0 until icons.length()) {
                val icon = icons.optJSONObject(i) ?: continue
                val src = icon.optString("src", "")
                if (src.isBlank()) continue
                val abs = resolveUrl(manifestUrl, src) ?: continue
                val sizes = icon.optString("sizes", "")
                val type = icon.optString("type", null)
                out.add(DiscoveredIcon(abs, sizes, type, IconSource.MANIFEST))
            }
        } catch (e: Exception) {
            log("Ошибка разбора manifest: ${e.message}", true, false)
        }
        return out
    }

    private fun findTags(html: String, name: String): List<String> {
        val regex = Regex("<$name\\b[^>]*>", setOf(RegexOption.IGNORE_CASE))
        return regex.findAll(html).map { it.value }.toList()
    }

    private fun parseAttrs(tag: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val regex = Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")
        for (m in regex.findAll(tag)) {
            val key = m.groupValues[1].lowercase(Locale.ROOT)
            val value = m.groupValues[2].ifEmpty { m.groupValues[3].ifEmpty { m.groupValues[4] } }
            map[key] = value
        }
        return map
    }
}
