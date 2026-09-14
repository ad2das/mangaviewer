package ml.melun.mangaview.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.SourceSearchQuery
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Diagnostic: newxtoon search across query shapes. */
@RunWith(AndroidJUnit4::class)
class NewxtoonSearchVariantsDeviceTest {
    @Test fun searchVariants() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as ViewerApplication
        val source = application.graph.sources.require(SourceId("newxtoon"))
        val queries = listOf(
            "로맨스",
            "나 혼자만 레벨업",
            "화산귀환",
            "헌터",
            "레벨",
            "test",
            "ㅁ",
            "1",
        )
        val result = JSONObject()
        for (query in queries) {
            val entry = JSONObject()
            try {
                val page = source.search(SourceSearchQuery(query))
                entry.put("ok", true)
                entry.put("count", page.items.size)
                entry.put("titles", page.items.take(3).joinToString(" | ") { it.title })
            } catch (failure: Throwable) {
                entry.put("ok", false)
                entry.put("error", failure.message ?: failure::class.java.name)
            }
            result.put(query, entry)
        }
        val output = File(context.getExternalFilesDir(null), "newxtoon-search-variants")
        output.mkdirs()
        output.resolve("result.json").writeText(result.toString(2))
    }
}
