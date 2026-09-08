package ml.melun.mangaview.update

import org.junit.Assert.*
import org.junit.Test

class UpdateReleaseTest {
    private val version = 2147000001L
    private val url = "https://github.com/ad2das/mangaviewer/releases/download/main-latest/mangaViewer_${version}-debug.apk"

    @Test fun readsBothLegacyAndNewReleaseMetadata() {
        val legacy = UpdateRelease.parseManifest("""{"version":$version,"link":"$url"}""")
        assertTrue(legacy.newerThan(2147000000))
        assertFalse(legacy.newerThan(version))
        assertFalse(legacy.newerThan(version + 1))
        val current = UpdateRelease.parseManifest("""{"version":$version,"link":"$url","sha256":"${"a".repeat(64)}","size":4096,"versionName":"5.0.0"}""")
        assertEquals(4096L, current.size)
        assertEquals("5.0.0 ($version)", current.label)
    }

    @Test fun rejectsMismatchedApkNamesAndUntrustedLinks() {
        for (link in listOf(url.replace(version.toString(), "2147000000"), url.replace("https:", "http:"),
            url.replace("github.com", "github.com.evil.example"), url.replace("ad2das", "another-owner"),
            "$url?redirect=1", "$url#file", url.replace("github.com", "user@github.com"))) {
            assertThrows(IllegalArgumentException::class.java) { UpdateRelease(version, link) }
        }
    }

    @Test fun rejectsTruncatedVersionsAndInvalidFileProof() {
        assertThrows(ArithmeticException::class.java) {
            UpdateRelease.parseManifest("""{"version":2147000001.5,"link":"$url"}""")
        }
        assertThrows(IllegalArgumentException::class.java) { UpdateRelease(version, url, sha256 = "bad") }
        assertThrows(IllegalArgumentException::class.java) { UpdateRelease(version, url, size = 0) }
        assertThrows(IllegalArgumentException::class.java) { UpdateRelease(version, url, size = UpdateRelease.MAX_APK_BYTES + 1) }
        assertThrows(IllegalArgumentException::class.java) { UpdateRelease(Int.MAX_VALUE.toLong() + 1, url) }
    }

    @Test fun releaseFallbackSelectsNewestApkAndIgnoresClassificationAssets() {
        val json = """{"tag_name":"main-latest","draft":false,"assets":[
          {"name":"classification-base.sqlite.gz","size":100,"browser_download_url":"https://example.com/data"},
          {"name":"mangaViewer_2147000000-debug.apk","size":200,"browser_download_url":"${url.replace(version.toString(), "2147000000")}"},
          {"name":"mangaViewer_$version-debug.apk","size":300,"browser_download_url":"$url","digest":"sha256:${"b".repeat(64)}"}]}"""
        val release = UpdateRelease.parseRelease(json)
        assertEquals(version, release.version)
        assertEquals(300L, release.size)
        assertEquals("b".repeat(64), release.sha256)
    }

    @Test fun noApkIsAnErrorRatherThanUpToDate() {
        assertThrows(IllegalStateException::class.java) {
            UpdateRelease.parseRelease("""{"tag_name":"main-latest","assets":[]}""")
        }
    }
}
