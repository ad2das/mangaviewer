package ml.melun.mangaview.activity;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.os.Build;
import android.os.Bundle;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.RecyclerView;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.omadahealth.github.swipyrefreshlayout.library.SwipyRefreshLayout;
import com.omadahealth.github.swipyrefreshlayout.library.SwipyRefreshLayoutDirection;

import java.util.ArrayList;

import ml.melun.mangaview.ui.NpaLinearLayoutManager;
import ml.melun.mangaview.ui.StableScrollbarRecyclerView;
import ml.melun.mangaview.R;
import ml.melun.mangaview.Utils;
import ml.melun.mangaview.adapter.TitleAdapter;
import ml.melun.mangaview.adapter.UpdatedAdapter;
import ml.melun.mangaview.mangaview.Bookmark;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Search;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.mangaview.UpdatedList;
import ml.melun.mangaview.mangaview.UpdatedManga;
import ml.melun.mangaview.repository.MangaRepository;
import ml.melun.mangaview.runtime.AppDispatchers;

import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.episodeIntent;
import static ml.melun.mangaview.Utils.openViewerPrepared;
import static ml.melun.mangaview.Utils.showCaptchaPopup;
import static ml.melun.mangaview.Utils.viewerIntent;
import static ml.melun.mangaview.activity.CaptchaActivity.RESULT_CAPTCHA;
import static ml.melun.mangaview.mangaview.MTitle.base_comic;

