package ml.melun.mangaview.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import ml.melun.mangaview.R;

public class FastLaunchActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView shell = new TextView(this);
        shell.setText("MangaView");
        shell.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        shell.setPadding(dp(20), 0, dp(20), 0);
        shell.setTextSize(24);
        shell.setTypeface(null, android.graphics.Typeface.BOLD);
        shell.setTextColor(ContextCompat.getColor(this, R.color.appText));
        shell.setBackgroundColor(ContextCompat.getColor(this, R.color.appSurface));
        shell.setOnClickListener(v -> openMain());
        setContentView(shell, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void openMain() {
        if(isFinishing() || isDestroyed())
            return;
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(0, 0);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
