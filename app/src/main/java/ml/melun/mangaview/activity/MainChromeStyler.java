package ml.melun.mangaview.activity;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationView;

import ml.melun.mangaview.R;

final class MainChromeStyler {
    private MainChromeStyler() {
    }

    static void applyWindowChrome(Activity activity, boolean dark) {
        if(activity == null || activity.getWindow() == null)
            return;
        if(dark) {
            int background = ContextCompat.getColor(activity, R.color.colorDarkWindowBackground);
            activity.getWindow().setStatusBarColor(background);
            activity.getWindow().setNavigationBarColor(background);
            activity.getWindow().getDecorView().setSystemUiVisibility(0);
            View contentHolder = activity.findViewById(R.id.contentHolder);
            if(contentHolder != null) {
                contentHolder.setBackgroundColor(background);
                if(contentHolder.getParent() instanceof View)
                    ((View) contentHolder.getParent()).setBackgroundColor(background);
            }
            Toolbar toolbar = activity.findViewById(R.id.toolbar);
            if(toolbar != null) {
                toolbar.setBackgroundColor(background);
                toolbar.setTitleTextColor(ContextCompat.getColor(activity, R.color.colorDarkText));
            }
            View root = activity.findViewById(R.id.drawer_layout);
            if(root != null)
                root.setBackgroundColor(background);
            return;
        }
        activity.getWindow().setStatusBarColor(ContextCompat.getColor(activity, R.color.appSurface));
        activity.getWindow().setNavigationBarColor(ContextCompat.getColor(activity, R.color.appCard));
        activity.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }

    static void applyBottomNavigation(Activity activity, BottomNavigationView bottomNavigationView, boolean dark) {
        if(activity == null || bottomNavigationView == null || !dark)
            return;
        GradientDrawable background = new GradientDrawable();
        background.setColor(ContextCompat.getColor(activity, R.color.colorDarkSurface));
        background.setStroke(dp(activity, 1), ContextCompat.getColor(activity, R.color.colorDarkDivider));
        background.setCornerRadius(dp(activity, 24));
        bottomNavigationView.setBackground(background);
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        int[] colors = new int[]{
                ContextCompat.getColor(activity, R.color.appAccent),
                ContextCompat.getColor(activity, R.color.colorDarkTextSecondary)
        };
        ColorStateList tint = new ColorStateList(states, colors);
        bottomNavigationView.setItemIconTintList(tint);
        bottomNavigationView.setItemTextColor(tint);
    }

    static void applyNavigationDrawer(NavigationView navigationView, boolean dark) {
        if(navigationView == null || !dark)
            return;
        int[][] states = new int[][]{
                new int[]{-android.R.attr.state_enabled},
                new int[]{android.R.attr.state_enabled},
                new int[]{-android.R.attr.state_checked},
                new int[]{android.R.attr.state_pressed}
        };
        int[] colors = new int[]{
                Color.parseColor("#565656"),
                Color.parseColor("#a2a2a2"),
                Color.WHITE,
                Color.WHITE
        };
        navigationView.setItemTextColor(new ColorStateList(states, colors));
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}

