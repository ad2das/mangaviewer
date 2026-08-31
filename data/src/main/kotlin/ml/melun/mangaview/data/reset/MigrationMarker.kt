package ml.melun.mangaview.data.reset

import java.io.File
import java.io.FileOutputStream
import ml.melun.mangaview.data.cache.AtomicFilePublisher
import ml.melun.mangaview.data.cache.PosixAtomicFilePublisher

class MigrationMarker(
    private val directory: File,
    private val version: Int,
    private val publisher: AtomicFilePublisher = PosixAtomicFilePublisher(),
) {
    private val destination: File
        get() = File(directory, "mangaviewer-reset-v$version.complete")

    fun isComplete(): Boolean = destination.isFile

    fun complete() {
        require(directory.exists() && directory.isDirectory || directory.mkdirs()) {
            "Migration marker directory is unavailable"
        }
        val staging = File(directory, "${destination.name}.part")
        try {
            FileOutputStream(staging, false).use { output ->
                output.write("complete\n".toByteArray(Charsets.US_ASCII))
                output.fd.sync()
            }
            publisher.publish(staging, destination)
        } finally {
            staging.delete()
        }
    }
}
