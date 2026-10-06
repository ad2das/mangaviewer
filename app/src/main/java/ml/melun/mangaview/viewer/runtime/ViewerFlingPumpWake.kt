package ml.melun.mangaview.viewer.runtime

import android.os.Looper
import android.os.MessageQueue
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor

/**
 * Wake source for [ViewerFlingStepPump].
 *
 * The animation looper must never post to the main looper: `Handler.post` acquires the main
 * MessageQueue monitor, and while main is busy that acquisition delays the very vsync the step
 * exists to consume. The production source is a non-blocking pipe observed by the main looper, so
 * waking is one `Os.write` syscall from the animation looper and a listener invocation on main;
 * no Java monitor is touched by the waker. Implementations are injectable so the pump stays
 * testable on a plain JVM.
 */
internal interface ViewerFlingPumpWake {
    /** Registers the main-side listener; must run on the main thread. */
    fun start(onWake: () -> Unit)

    /** Removes the listener and closes the source; must run on the main thread. */
    fun stop()

    /** Wakes the main looper; called from the animation looper. Never blocks, may coalesce. */
    fun wake()
}

/**
 * Pipe-backed wake. [wake] writes a single byte; a full pipe already holds a pending wake and is
 * treated as already woken. The main-looper listener consumes every pending byte before running
 * its callback, so a kept descriptor never stays readable, which would otherwise spin the looper.
 */
internal class ViewerFlingPumpPipeWake : ViewerFlingPumpWake {
    private val lock = Any()
    private var readFd: FileDescriptor? = null
    private var writeFd: FileDescriptor? = null
    private val wakeByte = ByteArray(1)
    private val readBuffer = ByteArray(READ_BUFFER_BYTES)

    override fun start(onWake: () -> Unit) {
        stop()
        val descriptors = try {
            Os.pipe().also { pipe ->
                for (fd in pipe) {
                    Os.fcntlInt(fd, OsConstants.F_SETFL, OsConstants.O_NONBLOCK)
                    Os.fcntlInt(fd, OsConstants.F_SETFD, OsConstants.FD_CLOEXEC)
                }
            }
        } catch (failure: ErrnoException) {
            throw IllegalStateException("Fling pump wake pipe is unavailable", failure)
        }
        val listener = MessageQueue.OnFileDescriptorEventListener { _, _ ->
            drainRead(descriptors[0])
            onWake()
            MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT
        }
        synchronized(lock) {
            readFd = descriptors[0]
            writeFd = descriptors[1]
        }
        try {
            Looper.getMainLooper().queue.addOnFileDescriptorEventListener(descriptors[0],
                MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT, listener)
        } catch (failure: Throwable) {
            synchronized(lock) {
                readFd = null
                writeFd = null
            }
            for (fd in descriptors) runCatching { Os.close(fd) }
            throw failure
        }
    }

    override fun stop() {
        val read: FileDescriptor?
        val write: FileDescriptor?
        synchronized(lock) {
            read = readFd
            write = writeFd
            readFd = null
            writeFd = null
        }
        if (read != null) {
            Looper.getMainLooper().queue.removeOnFileDescriptorEventListener(read)
            runCatching { Os.close(read) }
        }
        if (write != null) runCatching { Os.close(write) }
    }

    override fun wake() {
        // The write holds the lock so a concurrent stop cannot close the descriptor mid-write.
        // EAGAIN means a wake byte is already pending, which is exactly what a wake means; any
        // other failure belongs to teardown, where no delivery is owed.
        synchronized(lock) {
            val fd = writeFd ?: return
            try {
                Os.write(fd, wakeByte, 0, 1)
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.EAGAIN) return
            }
        }
    }

    private fun drainRead(fd: FileDescriptor) {
        while (true) {
            val count = try {
                Os.read(fd, readBuffer, 0, readBuffer.size)
            } catch (error: ErrnoException) {
                // O_NONBLOCK: EAGAIN is an empty pipe, which ends the drain.
                return
            }
            if (count < readBuffer.size) return
        }
    }

    private companion object {
        const val READ_BUFFER_BYTES = 64
    }
}
