package ml.melun.mangaview.account

import android.util.AtomicFile
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

internal data class AccountCheckpoint(val owner: String, val records: List<CloudLibraryRecord>)

/** Written on IO before network work; offline deletes survive process death. */
internal interface AccountCheckpointPort {
    fun read(): AccountCheckpoint?
    fun write(value: AccountCheckpoint)
}

internal class AccountCheckpointStore(file: File) : AccountCheckpointPort {
    private val file = AtomicFile(file)

    override fun read(): AccountCheckpoint? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val root = JsonParser.parseString(file.openRead().bufferedReader().use { it.readText() }).asJsonObject
        return AccountCheckpoint(root.get("owner").asString, CloudLibraryRecords.decode(root.get("library").asString))
    }

    override fun write(value: AccountCheckpoint) {
        file.baseFile.parentFile!!.mkdirs()
        val bytes = JsonObject().apply {
            addProperty("owner", value.owner)
            addProperty("library", CloudLibraryRecords.encode(value.records))
        }.toString().toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (failure: Throwable) { file.failWrite(stream); throw failure }
    }
}
