package ml.melun.mangaview.activity;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import ml.melun.mangaview.task.LifecycleTask;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.os.Build;
import android.os.Bundle;

import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import ml.melun.mangaview.ui.NpaLinearLayoutManager;
import ml.melun.mangaview.R;
import ml.melun.mangaview.adapter.EpisodeAdapter;
import ml.melun.mangaview.adapter.TagAdapter;
import ml.melun.mangaview.glide.ViewerWarmupManager;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;

import static ml.melun.mangaview.MainApplication.getHttpClient;
import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.deleteRecursive;
import static ml.melun.mangaview.Utils.documentFileFromUri;
import static ml.melun.mangaview.Utils.getOfflineEpisodes;
import static ml.melun.mangaview.Utils.queueOfflineDownload;
import static ml.melun.mangaview.Utils.showCaptchaPopup;
import static ml.melun.mangaview.Utils.showTokiCaptchaPopup;
import static ml.melun.mangaview.Utils.useScopedStorageHome;
import static ml.melun.mangaview.activity.CaptchaActivity.RESULT_CAPTCHA;
import static ml.melun.mangaview.mangaview.Title.LOAD_CAPTCHA;


public class EpisodeActivity extends AppCompatActivity {
    //global variables
    Title title;
    EpisodeAdapter episodeAdapter;
    Context context = this;
    RecyclerView episodeList;
    boolean favoriteResult = false;
    boolean recentResult = false;
    int position;
    int bookmarkId = -1;
    int bookmarkIndex = -1;
    List<Manga> episodes;
    boolean dark, online=true;
    Intent viewer;
    ActionBar actionBar;
    String homeDir;
    int mode = 0;
    FloatingActionButton resumefab;
    ProgressBar progress;
    boolean loaded = false;
    LinearLayoutCompat fab_container;
    getEpisodes episodeTask;


