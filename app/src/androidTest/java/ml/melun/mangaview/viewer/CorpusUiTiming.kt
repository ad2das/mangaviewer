package ml.melun.mangaview.viewer

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Host-independent monotonic navigation timings; never waits for content or changes UI state. */
internal class CorpusUiTiming(private val output: File?) {
    private val events = JSONArray()

    fun mark(stage: String) {
        events.put(JSONObject().put("stage", stage).put("atMonotonicNs", System.nanoTime()))
    }

    fun <T> measure(stage: String, action: () -> T): T {
        val start = System.nanoTime()
        var succeeded = false
        try {
            return action().also { succeeded = true }
        } finally {
            events.put(JSONObject().put("stage", stage).put("startedMonotonicNs", start)
                .put("finishedMonotonicNs", System.nanoTime()).put("succeeded", succeeded))
        }
    }

    fun save() {
        output?.writeText(JSONObject().put("clock", "System.nanoTime")
            .put("scope", "UI automation overhead and navigation; not physical presentation")
            .put("events", events).toString(2))
    }
}
