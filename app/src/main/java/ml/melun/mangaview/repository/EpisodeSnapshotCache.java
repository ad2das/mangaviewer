package ml.melun.mangaview.repository;

import java.util.Locale;

import ml.melun.mangaview.mangaview.Title;

public final class EpisodeSnapshotCache {
    private EpisodeSnapshotCache() {
    }

    public static String key(Title title, boolean fallbackNtk) {
        String source = title == null ? "" : title.getSourceSite();
        if(source == null || source.length() == 0)
            source = fallbackNtk ? "ntk" : "wfwf";
        return "episodeSnapshotV2_" + normalizeSource(source) + "_"
                + (title == null ? 0 : title.getBaseMode()) + "_"
                + (title == null ? 0 : title.getId());
    }

    public static String legacyKey(Title title) {
        return "episodeSnapshotV1_" + (title == null ? 0 : title.getBaseMode()) + "_"
                + (title == null ? 0 : title.getId());
    }

    private static String normalizeSource(String source) {
        if(source == null)
            return "";
        return source.trim().toLowerCase(Locale.ROOT);
    }
}
