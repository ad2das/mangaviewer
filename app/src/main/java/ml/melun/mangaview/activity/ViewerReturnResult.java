package ml.melun.mangaview.activity;

import android.content.Intent;

import ml.melun.mangaview.Utils;
import ml.melun.mangaview.mangaview.Title;

final class ViewerReturnResult {
    private ViewerReturnResult() {
    }

    static String episodeListTitleJson(Title targetTitle) {
        if(targetTitle == null)
            return null;
        try {
            return Utils.toViewerTitleJson(targetTitle, true);
        } catch(Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            return null;
        }
    }

    static void addEpisodeListResult(Intent target, String titleJson) {
        if(target == null || titleJson == null || titleJson.trim().length() == 0)
            return;
        target.putExtra(ViewerIntentContract.EXTRA_RETURN_EPISODE_SOURCE_SWITCHED, true);
        target.putExtra(ViewerIntentContract.EXTRA_RETURN_EPISODE_TITLE, titleJson);
    }
}

