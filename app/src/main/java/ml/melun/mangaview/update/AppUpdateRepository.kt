package ml.melun.mangaview.update

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.CoroutineContext

internal class AppUpdateRepository(private val context: Context, clientFactory: () -> OkHttpClient = {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS).build()
    }) {
    private val client by lazy(clientFactory)

    suspend fun latest(): UpdateRelease = withContext(Dispatchers.IO) {
        try {
            UpdateRelease.parseManifest(metadata(UpdateRelease.METADATA_URL))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            // The release asset is authoritative; the branch's old version.json is not updated by CI.
            UpdateRelease.parseRelease(metadata(UpdateRelease.RELEASE_API_URL))
        }
    }

    private suspend fun metadata(url: String): String = withResponse("$url?check=${System.currentTimeMillis()}") { response, owner ->
        check(response.isSuccessful) { "업데이트 서버에 연결하지 못했습니다 (${response.code})" }
        val body = requireNotNull(response.body)
        body.byteStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                owner.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                check(output.size() + count <= 256 * 1024) { "업데이트 정보가 너무 큽니다" }
                output.write(buffer, 0, count)
            }
            output.toString("UTF-8")
        }
    }

    suspend fun download(release: UpdateRelease, progress: (Int?) -> Unit): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "app-updates").apply { check(mkdirs() || isDirectory) }
        val partial = File(directory, "${release.version}.apk.part")
        val complete = File(directory, "${release.version}.apk")
        var succeeded = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            withResponse(release.link) { response, owner ->
                check(response.isSuccessful) { "업데이트 다운로드에 실패했습니다 (${response.code})" }
                val body = requireNotNull(response.body)
                val length = body.contentLength().takeIf { it >= 0 }
                if (release.size != null && length != null) check(release.size == length) { "업데이트 파일 크기가 다릅니다" }
                val expected = release.size ?: length
                check(expected == null || expected in 1..UpdateRelease.MAX_APK_BYTES) { "업데이트 파일 크기가 올바르지 않습니다" }
                var received = 0L
                var lastPercent = -1
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            owner.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            check(received <= UpdateRelease.MAX_APK_BYTES && (expected == null || received <= expected)) {
                                "업데이트 파일 크기가 다릅니다"
                            }
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            val percent = expected?.let { (received * 100 / it).toInt() }
                            if (percent != lastPercent) { progress(percent); lastPercent = percent ?: -1 }
                        }
                        output.fd.sync()
                    }
                }
                check(received > 0 && (expected == null || received == expected)) { "업데이트 다운로드가 완료되지 않았습니다" }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            check(release.sha256 == null || release.sha256 == hash) { "업데이트 파일 검증에 실패했습니다" }
            verifyPackage(partial, release.version)
            currentCoroutineContext().ensureActive()
            check((!complete.exists() || complete.delete()) && partial.renameTo(complete)) { "업데이트 파일을 저장하지 못했습니다" }
            succeeded = true
            complete
        } finally {
            if (!succeeded) partial.delete()
        }
    }

    @Suppress("DEPRECATION")
    internal fun verifyPackage(file: File, version: Long) {
        val manager = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = requireNotNull(manager.getPackageArchiveInfo(file.absolutePath, flags)) { "올바른 APK 파일이 아닙니다" }
        val installed = manager.getPackageInfo(context.packageName, flags)
        check(archive.packageName == context.packageName && archive.longVersionCode == version && version > installed.longVersionCode) {
            "설치할 앱의 버전 정보가 다릅니다"
        }
        val expected = installed.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        check(expected.isNotEmpty() && archive.signingInfo?.apkContentsSigners?.toSet() == expected) {
            "현재 앱과 업데이트의 서명이 다릅니다"
        }
    }

    private suspend fun <T> withResponse(url: String, consume: (Response, CoroutineContext) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", "MangaViewer-Updater")
            .header("Cache-Control", "no-cache").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) { continuation.resumeWithException(error) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val value = response.use { consume(it, continuation.context) }
                    continuation.resumeWith(Result.success(value))
                } catch (failure: Exception) { continuation.resumeWithException(failure) }
            }
        })
    }
}
