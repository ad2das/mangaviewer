package ml.melun.mangaview.activity;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;

import ml.melun.mangaview.R;

final class SettingsScreenStyler {
    private static final int[] ROW_IDS = {
            R.id.setting_url,
            R.id.setting_site_toggle,
            R.id.setting_ntk_diagnostics,
            R.id.setting_dir,
            R.id.setting_startTab,
            R.id.setting_dark,
            R.id.setting_viewer,
            R.id.setting_key,
            R.id.setting_reverse,
            R.id.setting_buttonLayout,
            R.id.setting_double,
            R.id.setting_double_leftright,
            R.id.setting_stretch,
            R.id.setting_pageRtl,
            R.id.setting_dataExport,
            R.id.setting_dataImport,
            R.id.setting_reset,
            R.id.setting_dataSave,
            R.id.setting_license
    };

    private SettingsScreenStyler() {
    }

    static void style(SettingsActivity activity, boolean dark) {
        View root = activity.findViewById(android.R.id.content);
        root.setBackgroundColor(ContextCompat.getColor(activity, dark ? R.color.colorDarkWindowBackground : R.color.appSurface));
        styleTree(activity, root, dark);
        for (int id : ROW_IDS)
            styleRow(activity, activity.findViewById(id), dark, id == R.id.setting_reset);
    }

    private static void styleRow(SettingsActivity activity, View row, boolean dark, boolean danger) {
        if(row == null)
            return;
        if(dark)
            row.setBackground(makeSettingsRowBackground(activity, danger));
        else
            row.setBackgroundResource(danger ? R.drawable.app_danger_row_bg : R.drawable.app_setting_row_bg);
        row.setPadding(dp(activity, 12), 0, dp(activity, 12), 0);
        row.setMinimumHeight(dp(activity, 62));
        if(row.getLayoutParams() instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) row.getLayoutParams();
            lp.width = LinearLayout.LayoutParams.MATCH_PARENT;
            lp.height = LinearLayout.LayoutParams.WRAP_CONTENT;
            lp.setMargins(dp(activity, 16), dp(activity, 4), dp(activity, 16), dp(activity, 4));
            row.setLayoutParams(lp);
        }
    }

    private static void styleTree(Context context, View view, boolean dark) {
        if(view instanceof TextView)
            styleText(context, (TextView) view, dark);
        if(view instanceof Spinner) {
            if(dark)
                view.setBackground(makeSettingsRowBackground(context, false));
            else
                view.setBackgroundResource(R.drawable.app_search_filter_bg);
        }
        if(view instanceof Switch)
            styleSwitch(context, (Switch) view, dark);
        if(view instanceof LinearLayout) {
            LinearLayout layout = (LinearLayout) view;
            layout.setBackgroundColor(ContextCompat.getColor(context, dark ? R.color.colorDarkWindowBackground : R.color.appSurface));
        }
        if(view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for(int i = 0; i < group.getChildCount(); i++)
                styleTree(context, group.getChildAt(i), dark);
        }
    }

    private static void styleText(Context context, TextView text, boolean dark) {
        boolean sectionHeader = text.getParent() instanceof LinearLayout
                && !(text.getParent() instanceof ConstraintLayout);
        if(sectionHeader) {
            text.setBackgroundColor(ContextCompat.getColor(context, dark ? R.color.colorDarkWindowBackground : R.color.appSurface));
            text.setTextColor(ContextCompat.getColor(context, R.color.appAccent));
            text.setTextSize(12);
            text.setGravity(Gravity.BOTTOM | Gravity.START);
            text.setAllCaps(false);
            text.setPadding(dp(context, 20), dp(context, 18), dp(context, 20), dp(context, 7));
        } else {
            text.setTextColor(ContextCompat.getColor(context, dark ? R.color.colorDarkText : R.color.appText));
            text.setTextSize(14);
            text.setIncludeFontPadding(false);
            text.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        }
    }

    private static void styleSwitch(Context context, Switch view, boolean dark) {
        int accent = ContextCompat.getColor(context, R.color.appAccent);
        int muted = ContextCompat.getColor(context, dark ? R.color.colorDarkDivider : R.color.appDivider);
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        view.setThumbTintList(new ColorStateList(states,
                new int[]{accent, ContextCompat.getColor(context, dark ? R.color.colorDarkSurface : R.color.appCard)}));
        view.setTrackTintList(new ColorStateList(states,
                new int[]{ContextCompat.getColor(context, R.color.appAccentLight), muted}));
    }

    static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    static GradientDrawable makeSettingsRowBackground(Context context, boolean danger) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(ContextCompat.getColor(context, danger ? R.color.colorDarkSurfaceElevated : R.color.colorDarkSurface));
        background.setStroke(dp(context, 1), ContextCompat.getColor(context, danger ? R.color.appAccent : R.color.colorDarkDivider));
        background.setCornerRadius(dp(context, 8));
        return background;
    }
}

