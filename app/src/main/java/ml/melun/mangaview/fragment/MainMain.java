package ml.melun.mangaview.fragment;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.tabs.TabLayout;
import com.bumptech.glide.Glide;

import ml.melun.mangaview.interfaces.MainActivityCallback;
import ml.melun.mangaview.R;
import ml.melun.mangaview.Preference;
import ml.melun.mangaview.Utils;
import ml.melun.mangaview.activity.MainActivity;
import ml.melun.mangaview.activity.TagSearchActivity;
import ml.melun.mangaview.adapter.MainAdapter;
import ml.melun.mangaview.adapter.MainWebtoonAdapter;
import ml.melun.mangaview.interfaces.UrlUpdateCallback;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.ui.NpaLinearLayoutManager;

import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.episodeIntent;
import static ml.melun.mangaview.Utils.openViewer;
import static ml.melun.mangaview.activity.CaptchaActivity.RESULT_CAPTCHA;
import static ml.melun.mangaview.mangaview.MTitle.base_comic;
import static ml.melun.mangaview.mangaview.MTitle.base_webtoon;

public class MainMain extends Fragment{

    RecyclerView mainRecycler;
    RecyclerView webtoonRecycler;
    RecyclerView comicRecycler;
    MainWebtoonAdapter mainComicAdapter;
    MainWebtoonAdapter mainWebtoonAdapter;
    MainAdapter.onItemClick homeClickListener;
    Fragment fragment;
    boolean wait = false;
    UrlUpdateCallback callback;
    MainActivityCallback mainActivityCallback;
    TabLayout mainTabLayout;
    TextView modeWebtoon;
    TextView modeComic;
    int selectedBaseMode = base_webtoon;

    final static int FOR_YOU_TAB = 0;
    final static int POPULAR_TAB = 1;
    final static int NEW_TAB = 2;
    final static int GENRES_TAB = 3;

    boolean fragmentActive = false;
    boolean comicFetched = false;
    boolean webtoonFetched = false;
    boolean viewStarted = false;
    int scrollRequestVersion = 0;
    Preference.LocalChangeListener localChangeListener;

    public void setWait(Boolean wait){
        this.wait = wait;
    }


    public static MainMain newInstance(){
        MainMain frag = new MainMain();
        frag.initializeCallback();
        return frag;
    }

    public MainMain(){

    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.mainActivityCallback = (MainActivity)getActivity();
    }

    public void initializeCallback(){
        callback = success -> {
            wait = false;
            comicFetched = false;
            webtoonFetched = false;
            if(fragmentActive)
                fetchSelected();
        };
    }

