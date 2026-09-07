package ml.melun.mangaview.source.ntk

import java.io.File

/** Explicit diagnostic runner for an unmodified captured provider document; not corpus credit. */
object NtkManifestSnapshotCheck {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 3) { "Expected captured HTML file, document origin, episode path" }
        val document = NtkEpisodeDocument(args[1], args[2], File(args[0]).readText())
        repeat(3) { iteration ->
            val started = System.nanoTime()
            val result = NtkDocumentParser().manifest(document)
            val descriptor = requireNotNull(result.descriptor) { "Captured document has no verified viewer" }
            println("snapshot iteration=$iteration bytes=${document.html.toByteArray().size} " +
                "elapsedMs=${(System.nanoTime() - started) / 1_000_000L} " +
                "work=${descriptor.workId} episode=${descriptor.episodeId} " +
                "pages=${descriptor.expectedPageCount} direct=${result.directPages.size}")
        }
    }
}
