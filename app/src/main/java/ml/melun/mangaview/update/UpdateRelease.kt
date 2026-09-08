package ml.melun.mangaview.update

import com.google.gson.JsonParser
import java.net.URI

internal data class UpdateRelease(
    val version: Long,
    val link: String,
    val versionName: String? = null,
    val sha256: String? = null,
    val size: Long? = null,
) {
    init {
        require(version in 1..Int.MAX_VALUE.toLong()) { "업데이트 버전 정보가 올바르지 않습니다" }
        val uri = URI(link)
        require(uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 &&
            uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath == "/ad2das/mangaviewer/releases/download/main-latest/mangaViewer_${version}-debug.apk") {
            "공식 업데이트 주소가 아닙니다"
        }
        require(sha256 == null || sha256.matches(Regex("[0-9a-f]{64}"))) { "업데이트 파일 정보가 올바르지 않습니다" }
        require(size == null || size in 1..MAX_APK_BYTES) { "업데이트 파일 크기가 올바르지 않습니다" }
    }

    fun newerThan(installedVersion: Long): Boolean = version > installedVersion
    val label: String get() = versionName?.takeIf(String::isNotBlank)?.let { "$it ($version)" } ?: version.toString()

    companion object {
        const val MAX_APK_BYTES = 128L * 1024 * 1024
        const val METADATA_URL = "https://github.com/ad2das/mangaviewer/releases/download/main-latest/version.json"
        const val RELEASE_API_URL = "https://api.github.com/repos/ad2das/mangaviewer/releases/tags/main-latest"

        fun parseManifest(text: String): UpdateRelease {
            val row = JsonParser.parseString(text).asJsonObject
            return UpdateRelease(row.get("version").asBigDecimal.longValueExact(), row.get("link").asString,
                row.get("versionName")?.takeUnless { it.isJsonNull }?.asString,
                row.get("sha256")?.takeUnless { it.isJsonNull }?.asString,
                row.get("size")?.takeUnless { it.isJsonNull }?.asBigDecimal?.longValueExact())
        }

        fun parseRelease(text: String): UpdateRelease {
            val row = JsonParser.parseString(text).asJsonObject
            require(row.get("tag_name").asString == "main-latest" && row.get("draft")?.asBoolean != true)
            return row.getAsJsonArray("assets").mapNotNull { element ->
                val asset = element.asJsonObject
                val match = Regex("mangaViewer_([0-9]+)-debug\\.apk").matchEntire(asset.get("name").asString)
                    ?: return@mapNotNull null
                val digest = asset.get("digest")?.takeUnless { it.isJsonNull }?.asString
                UpdateRelease(match.groupValues[1].toLong(), asset.get("browser_download_url").asString,
                    sha256 = digest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
                    size = asset.get("size").asBigDecimal.longValueExact())
            }.maxByOrNull { it.version } ?: error("배포된 업데이트 APK가 없습니다")
        }
    }
}
