package ml.melun.mangaview.activity;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import ml.melun.mangaview.task.LifecycleTask;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

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
import android.widget.ProgressBar;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ml.melun.mangaview.ui.NpaLinearLayoutManager;
import ml.melun.mangaview.R;
import ml.melun.mangaview.adapter.EpisodeAdapter;
import ml.melun.mangaview.adapter.TagAdapter;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;

import static ml.melun.mangaview.MainApplication.httpClient;
import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.CODE_SCOPED_STORAGE;
import static ml.melun.mangaview.Utils.getOfflineEpisodes;
import static ml.melun.mangaview.Utils.showErrorPopup;


public class EpisodeActivity extends AppCompatActivity {
    private static final long EPISODE_CACHE_TTL_MS = 10 * 60 * 1000L;
    private static final int EPISODE_CACHE_LIMIT = 24;
    private static final ExecutorService EPISODE_PREFETCH_EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Map<String, CachedEpisodes> EPISODE_CACHE =
            new LinkedHashMap<String, CachedEpisodes>(EPISODE_CACHE_LIMIT, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedEpisodes> eldest) {
                    return size() > EPISODE_CACHE_LIMIT;
                }
            };
    private static final Set<String> EPISODE_PREFETCHING = new HashSet<>();

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
    boolean hasCachedEpisodes = false;


    public boolean onOptionsItemSelected(MenuItem item){
        int itemId = item.getItemId();
        if(itemId == android.R.id.home) {
            finish();
            return true;
        } else if(itemId == R.id.episode_download) {
            Intent download = new Intent(context, DownloadActivity.class);
            download.putExtra("title", new Gson().toJson(title));
            startActivity(download);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(resultCode== RESULT_OK && data != null){
            int newid = data.getIntExtra("id", -1);
            if(newid>0 && newid!=bookmarkId){
                bookmarkId = newid;
                bookmarkIndex = -1;
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
            if(canResumeBookmark())
                resumefab.show();
            else
                resumefab.hide();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        dark = p.getDarkTheme();
        if(dark) setTheme(R.style.AppThemeDarkNoTitle);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_episode);
        Intent intent = getIntent();
        title = new Gson().fromJson(intent.getStringExtra("title"),new TypeToken<Title>(){}.getType());
        if(title == null) {
            finish();
            return;
        }
        online = intent.getBooleanExtra("online", true);
        if(title.useBookmark())
            bookmarkId = p.getBookmark(title);
        position = intent.getIntExtra("position",0);
        favoriteResult = intent.getBooleanExtra("favorite",false);
        recentResult = intent.getBooleanExtra("recent",false);
        episodeList = this.findViewById(R.id.EpisodeList);
        progress = this.findViewById(R.id.progress);
        episodeList.setLayoutManager(new NpaLinearLayoutManager(this));
        episodeList.setHasFixedSize(true);
        episodeList.setItemViewCacheSize(10);
        homeDir = p.getHomeDir();
        resumefab = this.findViewById(R.id.resumefab);
        fab_container = findViewById(R.id.fab_container);

        ((SimpleItemAnimator) episodeList.getItemAnimator()).setSupportsChangeAnimations(false);
        if(recentResult){
            Intent resultIntent = new Intent();
            setResult(RESULT_OK,resultIntent);
        }


        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        actionBar = getSupportActionBar();
        if(actionBar!=null){
            actionBar.setTitle(title.getName());
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        if(online) {
            mode = 0;
            fab_container.setVisibility(View.GONE);
            CachedEpisodes cached = getCachedEpisodesFromCache(title);
            if(cached != null) {
                hasCachedEpisodes = true;
                applyCachedTitle(cached.title);
                episodes = cloneEpisodesForTitle(title, cached.episodes);
                title.setEps(episodes);
                episodeAdapter = new EpisodeAdapter(context, episodes, title, mode);
                afterLoad();
                progress.setVisibility(View.GONE);
                loaded = true;
                fab_container.setVisibility(View.VISIBLE);
                invalidateOptionsMenu();
            } else {
                episodes = new ArrayList<>();
                episodeAdapter = new EpisodeAdapter(context, episodes, title, mode);
                afterLoad();
            }
            episodeTask = new getEpisodes();
            episodeTask.executeOnExecutor(LifecycleTask.THREAD_POOL_EXECUTOR);
        }else{
            //offline title
            //initialize eps list
            episodes = new ArrayList<>();

            //get child folder list of title dir
            if(title.getPath() == null || title.getPath().length() == 0) {
                finish();
                return;
            }
            if (Build.VERSION.SDK_INT >= CODE_SCOPED_STORAGE) {
                //scoped storage
                DocumentFile titleDir = DocumentFile.fromTreeUri(context, Uri.parse(title.getPath()));
                if(titleDir == null) {
                    finish();
                    return;
                }
                DocumentFile data = titleDir.findFile("title.gson");
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
                    for(DocumentFile f : getOfflineEpisodes(titleDir)){
                        Manga m = new Manga(-1, f.getName(), "", title.getBaseMode());
                        m.setMode(mode);
                        m.setOfflinePath(f.toString());
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
        //find bookmark
        actionBar = getSupportActionBar();
        if(actionBar!=null){
            actionBar.setTitle(title.getName());
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
        if(bookmarkId>-1){
            bookmarkIndex = -1;
            if(episodes != null)
                for(int i=0; i< episodes.size(); i++){
                    if(episodes.get(i).getId()==bookmarkId){
                        bookmarkIndex=i+1;
                        episodeAdapter.setBookmark(bookmarkIndex);
                        break;
                    }
                }
        }
        episodeAdapter.setFavorite(p.findFavorite(title)>-1);
        if(episodeList.getAdapter() != episodeAdapter)
            episodeList.setAdapter(episodeAdapter);
        if(canResumeBookmark() && bookmarkIndex>8) {
            episodeList.scrollToPosition(bookmarkIndex);
        }
        findViewById(R.id.upfab).setOnClickListener(v -> episodeList.scrollToPosition(0));
        findViewById(R.id.downfab).setOnClickListener(v -> {
            if(episodes != null)
                episodeList.scrollToPosition(episodes.size()); //헤더가 0이기 때문
        });
        if(canResumeBookmark())
            resumefab.show();
        else
            resumefab.hide();
        resumefab.setOnClickListener(v -> {
            if(canResumeBookmark())
                openViewer(episodes.get(bookmarkIndex-1),0);
            else
                resumefab.hide();
        });

        episodeAdapter.setClickListener(new EpisodeAdapter.ItemClickListener() {

            @Override
            public void onItemClick(int position, Manga selected) {
                //add local images to manga
                if(selected != null)
                    openViewer(selected,0);
            }
            @Override
            public void onStarClick(){
                //star click handler
                episodeAdapter.setFavorite(p.toggleFavorite(title, position));
                if(favoriteResult){
                    Intent resultIntent = new Intent();
                    resultIntent.putExtra("favorite", p.findFavorite(title)>-1);
                    setResult(RESULT_OK, resultIntent);
                }
            }

            @Override
            public void onAuthorClick() {
                if(title.getAuthor() != null && title.getAuthor().length()>0){
                    Intent i = new Intent(context, TagSearchActivity.class);
                    i.putExtra("query",title.getAuthor());
                    i.putExtra("mode",1);
                    startActivity(i);
                }
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
    }

    private boolean canResumeBookmark() {
        return episodes != null
                && bookmarkIndex > 0
                && bookmarkIndex <= episodes.size();
    }

    private void updateLoadedEpisodes() {
        if(episodeAdapter == null) {
            episodeAdapter = new EpisodeAdapter(context, episodes, title, mode);
            afterLoad();
        } else {
            episodeAdapter.setData(episodes, title);
            afterLoad();
        }
        progress.setVisibility(View.GONE);
        loaded = true;
        fab_container.setVisibility(View.VISIBLE);
        invalidateOptionsMenu();
    }

    public static void prefetchTitleDetails(Title source) {
        if(source == null || source.getId() <= 0 || getCachedEpisodesFromCache(source) != null)
            return;
        String key = episodeCacheKey(source);
        synchronized (EPISODE_CACHE) {
            if(EPISODE_PREFETCHING.contains(key))
                return;
            EPISODE_PREFETCHING.add(key);
        }
        EPISODE_PREFETCH_EXECUTOR.execute(() -> {
            try {
                Title prefetchTitle = source.clone();
                int result = prefetchTitle.fetchEps(httpClient);
                List<Manga> prefetchEpisodes = prefetchTitle.getEps();
                if(prefetchEpisodes != null && prefetchEpisodes.size() > 0)
                    putCachedEpisodesInCache(prefetchTitle, prefetchEpisodes);
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                synchronized (EPISODE_CACHE) {
                    EPISODE_PREFETCHING.remove(key);
                }
            }
        });
    }

    private static String episodeCacheKey(Title target) {
        if(target == null)
            return "";
        return target.getBaseMode() + ":" + target.getId();
    }

    private static CachedEpisodes getCachedEpisodesFromCache(Title target) {
        String key = episodeCacheKey(target);
        if(key.length() == 0)
            return null;
        synchronized (EPISODE_CACHE) {
            CachedEpisodes cached = EPISODE_CACHE.get(key);
            if(cached == null)
                return null;
            if(System.currentTimeMillis() - cached.createdAt > EPISODE_CACHE_TTL_MS) {
                EPISODE_CACHE.remove(key);
                return null;
            }
            return cached;
        }
    }

    private static void putCachedEpisodesInCache(Title target, List<Manga> data) {
        if(target == null || data == null || data.size() == 0)
            return;
        synchronized (EPISODE_CACHE) {
            EPISODE_CACHE.put(episodeCacheKey(target), new CachedEpisodes(target.clone(), cloneEpisodesForTitle(target, data)));
        }
    }

    private void applyCachedTitle(Title cachedTitle) {
        if(cachedTitle == null)
            return;
        title = cachedTitle;
    }

    private static List<Manga> cloneEpisodesForTitle(Title owner, List<Manga> source) {
        ArrayList<Manga> copy = new ArrayList<>();
        if(source == null)
            return copy;
        for(Manga manga : source) {
            if(manga == null)
                continue;
            Manga cloned = new Manga(manga.getId(), manga.getName(), manga.getDate(), manga.getBaseMode());
            cloned.setMode(manga.getMode());
            cloned.setTitle(owner);
            cloned.setTitleId(manga.getTitleId());
            cloned.setOfflinePath(manga.getOfflinePath());
            copy.add(cloned);
        }
        return copy;
    }

    private class getEpisodes extends LifecycleTask<Void,Void,Integer> {
        getEpisodes() {
            super(EpisodeActivity.this);
        }

        protected void onPreExecute() {
            super.onPreExecute();
            if(progress != null && !hasCachedEpisodes)
                progress.setVisibility(View.VISIBLE);
        }

        protected Integer doInBackground(Void... params) {
            if(title == null)
                return 1;
            int code = title.fetchEps(httpClient);
            episodes = title.getEps();
            return code;
        }

        @Override
        protected void onPostExecute(Integer res) {
            super.onPostExecute(res);
            if(episodeTask != this)
                return;
            episodeTask = null;
            if(episodes == null || episodes.size()==0){
                if(hasCachedEpisodes)
                    return;
                showErrorPopup(context, "회차 정보를 불러오지 못했습니다.", null, true);
                return;
            }else {
                putCachedEpisodesInCache(title, episodes);
                updateLoadedEpisodes();
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

    private static class CachedEpisodes {
        final Title title;
        final List<Manga> episodes;
        final long createdAt;

        CachedEpisodes(Title title, List<Manga> episodes) {
            this.title = title;
            this.episodes = episodes;
            this.createdAt = System.currentTimeMillis();
        }
    }

    public void openViewer(Manga manga, int code){
        if(manga == null)
            return;
        manga.setMode(mode);
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
        if(viewer == null)
            viewer = new Intent(context, ViewerActivity.class);
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
    protected void onDestroy() {
        if(episodeTask != null) {
            episodeTask.cancel(true);
            episodeTask = null;
        }
        super.onDestroy();
    }

}
