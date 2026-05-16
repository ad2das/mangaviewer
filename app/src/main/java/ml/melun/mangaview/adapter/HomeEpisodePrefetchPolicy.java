package ml.melun.mangaview.adapter;

import java.util.Locale;

final class HomeEpisodePrefetchPolicy {
    private HomeEpisodePrefetchPolicy() {
    }

    static boolean shouldPrefetchVisibleEpisodeSnapshot(String sourceSite, boolean ntkSite) {
        if(sourceSite == null || sourceSite.trim().length() == 0)
            return true;
        String source = sourceSite.trim().toLowerCase(Locale.ROOT);
        if("ntk".equals(source))
            return ntkSite;
        if("wfwf".equals(source) || source.startsWith("wolf"))
            return !ntkSite;
        return true;
    }

    static boolean shouldPrefetchViewerImagesFromHome(String sourceSite, boolean ntkSite) {
        if(!ntkSite)
            return true;
        if(sourceSite == null || sourceSite.trim().length() == 0)
            return false;
        return !"ntk".equals(sourceSite.trim().toLowerCase(Locale.ROOT));
    }
}
