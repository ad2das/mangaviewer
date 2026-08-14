package ml.melun.mangaview.activity;

import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.json.JSONArray;

import ml.melun.mangaview.ui.NpaLinearLayoutManager;
import ml.melun.mangaview.R;
import ml.melun.mangaview.Utils;
import ml.melun.mangaview.adapter.SelectEpisodeAdapter;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.ui.AppWindowSizePolicy;

import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.queueOfflineDownload;

public class DownloadActivity extends AppCompatActivity {
    Title title;
    SelectEpisodeAdapter adapter;
    RecyclerView eplist;
    boolean dark;
    JSONArray selected;
    boolean singleSelect = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        dark = p.getDarkTheme();
        if(dark) setTheme(R.style.AppThemeDark_ActionBar);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_download);
        setTitle("오프라인 저장");
        eplist = this.findViewById(R.id.dl_eplist);
        Intent intent = getIntent();
        try {
            String titleJson = intent == null ? null : intent.getStringExtra("title");
            title = new Gson().fromJson(titleJson,new TypeToken<Title>(){}.getType());
            if(title != null) {
                java.util.ArrayList<Manga> episodes = Utils.snapshotEpisodes(title);
                title.setEps(episodes);
                eplist.setLayoutManager(new NpaLinearLayoutManager(this));
                eplist.setHasFixedSize(true);
                eplist.setItemViewCacheSize(20);
                eplist.setItemAnimator(null);
                eplist.setOverScrollMode(View.OVER_SCROLL_NEVER);
                adapter = new SelectEpisodeAdapter(this, episodes);
                adapter.setClickListener((view, position) -> adapter.select(position));
                eplist.setAdapter(adapter);
            }
        }catch (Exception e){
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
        Button dl = findViewById(R.id.dl_btn);
        dl.setOnClickListener(v -> {
            if(adapter == null || title == null || Utils.snapshotEpisodes(title).size() == 0) {
                Toast.makeText(getApplication(),"다운로드할 회차 정보를 불러오지 못했습니다", Toast.LENGTH_SHORT).show();
                return;
            }
            if(adapter.getSelected(false).length()>0) {
                selected = adapter.getSelected(false);
                downloadClick();
            }else{
                Toast.makeText(getApplication(),"1개 이상의 화를 선택해 주세요", Toast.LENGTH_SHORT).show();
            }
        });
        Button dlAll = findViewById(R.id.dl_all_btn);
        dlAll.setOnClickListener(v -> {
            if(adapter == null || title == null || Utils.snapshotEpisodes(title).size() == 0) {
                Toast.makeText(getApplication(),"다운로드할 회차 정보를 불러오지 못했습니다", Toast.LENGTH_SHORT).show();
                return;
            }
            selected = adapter.getSelected(true);
            downloadClick();
        });
        ActionBar actionBar = getSupportActionBar();
        if(actionBar != null)
            actionBar.setDisplayHomeAsUpEnabled(true);

        Button selectionMode = findViewById(R.id.dl_mode_btn);
        selectionMode.setOnClickListener(view -> {
            if(adapter == null)
                return;
            if(singleSelect){
                singleSelect = false;
                selectionMode.setText("범위 선택 모드");
                adapter.setSelectionMode(singleSelect);
            }else{
                singleSelect = true;
                selectionMode.setText("단일 선택 모드");
                adapter.setSelectionMode(singleSelect);
            }
        });
        if(dark)
            applyDarkChrome(dl, dlAll, selectionMode);
        View contentRoot = findViewById(android.R.id.content);
        if(contentRoot != null) {
            contentRoot.addOnLayoutChangeListener((v, left, top, right, bottom,
                                                    oldLeft, oldTop, oldRight, oldBottom) ->
                    applyCompactWindowChrome(bottom - top, dl, dlAll, selectionMode));
            contentRoot.post(() -> applyCompactWindowChrome(
                    contentRoot.getHeight(), dl, dlAll, selectionMode));
        }
    }
    public boolean onOptionsItemSelected(MenuItem item){
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }


    private void downloadClick(){
        //download manga
        //ask for confirmation
        DialogInterface.OnClickListener dialogClickListener = (dialog, which) -> {
            switch (which){
                case DialogInterface.BUTTON_POSITIVE:
                    if(queueOfflineDownload(this, title, selected))
                        finish();
                    break;
                case DialogInterface.BUTTON_NEGATIVE:
                    //No button clicked
                    break;
            }
        };
        AlertDialog.Builder builder;
        if(dark) builder = new AlertDialog.Builder(this,R.style.darkDialog);
        else builder = new AlertDialog.Builder(this);
        builder.setMessage(title.getName()+ " 을(를) 다운로드 하시겠습니까?\n[ 총 "+selected.length()+"화 ]").setPositiveButton("네", dialogClickListener)
                .setNegativeButton("아니오", dialogClickListener).show();
    }

    private void applyDarkChrome(Button downloadSelected, Button downloadAll, Button selectionMode) {
        int window = ContextCompat.getColor(this, R.color.colorDarkWindowBackground);
        int surface = ContextCompat.getColor(this, R.color.colorDarkSurface);
        View root = findViewById(android.R.id.content);
        View controls = findViewById(R.id.dl_buttonContainer);
        if(root != null)
            root.setBackgroundColor(window);
        if(eplist != null)
            eplist.setBackgroundColor(window);
        if(controls != null)
            controls.setBackgroundColor(surface);
        getWindow().setStatusBarColor(surface);
        getWindow().setNavigationBarColor(window);
        styleAccentButton(downloadSelected);
        styleSecondaryButton(downloadAll);
        styleSecondaryButton(selectionMode);
    }

    private void applyCompactWindowChrome(int heightPixels, Button... buttons) {
        boolean compact = AppWindowSizePolicy.isCompactHeight(
                heightPixels, getResources().getDisplayMetrics().density);
        setViewHeight(findViewById(R.id.dl_buttonContainer), compact ? 64 : 88);
        for(Button button : buttons)
            setViewHeight(button, compact ? 48 : 52);
        if(eplist != null) {
            eplist.setPadding(eplist.getPaddingLeft(), dp(compact ? 4 : 10),
                    eplist.getPaddingRight(), dp(compact ? 6 : 14));
        }
    }

    private void setViewHeight(View view, int heightDp) {
        if(view == null)
            return;
        ViewGroup.LayoutParams params = view.getLayoutParams();
        int expected = dp(heightDp);
        if(params.height != expected) {
            params.height = expected;
            view.setLayoutParams(params);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void styleAccentButton(Button button) {
        if(button == null)
            return;
        button.setBackgroundTintList(null);
        button.setBackground(roundedBackground(R.color.appAccent, R.color.appAccent, 10));
        button.setTextColor(ContextCompat.getColor(this, android.R.color.white));
    }

    private void styleSecondaryButton(Button button) {
        if(button == null)
            return;
        button.setBackgroundTintList(null);
        button.setBackground(roundedBackground(R.color.colorDarkSurfaceElevated, R.color.colorDarkDivider, 10));
        button.setTextColor(ContextCompat.getColor(this, R.color.colorDarkText));
    }

    private GradientDrawable roundedBackground(int fillColorRes, int strokeColorRes, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        float density = getResources().getDisplayMetrics().density;
        drawable.setColor(ContextCompat.getColor(this, fillColorRes));
        drawable.setCornerRadius(density * radiusDp);
        drawable.setStroke(Math.max(1, Math.round(density)), ContextCompat.getColor(this, strokeColorRes));
        return drawable;
    }
}

