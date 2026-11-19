package ru.workspace.pwaiconextractor

import androidx.compose.ui.graphics.ImageBitmap

enum class IconSource(val label: String) {
    MANIFEST("manifest"),
    LINK_ICON("icon"),
    APPLE_TOUCH("apple-touch-icon"),
    OG_IMAGE("og:image"),
    TWITTER_IMAGE("twitter:image"),
    IMG_TAG("img"),
    DIRECT("прямая ссылка"),
    BASE44("медиатека Base44"),
    TEABLE("вложения Teable"),
}

data class IconItem(
    val id: String,
    val url: String,
    val sizeLabel: String,
    val mimeType: String?,
    val source: IconSource,
    val bytes: ByteArray? = null,
    val bitmap: ImageBitmap? = null,
    val selected: Boolean = true,
    val saveStatus: String? = null,
)

data class LogEntry(
    val message: String,
    val isError: Boolean = false,
    val isSuccess: Boolean = false,
)
