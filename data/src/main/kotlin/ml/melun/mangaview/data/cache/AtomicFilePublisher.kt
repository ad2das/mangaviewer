package ml.melun.mangaview.data.cache

import android.system.Os
import java.io.File

fun interface AtomicFilePublisher {
    fun publish(staging: File, destination: File)
}

class PosixAtomicFilePublisher : AtomicFilePublisher {
    override fun publish(staging: File, destination: File) {
        Os.rename(staging.absolutePath, destination.absolutePath)
    }
}
