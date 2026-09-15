package ml.melun.mangaview.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CoroutineScope

data class ViewerSettings(
    val darkTheme: Boolean = false,
    val rightToLeft: Boolean = false,
    val stretchToWidth: Boolean = true,
    val startTab: Int = 0,
    val sourceKey: String = "ntk",
    val seriesKind: Int = 0,
    val keepScreenOn: Boolean = false,
    val immersiveMode: Boolean = false,
    val volumeKeyNavigation: Boolean = false,
    val readerDimPercent: Int = 0,
    val recentQueries: List<String> = emptyList(),
)

class ViewerSettingsStore(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<ViewerSettings> = dataStore.data.map(::decode)

    suspend fun update(transform: (ViewerSettings) -> ViewerSettings) {
        dataStore.edit { preferences ->
            encode(preferences, transform(decode(preferences)))
        }
    }

    private fun decode(preferences: Preferences): ViewerSettings = ViewerSettings(
        darkTheme = preferences[DARK_THEME] ?: false,
        rightToLeft = preferences[RIGHT_TO_LEFT] ?: false,
        stretchToWidth = preferences[STRETCH_TO_WIDTH] ?: true,
        startTab = preferences[START_TAB] ?: 0,
        sourceKey = preferences[SOURCE_KEY] ?: "ntk",
        seriesKind = preferences[SERIES_KIND] ?: 0,
        keepScreenOn = preferences[KEEP_SCREEN_ON] ?: false,
        immersiveMode = preferences[IMMERSIVE_MODE] ?: false,
        volumeKeyNavigation = preferences[VOLUME_KEY_NAVIGATION] ?: false,
        readerDimPercent = (preferences[READER_DIM_PERCENT] ?: 0).coerceIn(0, MAX_READER_DIM_PERCENT),
        recentQueries = decodeRecentQueries(preferences[RECENT_QUERIES]),
    )

    private fun encode(preferences: androidx.datastore.preferences.core.MutablePreferences, value: ViewerSettings) {
        preferences[DARK_THEME] = value.darkTheme
        preferences[RIGHT_TO_LEFT] = value.rightToLeft
        preferences[STRETCH_TO_WIDTH] = value.stretchToWidth
        preferences[START_TAB] = value.startTab
        preferences[SOURCE_KEY] = value.sourceKey
        preferences[SERIES_KIND] = value.seriesKind
        preferences[KEEP_SCREEN_ON] = value.keepScreenOn
        preferences[IMMERSIVE_MODE] = value.immersiveMode
        preferences[VOLUME_KEY_NAVIGATION] = value.volumeKeyNavigation
        preferences[READER_DIM_PERCENT] = value.readerDimPercent.coerceIn(0, MAX_READER_DIM_PERCENT)
        preferences[RECENT_QUERIES] = encodeRecentQueries(value.recentQueries)
    }

    private fun decodeRecentQueries(raw: String?): List<String> = raw
        ?.split(RECENT_QUERY_SEPARATOR)
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.take(MAX_RECENT_QUERIES)
        ?: emptyList()

    private fun encodeRecentQueries(queries: List<String>): String = queries
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .take(MAX_RECENT_QUERIES)
        .joinToString(RECENT_QUERY_SEPARATOR)

    private companion object {
        const val MAX_READER_DIM_PERCENT = 70
        const val MAX_RECENT_QUERIES = 10
        const val RECENT_QUERY_SEPARATOR = "\n"
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val RIGHT_TO_LEFT = booleanPreferencesKey("right_to_left")
        val STRETCH_TO_WIDTH = booleanPreferencesKey("stretch_to_width")
        val START_TAB = intPreferencesKey("start_tab")
        val SOURCE_KEY = stringPreferencesKey("source_key")
        val SERIES_KIND = intPreferencesKey("series_kind")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val IMMERSIVE_MODE = booleanPreferencesKey("immersive_mode")
        val VOLUME_KEY_NAVIGATION = booleanPreferencesKey("volume_key_navigation")
        val READER_DIM_PERCENT = intPreferencesKey("reader_dim_percent")
        val RECENT_QUERIES = stringPreferencesKey("recent_queries")
    }
}

class ViewerSettingsStoreFactory(
    private val fileName: String = "viewer_settings_v2.preferences_pb",
) {
    fun open(
        context: Context,
        scope: CoroutineScope,
        ioDispatcher: CoroutineDispatcher,
    ): ViewerSettingsStore {
        val appContext = context.applicationContext
        val dataStoreScope = CoroutineScope(scope.coroutineContext + ioDispatcher)
        val dataStoreFile = File(appContext.applicationInfo.dataDir, "files/datastore/$fileName")
        val dataStore = PreferenceDataStoreFactory.create(scope = dataStoreScope) {
            dataStoreFile
        }
        return ViewerSettingsStore(dataStore)
    }
}
