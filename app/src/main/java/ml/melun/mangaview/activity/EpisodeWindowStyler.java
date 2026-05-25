package ml.melun.mangaview.activity;

import android.app.Activity;
import android.view.View;

import androidx.core.content.ContextCompat;

import ml.melun.mangaview.R;

final class EpisodeWindowStyler {
    private EpisodeWindowStyler() {
    }

    static void apply(Activity activity, boolean dark, View episodeList) {
        View root = activity.findViewById(android.R.id.content);
        View appBar = activity.findViewById(R.id.episode_toolbar);
        View toolbar = activity.findViewById(R.id.toolbar);
        int surface = ContextCompat.getColor(activity, dark ? R.color.colorDarkWindowBackground : R.color.appSurface);
        int chrome = ContextCompat.getColor(activity, dark ? R.color.colorDarkSurface : R.color.appSurface);
        activity.getWindow().setStatusBarColor(chrome);
        activity.getWindow().setNavigationBarColor(surface);
        if(root != null)
            root.setBackgroundColor(surface);
        if(appBar != null)
            appBar.setBackgroundColor(chrome);
        if(toolbar != null)
            toolbar.setBackgroundColor(chrome);
        if(episodeList != null)
            episodeList.setBackgroundColor(surface);
        if(dark) {
            activity.getWindow().getDecorView().setSystemUiVisibility(0);
            return;
        }
        activity.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }
}