    public boolean onOptionsItemSelected(MenuItem item){
        switch (item.getItemId()) {
            case android.R.id.home:
                finish();
                return true;
            case R.id.episode_favorite:
                toggleFavorite();
                return true;
            case R.id.episode_download:
                Intent download = new Intent(context, DownloadActivity.class);
                download.putExtra("title", new Gson().toJson(title));
                startActivity(download);
                return true;
            case R.id.episode_more:
                showMoreMenu(findViewById(R.id.episode_more));
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showMoreMenu(View anchor) {
        if(anchor == null || title == null)
            return;
        PopupMenu popup = new PopupMenu(context, anchor);
        popup.getMenu().add(0, 1, 0, "브라우저에서 열기");
        popup.getMenu().add(0, 2, 1, "공유");
        popup.getMenu().add(0, 3, 2, "오프라인 저장");
        popup.setOnMenuItemClickListener(menuItem -> {
            String url = getTitleWebUrl();
            switch (menuItem.getItemId()) {
                case 1:
                    if(url.length() == 0) {
                        Toast.makeText(context, "열 수 있는 주소가 없습니다", Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    return true;
                case 2:
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("text/plain");
                    share.putExtra(Intent.EXTRA_SUBJECT, title.getName());
                    share.putExtra(Intent.EXTRA_TEXT, title.getName() + (url.length() > 0 ? "\n" + url : ""));
                    startActivity(Intent.createChooser(share, "공유"));
                    return true;
                case 3:
                    Intent download = new Intent(context, DownloadActivity.class);
                    download.putExtra("title", new Gson().toJson(title));
                    startActivity(download);
                    return true;
            }
            return false;
        });
        popup.show();
    }

    private String getTitleWebUrl() {
        try {
            String path = title == null ? "" : title.getUrl();
            if(path == null || path.length() == 0)
                return "";
            if(path.startsWith("http://") || path.startsWith("https://"))
                return path;
            return getHttpClient().getUrl(path);
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(resultCode== RESULT_OK){
            int newid = data.getIntExtra("id", -1);
            if(newid>0 && newid!=bookmarkId){
                bookmarkId = newid;
                //find index of bookmark;
                if(episodes != null)
                    for(int i=0; i< episodes.size(); i++){
                            if(episodes.get(i).getId()==bookmarkId){
                                bookmarkIndex = i+1;
                                if(episodeAdapter != null)
                                    episodeAdapter.setBookmark(bookmarkIndex);
                                break;
                            }
                    }
            }
            if(bookmarkId>-1)
                resumefab.show();
            else
                resumefab.hide();
        }else if(resultCode == RESULT_CAPTCHA){
            //captcha Checked
            finish();
            startActivity(getIntent());
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        dark = p.getDarkTheme();
        if(dark) setTheme(R.style.AppThemeDarkNoTitle);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_episode);
        applyEpisodeWindowChrome();
        Intent intent = getIntent();
        title = new Gson().fromJson(intent.getStringExtra("title"),new TypeToken<Title>(){}.getType());
        online = intent.getBooleanExtra("online", true);
        if(title.useBookmark())
            bookmarkId = restoredBookmarkId(title);
        position = intent.getIntExtra("position",0);
        favoriteResult = intent.getBooleanExtra("favorite",false);
        recentResult = intent.getBooleanExtra("recent",false);
        episodeList = this.findViewById(R.id.EpisodeList);
        progress = this.findViewById(R.id.progress);
        episodeList.setLayoutManager(new NpaLinearLayoutManager(this));
        episodeList.setHasFixedSize(true);
        episodeList.setItemViewCacheSize(20);
        episodeList.setItemAnimator(null);
        episodeList.setOverScrollMode(View.OVER_SCROLL_NEVER);
        homeDir = p.getHomeDir();
        resumefab = this.findViewById(R.id.resumefab);
        fab_container = findViewById(R.id.fab_container);

        if(episodeList.getItemAnimator() instanceof SimpleItemAnimator)
            ((SimpleItemAnimator) episodeList.getItemAnimator()).setSupportsChangeAnimations(false);
        if(recentResult){
            Intent resultIntent = new Intent();
            setResult(RESULT_OK,resultIntent);
        }


        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        actionBar = getSupportActionBar();
        if(actionBar!=null){
            actionBar.setTitle("");
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        if(online) {
            mode = 0;
            fab_container.setVisibility(View.GONE);
            episodeTask = new getEpisodes();
            episodeTask.executeOnExecutor(LifecycleTask.THREAD_POOL_EXECUTOR);
        }else{
            //offline title
            //initialize eps list
            episodes = new ArrayList<>();

            //get child folder list of title dir
            if (useScopedStorageHome(title.getPath())) {
                //scoped storage
                DocumentFile titleDir = documentFileFromUri(context, title.getPath());
                DocumentFile data = titleDir == null ? null : titleDir.findFile("title.gson");
                if(data!=null){
                    mode = 3;
                    if (!title.useBookmark()) {
                        // is migrated
                        mode = 4;
                    }

                    episodes = title.getEps();
                    if(episodes == null)
                        episodes = new ArrayList<>();
                    for(DocumentFile f : getOfflineEpisodes(titleDir)){
                        String name = f.getName();
                        try {
                            int index = episodes.indexOf(new Manga(Integer.parseInt(name.substring(name.lastIndexOf('.') + 1)), "", "", title.getBaseMode()));
                            if (index > -1) {
                                episodes.get(index).setOfflinePath(f.getUri().toString());
                                episodes.get(index).setMode(mode);
                            }
                        } catch (Exception e) {
                            // folder name is not properly formatted
                        }
                    }
                    //for loop to remove non-existing episodes
                    if (episodes != null)
                        for (int i = episodes.size() - 1; i >= 0; i--) {
                            if (episodes.get(i).getOfflinePath() == null) episodes.remove(i);
                        }

                }else{
                    mode = 1;
                    if(titleDir != null) {
                        for(DocumentFile f : getOfflineEpisodes(titleDir)){
                            Manga m = new Manga(-1, f.getName(), "", title.getBaseMode());
                            m.setMode(mode);
                            m.setOfflinePath(f.getUri().toString());
                            episodes.add(m);
                        }
                    }
                }
            }else {

                //read ids and folder names
                File titleDir = new File(title.getPath());
                File data = new File(titleDir, "title.gson");
                if (data.exists()) {
                    mode = 3;

                    if (!title.useBookmark()) {
                        // is migrated
                        mode = 4;
                    }

                    episodes = title.getEps();
                    if(episodes == null)
                        episodes = new ArrayList<>();
                    for (File folder : getOfflineEpisodes(title.getPath())) {
                        //get id from listContent
                        String name = folder.getName();
                        try {
                            int index = episodes.indexOf(new Manga(Integer.parseInt(name.substring(name.lastIndexOf('.') + 1)), "", "", title.getBaseMode()));
                            if (index > -1) {
                                episodes.get(index).setOfflinePath(folder.getAbsolutePath());
                                episodes.get(index).setMode(mode);
                            }
                        } catch (Exception e) {
                            // folder name is not properly formatted
                        }
                    }
                    //for loop to remove non-existing episodes
                    if (episodes != null)
                        for (int i = episodes.size() - 1; i >= 0; i--) {
                            if (episodes.get(i).getOfflinePath() == null) episodes.remove(i);
                        }

                } else {
                    mode = 1;
                    for (File f : getOfflineEpisodes(title.getPath())) {
                        Manga manga;
                        manga = new Manga(-1, f.getName(), "", title.getBaseMode());
                        manga.setMode(mode);
                        manga.setOfflinePath(f.getAbsolutePath());
                        //add local images to manga
                        episodes.add(manga);
                        // set eps to title object
                        title.setEps(episodes);
                    }
                }
            }
            //set up adapter
            episodeAdapter = new EpisodeAdapter(context, episodes, title, mode);
            afterLoad();
        }
    }

//    @Override
//    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
//        super.onActivityResult(requestCode, resultCode, data);
//        if(requestCode==0){
//            if(bookmarkId != p.getBookmark()){
//                bookmarkId = p.getBookmark();
//                episodeAdapter.setBookmark(bookmarkId);
//            }
//        }
//    }

    public void afterLoad(){
        if(fab_container != null)
            fab_container.setVisibility(View.GONE);
        //find bookmark
        actionBar = getSupportActionBar();
        if(actionBar!=null){
            actionBar.setTitle("");
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
        if(bookmarkId>-1){
            if(episodes != null)
                for(int i=0; i< episodes.size(); i++){
                    if(episodes.get(i).getId()==bookmarkId){
                        bookmarkIndex=i+1;
                        episodeAdapter.setBookmark(bookmarkIndex);
                        break;
                    }
                }
        }
        if(bookmarkIndex < 0 && title.getBookmarkEpisodeIndex() > 0 && episodes != null
                && title.getBookmarkEpisodeIndex() <= episodes.size()) {
            bookmarkIndex = title.getBookmarkEpisodeIndex();
            bookmarkId = episodes.get(bookmarkIndex - 1).getId();
            episodeAdapter.setBookmark(bookmarkIndex);
        }
        episodeAdapter.setFavorite(p.findFavorite(title)>-1);
        episodeList.setAdapter(episodeAdapter);
        if(bookmarkIndex>8) {
            episodeList.scrollToPosition(bookmarkIndex);
        }
        findViewById(R.id.upfab).setOnClickListener(v -> episodeList.scrollToPosition(0));
        findViewById(R.id.downfab).setOnClickListener(v -> {
            episodeList.scrollToPosition(episodes.size()); //헤더가 0이기 때문
        });
        if(bookmarkIndex>-1)
            resumefab.show();
        else
            resumefab.hide();
        resumefab.setOnClickListener(v -> {
            if(episodes == null || bookmarkIndex <= 0 || bookmarkIndex > episodes.size())
                return;
            openViewer(episodes.get(bookmarkIndex - 1),0);
        });

        episodeAdapter.setClickListener(new EpisodeAdapter.ItemClickListener() {

            @Override
            public void onItemClick(int position, Manga selected) {
                //add local images to manga
                openViewer(selected,0);
            }
            @Override
            public void onStarClick(){
                toggleFavorite();
            }

            @Override
            public void onAuthorClick() {
                if(title.getAuthor().length()>0){
                    Intent i = new Intent(context, TagSearchActivity.class);
                    i.putExtra("query",title.getAuthor());
                    i.putExtra("mode",1);
                    startActivity(i);
                }
            }

            @Override
            public void onEpisodeTabClick() {
                if(episodeList != null && episodeAdapter != null && episodeAdapter.getItemCount() > 1)
                    episodeList.smoothScrollToPosition(1);
            }

            @Override
            public void onDownloadClick(int position, Manga m) {
                if(!online) {
                    confirmDeleteOfflineEpisode(position, m);
                    return;
                }
                if(m != null)
                    m.setTitle(title);
                queueOfflineDownload(context, title, m);
            }

            @Override
            public void onFirstClick(){
                if(episodes != null && episodes.size()>0)
                    openViewer(episodes.get(episodes.size()-1),0);
            }
        });
        episodeAdapter.setTagClickListener(tag -> {
            Intent i = new Intent(context, TagSearchActivity.class);
            i.putExtra("query",tag);
            i.putExtra("mode",2);
            startActivity(i);
        });
        warmupInitialViewerTargets();
    }

    private void warmupInitialViewerTargets() {
        if(!online || episodes == null || episodes.size() == 0)
            return;
        if(bookmarkIndex > 0 && bookmarkIndex <= episodes.size())
            warmupEpisode(episodes.get(bookmarkIndex - 1));
        int limit = p.getDataSave() ? 2 : 4;
        for(int i = 0; i < episodes.size() && i < limit; i++)
            warmupEpisode(episodes.get(i));
    }

    private void warmupEpisode(Manga manga) {
        if(manga == null)
            return;
        manga.setMode(mode);
        manga.setTitle(title);
        manga.setTitleId(title == null ? manga.getTitleId() : title.getId());
        ViewerWarmupManager.warmup(context, manga, title);
    }

    private void confirmDeleteOfflineEpisode(int position, Manga manga) {
        if(online || manga == null || manga.getOfflinePath() == null || manga.getOfflinePath().length() == 0)
            return;
        DialogInterface.OnClickListener listener = (dialog, which) -> {
            if(which == DialogInterface.BUTTON_POSITIVE)
                deleteOfflineEpisode(position, manga);
        };
        AlertDialog.Builder builder = dark
                ? new AlertDialog.Builder(context, R.style.darkDialog)
                : new AlertDialog.Builder(context);
        builder.setMessage(manga.getName() + " 을(를) 저장됨에서 삭제하시겠습니까?")
                .setPositiveButton("네", listener)
                .setNegativeButton("아니오", listener)
                .show();
    }

    private void deleteOfflineEpisode(int position, Manga manga) {
        boolean deleted = false;
        String path = manga.getOfflinePath();
        if(useScopedStorageHome(path)) {
            try {
                DocumentFile target = documentFileFromUri(context, path);
                deleted = target != null && target.delete();
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            deleted = deleteRecursive(new File(path));
        }
        if(!deleted) {
            Toast.makeText(context, "삭제를 실패했습니다.", Toast.LENGTH_SHORT).show();
            return;
        }

        p.removeViewerBookmark(manga);
        if(position >= 0 && episodes != null && position < episodes.size()) {
            if(episodes.get(position) == manga)
                episodeAdapter.removeEpisode(position);
            else {
                episodes.remove(manga);
                episodeAdapter.notifyDataSetChanged();
            }
        }
        Toast.makeText(context, "삭제가 완료되었습니다.", Toast.LENGTH_SHORT).show();
        if(episodes == null || episodes.size() == 0) {
            deleteEmptyOfflineTitle();
            finish();
        }
    }

    private void deleteEmptyOfflineTitle() {
        if(title == null || title.getPath() == null || title.getPath().length() == 0)
            return;
        try {
            if(useScopedStorageHome(title.getPath())) {
                DocumentFile titleDir = documentFileFromUri(context, title.getPath());
                if(titleDir != null)
                    titleDir.delete();
            } else {
                deleteRecursive(new File(title.getPath()));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private int restoredBookmarkId(Title title) {
        if(title == null)
            return -1;
        int bookmark = p.getBookmark(title);
        if(bookmark > 0)
            return bookmark;
        if(title.getBookmark() > 0)
            return title.getBookmark();
        if(title.getBookmarkEpisodeId() > 0)
            return title.getBookmarkEpisodeId();
        return -1;
    }

    private class getEpisodes extends LifecycleTask<Void,Void,Integer> {
        protected void onPreExecute() {
            super.onPreExecute();
            progress.setVisibility(View.VISIBLE);
        }

        protected Integer doInBackground(Void... params) {
            int code = title.fetchEps(getHttpClient());
            episodes = title.getEps();
            return code;
        }

        @Override
        protected void onPostExecute(Integer res) {
            super.onPostExecute(res);
            if(episodeTask != this)
                return;
            episodeTask = null;
            if(res == LOAD_CAPTCHA){
                //캡차 처리 팝업
                showTokiCaptchaPopup(context, p);
                return;
            }else if(episodes == null || episodes.size()==0){
                showCaptchaPopup(title.getUrl(), context, p);
                return;
            }else {
                episodeAdapter = new EpisodeAdapter(context, episodes, title, mode);
                afterLoad();
                progress.setVisibility(View.GONE);
                loaded = true;
                fab_container.setVisibility(View.GONE);
                invalidateOptionsMenu();
            }
        }

        @Override
        protected void onCancelled(Integer res) {
            super.onCancelled(res);
            if(episodeTask == this)
                episodeTask = null;
            if(progress != null)
                progress.setVisibility(View.GONE);
        }
    }

    public void openViewer(Manga manga, int code){
        manga.setMode(mode);
        manga.setTitle(title);
        manga.setTitleId(title == null ? manga.getTitleId() : title.getId());
        ViewerWarmupManager.warmup(context, manga, title);
        Intent viewer = null;
        switch (p.getViewerType()){
            case 0:
                viewer = new Intent(context, ViewerActivity.class);
                break;
            case 2:
                viewer = new Intent(context, ViewerActivity3.class);
                break;
            case 1:
                viewer = new Intent(context, ViewerActivity2.class);
                break;
        }
        viewer.putExtra("manga", new Gson().toJson(manga));
        viewer.putExtra("title", new Gson().toJson(title));
        viewer.putExtra("recent",true);
        startActivityForResult(viewer, code);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        MenuInflater inflater = getMenuInflater();
        if(loaded)
            inflater.inflate(R.menu.episode_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean result = super.onPrepareOptionsMenu(menu);
        MenuItem favorite = menu.findItem(R.id.episode_favorite);
        if(favorite != null)
            favorite.setIcon(p.findFavorite(title) > -1 ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
        return result;
    }

    private void toggleFavorite() {
        if(title == null)
            return;
        boolean favorite = p.toggleFavorite(title, position);
        if(episodeAdapter != null)
            episodeAdapter.setFavorite(favorite);
        invalidateOptionsMenu();
        if(favoriteResult){
            Intent resultIntent = new Intent();
            resultIntent.putExtra("favorite", favorite);
            setResult(RESULT_OK, resultIntent);
        }
    }

    @Override
    protected void onDestroy() {
        if(episodeTask != null) {
            episodeTask.cancel(true);
            episodeTask = null;
        }
        super.onDestroy();
    }

    private void applyEpisodeWindowChrome() {
        if(dark)
            return;
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.appSurface));
            getWindow().setNavigationBarColor(ContextCompat.getColor(this, R.color.appSurface));
        }
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

}
