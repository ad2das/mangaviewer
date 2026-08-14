package ml.melun.mangaview.activity;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;

import ml.melun.mangaview.R;
import ml.melun.mangaview.MainApplication;

public class LicenseActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        boolean dark = MainApplication.p != null && MainApplication.p.getDarkTheme();
        if(dark) setTheme(R.style.AppThemeDark_ActionBar);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_license);
        int chromeColor = ContextCompat.getColor(this,
                dark ? R.color.colorDarkWindowBackground : R.color.appSurface);
        getWindow().setStatusBarColor(chromeColor);
        getWindow().setNavigationBarColor(chromeColor);
        getWindow().getDecorView().setSystemUiVisibility(
                dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        ActionBar ab = getSupportActionBar();
        if(ab != null) {
            ab.setTitle("오픈소스 라이선스");
            ab.setDisplayHomeAsUpEnabled(true);
        }
    }
    public boolean onOptionsItemSelected(MenuItem item){
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}