    public UrlUpdateCallback getCallback(){
        return callback;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        ViewGroup rootView = (ViewGroup) inflater.inflate(R.layout.content_main , container, false);


        mainTabLayout = rootView.findViewById(R.id.mainTab);
        modeWebtoon = rootView.findViewById(R.id.modeWebtoon);
        modeComic = rootView.findViewById(R.id.modeComic);

        TabLayout.Tab forYouTab = mainTabLayout.newTab().setText("홈");
        TabLayout.Tab popularTab = mainTabLayout.newTab().setText("인기");
        TabLayout.Tab newTab = mainTabLayout.newTab().setText("신작");
        TabLayout.Tab genresTab = mainTabLayout.newTab().setText("장르");
        mainTabLayout.addTab(forYouTab);
        mainTabLayout.addTab(popularTab);
        mainTabLayout.addTab(newTab);
        mainTabLayout.addTab(genresTab);
        forYouTab.select();

        mainTabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                applySelectedHomeTab();
                scrollToSelectedTab();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {

            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                applySelectedHomeTab();
                scrollToSelectedTab();
            }
        });

        fragment = this;
        //main content
        // 최근 추가된 만화
        webtoonRecycler = rootView.findViewById(R.id.main_recycler);
        comicRecycler = rootView.findViewById(R.id.main_comic_recycler);
        configureHomeRecycler(webtoonRecycler);
        configureHomeRecycler(comicRecycler);
        mainRecycler = webtoonRecycler;
        localChangeListener = scope -> {
            if(!"recent".equals(scope) && !"bookmark".equals(scope))
                return;
            RecyclerView target = mainRecycler;
            if(target != null)
                target.post(this::refreshHomeLocalState);
        };
        p.addLocalChangeListener(localChangeListener);


        homeClickListener = new MainAdapter.onItemClick() {

            @Override
            public void clickedTitle(Title t) {
                startActivity(episodeIntent(getContext(), t));
            }

            @Override
            public void clickedManga(Manga m) {
                //mget title from manga m and start intent for manga m
                //getTitleFromManga intentStarter = new getTitleFromManga();
                //intentStarter.execute(m);
                openViewer(getContext(), m,-1);
            }

            @Override
            public void clickedGenre(String t) {
                Intent i = new Intent(getContext(), TagSearchActivity.class);
                i.putExtra("query",t);
                i.putExtra("mode",2);
                i.putExtra("baseMode", p.getBaseMode());
                startActivity(i);
            }

            @Override
            public void clickedName(String t) {
                Intent i = new Intent(getContext(), TagSearchActivity.class);
                i.putExtra("query",t);
                i.putExtra("mode",3);
                i.putExtra("baseMode", p.getBaseMode());
                startActivity(i);
            }

            @Override
            public void clickedRelease(String t) {
                Intent i = new Intent(getContext(), TagSearchActivity.class);
                i.putExtra("query",t);
                i.putExtra("mode",4);
                i.putExtra("baseMode", p.getBaseMode());
                startActivity(i);
            }

            @Override
            public void clickedMoreUpdated() {
                Intent i = new Intent(getContext(), TagSearchActivity.class);
                i.putExtra("mode",5);
                startActivity(i);
            }

            @Override
            public void captchaCallback() {
                Utils.showCaptchaPopup(getActivity(), 3, fragment, p);
            }

            @Override
            public void clickedSearch(String query) {
                mainActivityCallback.search(query);
            }

            @Override
            public void clickedRetry() {
                fetchSelected();
            }

            @Override
            public void clickedCategoryPath(String title, String path) {
                Intent i = new Intent(getContext(), TagSearchActivity.class);
                i.putExtra("query", path);
                i.putExtra("title", title);
                i.putExtra("mode", 8);
                i.putExtra("baseMode", p.getBaseMode());
                startActivity(i);
            }

            @Override
            public void clickedHomeAction(int action) {
                if(mainActivityCallback == null)
                    return;
                if(action == MainWebtoonAdapter.ACTION_UPDATES)
                    selectHomeTab(NEW_TAB);
                else if(action == MainWebtoonAdapter.ACTION_BOOKMARKS)
                    mainActivityCallback.navigateToTab(2);
                else if(action == MainWebtoonAdapter.ACTION_DOWNLOADS)
                    mainActivityCallback.navigateToTab(2);
                else if(action == MainWebtoonAdapter.ACTION_GENRES) {
                    TabLayout.Tab tab = mainTabLayout == null ? null : mainTabLayout.getTabAt(GENRES_TAB);
                    if(tab != null)
                        tab.select();
                }
            }

            @Override
            public void longClickedContinue(View view, Title title) {
                showContinuePopup(view, title);
            }
        };

        selectedBaseMode = p.getBaseMode() == base_comic ? base_comic : base_webtoon;
        ensureHomeAdapter(selectedBaseMode);
        modeWebtoon.setOnClickListener(v -> {
            switchBaseMode(base_webtoon);
            fetchSelected();
            scheduleInactivePrefetch();
        });
        modeComic.setOnClickListener(v -> {
            switchBaseMode(base_comic);
            fetchSelected();
            scheduleInactivePrefetch();
        });
        switchBaseMode(selectedBaseMode);

        showInitialHomeRows();
        RecyclerView selectedRecycler = getSelectedRecycler();
        if(selectedRecycler != null) {
            selectedRecycler.postDelayed(() -> {
                if(!isAdded())
                    return;
                if(!wait)
                    fetchSelected();
                scheduleInactivePrefetch();
            }, 160);
        }
        return rootView;
    }

    private void configureHomeRecycler(RecyclerView recyclerView) {
        if(recyclerView == null)
            return;
        NpaLinearLayoutManager lm = new NpaLinearLayoutManager(getContext());
        recyclerView.setLayoutManager(lm);
        recyclerView.setHasFixedSize(true);
        recyclerView.setItemViewCacheSize(8);
        recyclerView.setItemAnimator(null);
        recyclerView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if(getContext() == null)
                    return;
                if(newState == RecyclerView.SCROLL_STATE_IDLE)
                    Glide.with(MainMain.this).resumeRequests();
                else
                    Glide.with(MainMain.this).pauseRequests();
            }
        });
    }

    private void registerRevealObserver(MainWebtoonAdapter adapter, RecyclerView recyclerView, int baseMode) {
        if(adapter == null || recyclerView == null)
            return;
        adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                revealIfSelectedAndReady();
            }

            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) {
                revealIfSelectedAndReady();
            }

            private void revealIfSelectedAndReady() {
                if(selectedBaseMode != baseMode || mainRecycler != recyclerView || !adapter.hasDisplayContent())
                    return;
                recyclerView.post(() -> {
                    if(selectedBaseMode == baseMode && mainRecycler == recyclerView && adapter.hasDisplayContent() && recyclerView.getAlpha() < 1f) {
                        showSelectedRecycler(getOtherRecycler(recyclerView), recyclerView);
                        scrollHomeToTop();
                    }
                });
            }
        });
    }

    private MainWebtoonAdapter ensureHomeAdapter(int baseMode) {
        if(baseMode == base_comic) {
            if(mainComicAdapter == null) {
                mainComicAdapter = new MainWebtoonAdapter(getContext(), base_comic);
                mainComicAdapter.setListener(homeClickListener);
                mainComicAdapter.setAnchorRecycler(comicRecycler);
                if(comicRecycler != null)
                    comicRecycler.setAdapter(mainComicAdapter);
                registerRevealObserver(mainComicAdapter, comicRecycler, base_comic);
            }
            return mainComicAdapter;
        }
        if(mainWebtoonAdapter == null) {
            mainWebtoonAdapter = new MainWebtoonAdapter(getContext());
            mainWebtoonAdapter.setListener(homeClickListener);
            mainWebtoonAdapter.setAnchorRecycler(webtoonRecycler);
            if(webtoonRecycler != null)
                webtoonRecycler.setAdapter(mainWebtoonAdapter);
            registerRevealObserver(mainWebtoonAdapter, webtoonRecycler, base_webtoon);
        }
        return mainWebtoonAdapter;
    }

    private MainWebtoonAdapter getSelectedAdapter() {
        ensureHomeAdapter(selectedBaseMode);
        return selectedBaseMode == base_comic ? mainComicAdapter : mainWebtoonAdapter;
    }

    private int getSelectedTabPosition() {
        if(mainTabLayout == null || mainTabLayout.getSelectedTabPosition() < 0)
            return FOR_YOU_TAB;
        return mainTabLayout.getSelectedTabPosition();
    }

    private void switchBaseMode(int baseMode) {
        RecyclerView previousRecycler = mainRecycler;
        selectedBaseMode = baseMode;
        p.setBaseMode(baseMode);
        ensureHomeAdapter(baseMode);
        updateModeToggle();
        MainWebtoonAdapter selectedAdapter = getSelectedAdapter();
        if(selectedAdapter != null)
            selectedAdapter.showInitialRows();
        mainRecycler = getSelectedRecycler();
        if(mainRecycler != null) {
            if(previousRecycler != null)
                previousRecycler.stopScroll();
            mainRecycler.stopScroll();
            showSelectedRecycler(previousRecycler, mainRecycler);
            scrollHomeToTop();
        }
        applySelectedHomeTab();
        scrollToSelectedTab();
    }

    private RecyclerView getSelectedRecycler() {
        return selectedBaseMode == base_comic ? comicRecycler : webtoonRecycler;
    }

    private RecyclerView getOtherRecycler(RecyclerView recyclerView) {
        return recyclerView == comicRecycler ? webtoonRecycler : comicRecycler;
    }

    private void showSelectedRecycler(RecyclerView previousRecycler, RecyclerView selectedRecycler) {
        if(selectedRecycler == null)
            return;
        selectedRecycler.setAlpha(1f);
        selectedRecycler.setEnabled(true);
        selectedRecycler.bringToFront();
        RecyclerView inactiveRecycler = selectedRecycler == comicRecycler ? webtoonRecycler : comicRecycler;
        if(inactiveRecycler != null && inactiveRecycler != selectedRecycler) {
            inactiveRecycler.setAlpha(0f);
            inactiveRecycler.setEnabled(false);
        }
        if(previousRecycler != null && previousRecycler != selectedRecycler) {
            previousRecycler.setAlpha(0f);
            previousRecycler.setEnabled(false);
        }
    }

    private void applySelectedHomeTab() {
        MainWebtoonAdapter adapter = getSelectedAdapter();
        if(adapter != null)
            adapter.setHomeTab(getSelectedTabPosition());
    }

    private void updateModeToggle() {
        if(getContext() == null || modeWebtoon == null || modeComic == null)
            return;
        boolean webtoon = selectedBaseMode == base_webtoon;
        styleModeButton(modeWebtoon, webtoon);
        styleModeButton(modeComic, !webtoon);
    }

    private void styleModeButton(TextView view, boolean selected) {
        view.setBackgroundResource(selected ? R.drawable.app_accent_button_bg : android.R.color.transparent);
        view.setTextColor(ContextCompat.getColor(getContext(), selected ? android.R.color.white : R.color.appTextSecondary));
    }

    public void scrollToSelectedTab() {
        if(mainRecycler == null)
            return;
        MainWebtoonAdapter adapter = getSelectedAdapter();
        if(adapter == null)
            return;
        int target = adapter.getScrollPositionForHomeTab(getSelectedTabPosition());
        int requestVersion = ++scrollRequestVersion;
        int requestBaseMode = selectedBaseMode;
        int requestTab = getSelectedTabPosition();
        mainRecycler.stopScroll();
        scrollRecyclerToPosition(target);
        mainRecycler.post(() -> {
            if(mainRecycler == null || requestVersion != scrollRequestVersion || requestBaseMode != selectedBaseMode || requestTab != getSelectedTabPosition())
                return;
            scrollRecyclerToPosition(target);
        });
        mainRecycler.postDelayed(() -> {
            if(mainRecycler == null || requestVersion != scrollRequestVersion || requestBaseMode != selectedBaseMode || requestTab != getSelectedTabPosition())
                return;
            scrollRecyclerToPosition(target);
        }, 120);
        mainRecycler.postDelayed(() -> {
            if(mainRecycler == null || requestVersion != scrollRequestVersion || requestBaseMode != selectedBaseMode || requestTab != getSelectedTabPosition())
                return;
            scrollRecyclerToPosition(target);
        }, 300);
    }

    private void scrollHomeToTop() {
        if(mainRecycler == null)
            return;
        scrollRecyclerToPosition(0);
        mainRecycler.post(() -> {
            if(mainRecycler != null)
                scrollRecyclerToPosition(0);
        });
    }

    private void scrollRecyclerToPosition(int position) {
        if(mainRecycler == null)
            return;
        RecyclerView.LayoutManager manager = mainRecycler.getLayoutManager();
        if(manager instanceof LinearLayoutManager)
            ((LinearLayoutManager) manager).scrollToPositionWithOffset(position, 0);
        else if(manager != null)
            manager.scrollToPosition(position);
    }

    private void selectHomeTab(int position) {
        if(mainTabLayout == null || position < 0 || position >= mainTabLayout.getTabCount())
            return;
        TabLayout.Tab tab = mainTabLayout.getTabAt(position);
        if(tab != null)
            tab.select();
    }

    private void fetchSelected() {
        if(selectedBaseMode == base_comic)
            fetchComic();
        else
            fetchWebtoon();
    }

    private void showInitialHomeRows() {
        MainWebtoonAdapter adapter = getSelectedAdapter();
        if(adapter != null)
            adapter.showInitialRows();
    }

    private void scheduleInactivePrefetch() {
        RecyclerView targetRecycler = mainRecycler != null ? mainRecycler : getSelectedRecycler();
        if(targetRecycler == null || wait)
            return;
        final int visibleBaseMode = selectedBaseMode;
        targetRecycler.postDelayed(() -> {
            if(!isAdded() || wait)
                return;
            if(visibleBaseMode == base_comic)
                fetchWebtoon();
            else
                fetchComic();
        }, 900);
    }

    private void fetchComic() {
        ensureHomeAdapter(base_comic);
        if(mainComicAdapter != null && !comicFetched) {
            comicFetched = true;
            mainComicAdapter.fetch();
        }
    }

    private void fetchWebtoon() {
        ensureHomeAdapter(base_webtoon);
        if(mainWebtoonAdapter != null && !webtoonFetched) {
            webtoonFetched = true;
            mainWebtoonAdapter.fetch();
        }
    }

    private void refreshHomeLocalState() {
        if(mainComicAdapter != null && comicFetched)
            mainComicAdapter.refreshLocalState();
        if(mainWebtoonAdapter != null && webtoonFetched)
            mainWebtoonAdapter.refreshLocalState();
    }

    private void showContinuePopup(View view, Title title) {
        if(getContext() == null || view == null || title == null)
            return;
        PopupMenu popup = new PopupMenu(getContext(), view);
        popup.getMenuInflater().inflate(R.menu.title_options, popup.getMenu());
        popup.getMenu().findItem(R.id.del).setVisible(true);
        popup.setOnMenuItemClickListener(item -> {
            if(item.getItemId() == R.id.del) {
                p.removeRecent(title);
                refreshHomeLocalState();
                return true;
            }
            return false;
        });
        popup.show();
    }

    @Override
    public void onStart() {
        super.onStart();
        fragmentActive = true;
    }

    @Override
    public void onResume() {
        super.onResume();
        if(viewStarted)
            refreshHomeLocalState();
        viewStarted = true;
    }

    @Override
    public void onStop() {
        super.onStop();
        fragmentActive = false;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if(mainComicAdapter != null)
            mainComicAdapter.cancelFetch();
        if(mainWebtoonAdapter != null)
            mainWebtoonAdapter.cancelFetch();
        if(localChangeListener != null) {
            p.removeLocalChangeListener(localChangeListener);
            localChangeListener = null;
        }
        mainRecycler = null;
        webtoonRecycler = null;
        comicRecycler = null;
        mainComicAdapter = null;
        mainWebtoonAdapter = null;
        homeClickListener = null;
        comicFetched = false;
        webtoonFetched = false;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(resultCode == RESULT_CAPTCHA) {
            if(p.getBaseMode() == base_comic)
                comicFetched = false;
            else
                webtoonFetched = false;
            fetchSelected();
        }
    }
}
