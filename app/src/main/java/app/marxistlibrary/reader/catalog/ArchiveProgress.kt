package app.marxistlibrary.reader.catalog

enum class ArchiveStage(val label: String) {
    CACHE("正在检查本地文献"),
    CONNECT("正在连接内容源"),
    DOWNLOAD("正在下载附件"),
    VERIFY("正在校验附件"),
    PARSE("正在解析文献"),
    SAVE("正在保存文献"),
    INDEX("正在建立离线索引"),
    UPDATES("正在同步文库更新")
}

data class ArchiveProgress(
    val stage: ArchiveStage,
    val received: Long = 0L,
    val total: Long? = null
) {
    // Only a known transfer size produces a percentage.
    val fraction: Float? get() = total?.takeIf { it > 0 }?.let {
        (received.toDouble() / it).toFloat().coerceIn(0f, 1f)
    }
}