public class TagSearchActivity extends AppCompatActivity {
    private static final int THUMBNAIL_PRELOAD_AHEAD = 18;
    private static final int THUMBNAIL_PRELOAD_DELAY_MS = 80;
    private static final int LOAD_MORE_THRESHOLD = 18;
    RecyclerView searchResult;
    int mode;
    String query;
    TitleAdapter adapter;
    UpdatedAdapter uadapter;
    Context context;
    Search search;
    UpdatedList updated;
    TextView noresult;
    TextView resultMetaTitle;
    TextView resultMetaHint;
    String resultLabel;
    SwipyRefreshLayout swipe;
    Bookmark bookmark;
    int baseMode;
    LoadOperation loadTask;
    boolean destroyed = false;
    Runnable thumbnailPreloadRunnable;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if(p.getDarkTheme()) setTheme(R.style.AppThemeDark);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tag_search);
        if(!p.getDarkTheme()) {
            getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.appSurface));
            getWindow().setNavigationBarColor(ContextCompat.getColor(this, android.R.color.white));
            int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
        context = this;
        Toolbar toolbar = this.findViewById(R.id.tagSearchToolbar);
        setSupportActionBar(toolbar);
        searchResult = this.findViewById(R.id.tagSearchResult);
        noresult = this.findViewById(R.id.tagSearchNoResult);
        resultMetaTitle = this.findViewById(R.id.tagSearchMetaTitle);
        resultMetaHint = this.findViewById(R.id.tagSearchMetaHint);
        LinearLayoutManager lm = new NpaLinearLayoutManager(context);
        searchResult.setLayoutManager(lm);
        searchResult.setHasFixedSize(true);
        searchResult.setItemViewCacheSize(12);
        searchResult.setItemAnimator(null);
        searchResult.setOverScrollMode(View.OVER_SCROLL_NEVER);
        searchResult.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if(isFinishing() || destroyed)
                    return;
                if(newState == RecyclerView.SCROLL_STATE_IDLE)
                    Glide.with(TagSearchActivity.this).resumeRequests();
                scheduleThumbnailPreload();
                maybeLoadMoreSearchResults();
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if(isFinishing() || destroyed)
                    return;
                scheduleThumbnailPreload();
                maybeLoadMoreSearchResults();
            }
        });
        Intent i = getIntent();
        query = i.getStringExtra("query");
        mode = i.getIntExtra("mode",0);
        String title = i.getStringExtra("title");
        swipe = this.findViewById(R.id.tagSearchSwipe);
        baseMode = i.getIntExtra("baseMode", base_comic);

        ActionBar ab = getSupportActionBar();
        switch(mode){
            case 0:
                break;
            case 1:
                resultLabel = "작가: " + query;
                ab.setTitle(resultLabel);
                break;
            case 2:
                resultLabel = "태그: " + query;
                ab.setTitle(resultLabel);
                break;
            case 3:
            case 4:
                resultLabel = "검색 결과";
                ab.setTitle(resultLabel);
                break;
            case 5:
                resultLabel = "최근 추가됨";
                ab.setTitle(resultLabel);
                break;
            case 6:
                resultLabel = "검색 결과";
                ab.setTitle(resultLabel);
                break;
            case 7:
                resultLabel = "북마크";
                ab.setTitle(resultLabel);
                break;
        }

        if(mode == 8) {
            resultLabel = title == null ? "분류 결과" : title;
            ab.setTitle(resultLabel);
        }
        updateResultMeta();
        ab.setDisplayHomeAsUpEnabled(true);
        swipe.setRefreshing(true);

        if(mode == 5) {
            uadapter = new UpdatedAdapter(context);
            updated = new UpdatedList(p.getBaseMode());
            startLoad(new getUpdated());
            swipe.setOnRefreshListener(direction -> {
                 if (!updated.isLast()) {
                    startLoad(new getUpdated());
                } else swipe.setRefreshing(false);
            });

        }else if(mode == 7){
            adapter = new TitleAdapter(context);
            bookmark = new Bookmark();
            startLoad(new getBookmarks());
            swipe.setOnRefreshListener(direction -> {
                if (bookmark.isLast()) {
                    startLoad(new getBookmarks());
                } else swipe.setRefreshing(false);
            });

        }else {
            adapter = new TitleAdapter(context);
            search = MangaRepository.createSearch(query,mode,baseMode);
            startLoad(new searchManga());
            swipe.setOnRefreshListener(direction -> {
                if (!search.isLast()) {
                    startLoad(new searchManga());
                } else swipe.setRefreshing(false);
            });
        }
    }

    private void startLoad(LoadOperation task) {
        if(loadTask != null) {
            swipe.setRefreshing(true);
            return;
        }
        loadTask = task;
        swipe.setRefreshing(true);
        task.start();
    }

    private boolean prepareLoadResult(LoadOperation task) {
        if(loadTask != task || destroyed || isFinishing())
            return false;
        loadTask = null;
        return true;
    }

    private void clearLoad(LoadOperation task) {
        if(loadTask == task)
            loadTask = null;
        if(swipe != null)
            swipe.setRefreshing(false);
    }

    private interface LoadOperation {
        void start();
        void cancel();
    }

    public boolean onOptionsItemSelected(MenuItem item){
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }


    private class getBookmarks implements LoadOperation {
        private MangaRepository.Cancellation cancellation;
        private AppDispatchers.TaskHandle handle;
        private volatile boolean cancelled;

        public void start() {
            handle = AppDispatchers.submitUserAction(() -> {
                Integer result = load();
                AppDispatchers.runOnMain(() -> finish(result));
            });
        }

        private void finish(Integer integer) {
            if(cancelled)
                return;
            if(!prepareLoadResult(this))
                return;
            if(integer != 0){
                showCaptchaPopup(context, p);
            }
            if(adapter.getItemCount()==0) {
                adapter.addData(bookmark.getResult());
                searchResult.setAdapter(adapter);
                adapter.setClickListener(new TitleAdapter.ItemClickListener() {
                    @Override
                    public void onResumeClick(int position, int id) {
                        Title title = adapter.getItem(position);
                        Manga manga = new Manga(id,"","", title == null ? baseMode : title.getBaseMode());
                        if(title != null) {
                            manga.setTitle(title);
                            manga.setTitleId(title.getId());
                            ArrayList<Manga> episodes = Utils.snapshotEpisodes(title);
                            if(episodes.size() > 0)
                                manga.setEps(episodes);
                        }
                        openViewerPrepared(context, manga, 0, false, true, false, title, true);
                    }

                    @Override
                    public void onItemClick(int position) {
                        // start intent : Episode viewer
                        Title selected = adapter.getItem(position);
                        Intent episodeView = episodeIntent(context, selected);
                        episodeView.putExtra("online", true);
                        startActivity(episodeView);
                    }

                    @Override
                    public void onLongClick(View view, int position) {
                        popup(view, position, adapter.getItem(position), 0);
                    }
                });
            }else{
                adapter.addData(bookmark.getResult());
            }

            if(adapter.getItemCount()>0) {
                noresult.setVisibility(View.GONE);
            }else{
                noresult.setVisibility(View.VISIBLE);
            }
            updateResultMeta();
            scheduleThumbnailPreload();
            swipe.setRefreshing(false);
            searchResult.post(TagSearchActivity.this::maybeLoadMoreSearchResults);
        }

        private Integer load() {
            cancellation = MangaRepository.cancellation();
            try {
                return MangaRepository.fetchBookmark(bookmark, cancellation);
            } catch (Exception e) {
                if(!cancelled)
                    ml.melun.mangaview.report.CrashReporter.record(e);
                return 1;
            }
        }

        public void cancel() {
            cancelled = true;
            if(cancellation != null)
                cancellation.cancel();
            if(handle != null)
                handle.cancel();
            clearLoad(this);
        }
    }


    private class searchManga implements LoadOperation {
        private MangaRepository.Cancellation cancellation;
        private AppDispatchers.TaskHandle handle;
        private volatile boolean cancelled;

        public void start(){
            handle = AppDispatchers.submitUserAction(() -> {
                Integer result = load();
                AppDispatchers.runOnMain(() -> finish(result));
            });
        }

        private Integer load(){
            cancellation = MangaRepository.cancellation();
            try {
                return MangaRepository.search(search, cancellation);
            } catch (Exception e) {
                if(!cancelled)
                    ml.melun.mangaview.report.CrashReporter.record(e);
                return 1;
            }
        }

        private void finish(Integer res){
            if(cancelled)
                return;
            if(!prepareLoadResult(this))
                return;
            if(res == null)
                res = 1;
            if(res != 0){
                showCaptchaPopup(context, p);
            }
            if(adapter.getItemCount()==0) {
                adapter.addData(search.getResult());
                searchResult.setAdapter(adapter);
                adapter.setClickListener(new TitleAdapter.ItemClickListener() {
                    @Override
                    public void onResumeClick(int position, int id) {
                        Title title = adapter.getItem(position);
                        Manga manga = new Manga(id,"","", search.getBaseMode());
                        if(title != null) {
                            manga.setTitle(title);
                            manga.setTitleId(title.getId());
                            ArrayList<Manga> episodes = Utils.snapshotEpisodes(title);
                            if(episodes.size() > 0)
                                manga.setEps(episodes);
                        }
                        openViewerPrepared(context, manga, 0, false, true, false, title, true);
                    }

                    @Override
                    public void onItemClick(int position) {
                        // start intent : Episode viewer
                        Title selected = adapter.getItem(position);
                        Intent episodeView = episodeIntent(context, selected);
                        episodeView.putExtra("online", true);
                        startActivity(episodeView);
                    }

                    @Override
                    public void onLongClick(View view, int position) {
                        popup(view, position, adapter.getItem(position), 0);
                    }
                });
            }else{
                adapter.addData(search.getResult());
            }

            if(adapter.getItemCount()>0) {
                noresult.setVisibility(View.GONE);
            }else{
                noresult.setVisibility(View.VISIBLE);
            }
            updateVirtualScrollbar();
            updateResultMeta();
            scheduleThumbnailPreload();
            swipe.setRefreshing(false);
        }

        public void cancel() {
            cancelled = true;
            if(cancellation != null)
                cancellation.cancel();
            if(handle != null)
                handle.cancel();
            clearLoad(this);
        }
    }

    private class getUpdated implements LoadOperation {
        private MangaRepository.Cancellation cancellation;
        private AppDispatchers.TaskHandle handle;
        private volatile boolean cancelled;

        public void start(){
            handle = AppDispatchers.submitUserAction(() -> {
                String result = load();
                AppDispatchers.runOnMain(() -> finish(result));
            });
        }

        private String load(){
            cancellation = MangaRepository.cancellation();
            try {
                MangaRepository.loadUpdates(updated, cancellation);
                return null;
            } catch (Exception e) {
                if(!cancelled)
                    ml.melun.mangaview.report.CrashReporter.record(e);
                return null;
            }
        }

        private void finish(String res){
            if(cancelled)
                return;
            if(!prepareLoadResult(this))
                return;
            ArrayList<UpdatedManga> result = updated.getResult();
            if(result == null)
                result = new ArrayList<>();
            if(result.size() == 0 && uadapter.getItemCount() == 0){
                //error
                showCaptchaPopup(context, p);
            }
            if(uadapter.getItemCount()==0) {
                uadapter.addData(result);
                searchResult.setAdapter(uadapter);
                uadapter.setOnClickListener(new UpdatedAdapter.onclickListener() {
                    @Override
                    public void onEpsClick(Title t) {
                        Intent eps = episodeIntent(context, t);
                        eps.putExtra("online", true);
                        startActivity(eps);
                    }

                    @Override
                    public void onClick(Manga m) {
                        //open viewer
                        openViewerPrepared(context, m, 0, false, true, false, m == null ? null : m.getTitle(), true);
                    }
                });
            }else{
                uadapter.addData(result);
            }

            if(uadapter.getItemCount()>0) {
                noresult.setVisibility(View.GONE);
            }else{
                noresult.setVisibility(View.VISIBLE);
            }
            updateResultMeta();
            scheduleThumbnailPreload();
            swipe.setRefreshing(false);
        }

        public void cancel() {
            cancelled = true;
            if(cancellation != null)
                cancellation.cancel();
            if(handle != null)
                handle.cancel();
            clearLoad(this);
        }
    }
    void popup(View view, final int position, final Title title, final int m){
        PopupMenu popup = new PopupMenu(TagSearchActivity.this, view);
        //Inflating the Popup using xml file
        popup.getMenuInflater()
                .inflate(R.menu.title_options, popup.getMenu());
        popup.getMenu().removeItem(R.id.del);
        popup.getMenu().findItem(R.id.favAdd).setVisible(true);
        popup.getMenu().findItem(R.id.favDel).setVisible(true);
        if(p.findFavorite(title)>-1) popup.getMenu().removeItem(R.id.favAdd);
        else popup.getMenu().removeItem(R.id.favDel);


        //registering popup with OnMenuItemClickListener
        popup.setOnMenuItemClickListener(item -> {
            switch(item.getItemId()){
                case R.id.del:
                    break;
                case R.id.favAdd:
                case R.id.favDel:
                    //toggle favorite
                    p.toggleFavorite(title,0);
                    break;
            }
            return true;
        });
        popup.show(); //showing popup menu
    }

    private void scheduleThumbnailPreload() {
        if(searchResult == null)
            return;
        if(thumbnailPreloadRunnable != null)
            searchResult.removeCallbacks(thumbnailPreloadRunnable);
        thumbnailPreloadRunnable = this::preloadVisibleThumbnails;
        searchResult.postDelayed(thumbnailPreloadRunnable, THUMBNAIL_PRELOAD_DELAY_MS);
    }

    private void preloadVisibleThumbnails() {
        if(searchResult == null || destroyed || isFinishing())
            return;
        RecyclerView.LayoutManager manager = searchResult.getLayoutManager();
        if(!(manager instanceof LinearLayoutManager))
            return;
        LinearLayoutManager layoutManager = (LinearLayoutManager) manager;
        int first = layoutManager.findFirstVisibleItemPosition();
        int last = layoutManager.findLastVisibleItemPosition();
        if(first == RecyclerView.NO_POSITION)
            first = 0;
        int visibleCount = last >= first ? last - first + 1 : 8;
        int preloadCount = visibleCount + THUMBNAIL_PRELOAD_AHEAD;
        if(adapter != null)
            adapter.preloadThumbnails(first, preloadCount);
        if(uadapter != null)
            uadapter.preloadThumbnails(first, preloadCount);
    }

    private void maybeLoadMoreSearchResults() {
        if(mode != 8 || search == null || searchResult == null || loadTask != null || destroyed || isFinishing())
            return;
        if(search.isLast())
            return;
        RecyclerView.LayoutManager manager = searchResult.getLayoutManager();
        if(!(manager instanceof LinearLayoutManager))
            return;
        int count = adapter == null ? 0 : adapter.getItemCount();
        if(count == 0)
            return;
        LinearLayoutManager layoutManager = (LinearLayoutManager) manager;
        int lastVisible = layoutManager.findLastVisibleItemPosition();
        if(lastVisible == RecyclerView.NO_POSITION)
            lastVisible = 0;
        if(lastVisible >= count - LOAD_MORE_THRESHOLD)
            startLoad(new searchManga());
    }

    private void updateVirtualScrollbar() {
        if(!(searchResult instanceof StableScrollbarRecyclerView) || search == null)
            return;
        ((StableScrollbarRecyclerView) searchResult).setVirtualItemCount(search.getVirtualResultCount());
    }

    private void updateResultMeta() {
        if(resultMetaTitle == null || resultMetaHint == null)
            return;
        int loaded = adapter != null ? adapter.getItemCount() : (uadapter != null ? uadapter.getItemCount() : 0);
        int total = search == null ? 0 : search.getVirtualResultCount();
        String label;
        if(resultLabel != null) {
            label = resultLabel;
        } else if(mode == 5) {
            label = "최근 추가됨";
        } else if(mode == 7) {
            label = "북마크";
        } else {
            label = "검색 결과";
        }
        resultMetaTitle.setText(label);
        if(total > loaded)
            resultMetaHint.setText(loaded + "/" + total + "개 표시");
        else if(loaded > 0)
            resultMetaHint.setText(loaded + "개 표시");
        else
            resultMetaHint.setText("결과 준비 중");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(resultCode == RESULT_CAPTCHA){
            //captcha
            finish();
            startActivity(getIntent());
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if(loadTask != null) {
            loadTask.cancel();
            loadTask = null;
        }
        if(searchResult != null && thumbnailPreloadRunnable != null)
            searchResult.removeCallbacks(thumbnailPreloadRunnable);
        super.onDestroy();
    }
}
