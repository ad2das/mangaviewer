package ml.melun.mangaview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Title;

@RunWith(AndroidJUnit4.class)
public class PreferenceReloadInstrumentedTest {
    @Test
    public void reloadingAnEmptyRecentListRetiresThePreviousLookupIndex() {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences preferences =
                context.getSharedPreferences("mangaView", Context.MODE_PRIVATE);
        Map<String, ?> savedPreferences = new HashMap<>(preferences.getAll());
        try {
            MTitle stale = new MTitle(
                    "stale", 991337, "", "", null, "", MTitle.base_comic);
            stale.setSourceSite("ntk");
            MainApplication.p.runWithoutSync(
                    () -> MainApplication.p.setRecents(Collections.singletonList(stale)));
            assertNotNull(MainApplication.p.findRecentTitle(stale));

            preferences.edit().putString("recent", "[]").commit();
            MainApplication.p.init(context);

            Title fresh = new Title(
                    "fresh", "", "", null, "", 991337, MTitle.base_comic);
            fresh.setSourceSite("ntk");
            MainApplication.p.runWithoutSync(() -> MainApplication.p.addRecent(fresh));

            assertEquals(1, MainApplication.p.getRecent().size());
            assertEquals("fresh", MainApplication.p.getRecent().get(0).getName());
        } finally {
            restorePreferences(preferences, savedPreferences);
            MainApplication.p.init(context);
            MainApplication.httpClient = null;
        }
    }

    @SuppressWarnings("unchecked")
    private static void restorePreferences(
            SharedPreferences preferences,
            Map<String, ?> values
    ) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for(Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue();
            if(value instanceof String)
                editor.putString(entry.getKey(), (String)value);
            else if(value instanceof Integer)
                editor.putInt(entry.getKey(), (Integer)value);
            else if(value instanceof Long)
                editor.putLong(entry.getKey(), (Long)value);
            else if(value instanceof Float)
                editor.putFloat(entry.getKey(), (Float)value);
            else if(value instanceof Boolean)
                editor.putBoolean(entry.getKey(), (Boolean)value);
            else if(value instanceof Set)
                editor.putStringSet(entry.getKey(), new HashSet<>((Set<String>)value));
        }
        editor.commit();
    }
}
