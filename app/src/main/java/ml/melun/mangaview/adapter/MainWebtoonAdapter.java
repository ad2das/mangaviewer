package ml.melun.mangaview.adapter;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;

import ml.melun.mangaview.R;
import ml.melun.mangaview.Utils;
import ml.melun.mangaview.glide.ViewerWarmupManager;
import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.MainPageWebtoon;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Ranking;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.repository.CacheFileStore;
import ml.melun.mangaview.repository.MangaRepository;
import ml.melun.mangaview.runtime.AppDispatchers;
import ml.melun.mangaview.runtime.PerformanceMonitor;
import ml.melun.mangaview.runtime.PerfTrace;
import ml.melun.mangaview.ui.NpaLinearLayoutManager;

import static ml.melun.mangaview.MainApplication.getHttpClient;
import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.getGlideUrl;
import static ml.melun.mangaview.mangaview.MTitle.base_comic;
import static ml.melun.mangaview.mangaview.MTitle.base_webtoon;

public class MainWebtoonAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int HERO = 21;
    private static final int HOME_SECTION = 22;
    private static final int GROUP = 23;
    private static final int SECTION = 24;
    private static final int CATEGORY = 25;
    private static final int ACTION_STRIP = 26;
    private static final int STYLE_CONTINUE = 1;
    private static final int STYLE_RANKING = 2;
    private static final boolean HOME_HERO_ENABLED = true;
    private static final int HOME_EXTRA_SECTION_LIMIT = 4;
    private static final int STYLE_STANDARD = 3;
    private static final int VIEW_TYPE_HOME_CONTINUE = 101;
    private static final int VIEW_TYPE_HOME_RANKING = 102;
    private static final int VIEW_TYPE_HOME_STANDARD = 103;
    private static final int VIEW_TYPE_WEBTOON_CARD = 201;
    public static final int ACTION_UPDATES = 1;
    public static final int ACTION_BOOKMARKS = 2;
    public static final int ACTION_DOWNLOADS = 3;
    public static final int ACTION_GENRES = 4;

    Context context;
    boolean dark;
    boolean save;
    LayoutInflater inflater;
    List<Ranking<?>> dataSet;
    List<Object> rows;
    MainAdapter.onItemClick listener;
    int baseMode;
    Fetcher fetcher;
    RecyclerView anchorRecycler;
    RecyclerView.OnScrollListener anchorScrollListener;
    RecyclerView.OnItemTouchListener anchorContinueTouchListener;
    private final RecyclerView.RecycledViewPool sharedHomePool = new RecyclerView.RecycledViewPool();
    List<Object> pendingRows;
    boolean initialRowsShown = false;
    private final Set<String> preloadedThumbs = new LinkedHashSet<>();
    private static final int PRELOADED_THUMB_LIMIT = 160;
    private static final int PRELOAD_THUMB_MAX_PER_FETCH = 24;
    private static final int SECTION_BATCH_SIZE = 4;
    private static final int FIRST_SCREEN_BATCH_SIZE = 1;
    private static final String HOME_CACHE_KEY_PREFIX = "homeSnapshotV1_";
    private static final int HOME_CACHE_MAX_SECTIONS = 6;
    private static final int HOME_CACHE_MAX_TITLES_PER_SECTION = 10;
    private static final Executor ROW_DIFF_EXECUTOR = AppDispatchers.uiDiff();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private int preloadCount = 0;
    private int activeHomeTab = 0;
    private boolean continueProgressBackfillRunning = false;
    private int rowDiffGeneration = 0;
    private long firstContentStartedAt = PerfTrace.start("home_first_content_ms");
    private boolean firstContentLogged = false;
    private FetchStateListener fetchStateListener;
    private boolean siteNtkSnapshot;
    private boolean cacheLoadInFlight = false;
    private String lastContinueOpenKey = "";
    private long lastContinueOpenAt = 0L;
    private float anchorDownX;
    private float anchorDownY;
    private boolean anchorTouchMoved;

    public interface FetchStateListener {
        void onFetchFinished(int baseMode, boolean success);
    }

    public MainWebtoonAdapter(Context context){
        this(context, base_webtoon);
    }

    public MainWebtoonAdapter(Context context, int baseMode){
        this.context = context;
        this.baseMode = baseMode;
        this.dark = p.getDarkTheme();
        this.save = p.getDataSave();
        inflater = LayoutInflater.from(context);
        siteNtkSnapshot = isNtkSite();
        dataSet = initialDataSetForSite();
        rows = new ArrayList<>();
        sharedHomePool.setMaxRecycledViews(VIEW_TYPE_HOME_CONTINUE, 12);
        sharedHomePool.setMaxRecycledViews(VIEW_TYPE_HOME_RANKING, 12);
        sharedHomePool.setMaxRecycledViews(VIEW_TYPE_HOME_STANDARD, 12);
        sharedHomePool.setMaxRecycledViews(VIEW_TYPE_WEBTOON_CARD, 18);
        setHasStableIds(true);
    }

    public void fetch(){
        if(fetcher != null)
            fetcher.cancel(true);
        fetcher = new Fetcher();
        fetcher.start();
    }

    public void showInitialRows() {
        refreshSiteSnapshot();
        if(rows != null && rows.size() > 0 && hasDisplayContent(rows) && hasCompleteHomeSections())
            return;
        if(rows != null && rows.size() > 0 && hasDisplayContent(rows)) {
            loadCachedHomeRowsAsync();
            return;
        }
        if(!hasFetchedContent())
            dataSet = initialDataSetForSite();
        List<Object> warmRows = buildInitialPlaceholderRows();
        if(!hasDisplayContent(warmRows))
            warmRows = buildRows(dataSet, false);
        if(hasDisplayContent(warmRows)) {
            initialRowsShown = true;
            updateRows(warmRows);
            if(hasHero(warmRows))
                scrollHeroToTop();
        }
        loadCachedHomeRowsAsync();
    }

    public void showPlaceholderIfEmpty() {
        refreshSiteSnapshot();
        if(rows != null && rows.size() > 0 && hasDisplayContent(rows))
            return;
        if(!hasFetchedContent())
            dataSet = initialDataSetForSite();
        List<Object> placeholderRows = buildInitialPlaceholderRows();
        if(!hasDisplayContent(placeholderRows))
            placeholderRows = buildRows(dataSet, false);
        if(!hasDisplayContent(placeholderRows))
            return;
        pendingRows = null;
        rowDiffGeneration++;
        rows = placeholderRows;
        initialRowsShown = true;
        notifyDataSetChanged();
    }

    public boolean isFetching() {
        return fetcher != null;
    }

    public boolean hasFetchedContent() {
        return collectTitles(dataSet, 1).size() > 0;
    }

    public boolean hasRequiredHomeSections() {
        return hasRequiredHomeSections(dataSet);
    }

    public boolean hasCompleteHomeSections() {
        return hasCompleteHomeSections(dataSet);
    }

    public void setFetchStateListener(FetchStateListener listener) {
        this.fetchStateListener = listener;
    }

    public void cancelFetch() {
        if(fetcher != null) {
            fetcher.cancel(true);
            fetcher = null;
        }
    }

    public void setLoading(){
        dataSet = MainPageWebtoon.getBlankDataSet(baseMode, isNtkSite());
        updateRows(new ArrayList<>());
    }

    public void resetForSiteChange() {
        cancelFetch();
        siteNtkSnapshot = isNtkSite();
        dataSet = initialDataSetForSite();
        initialRowsShown = false;
        pendingRows = null;
        rowDiffGeneration++;
        preloadedThumbs.clear();
        preloadCount = 0;
        updateRows(buildRowsForCurrentTab(true));
    }

    public void setListener(MainAdapter.onItemClick listener){
        this.listener = listener;
    }

    public void setAnchorRecycler(RecyclerView recyclerView) {
        if(this.anchorRecycler != null && anchorScrollListener != null)
            this.anchorRecycler.removeOnScrollListener(anchorScrollListener);
        if(this.anchorRecycler != null && anchorContinueTouchListener != null)
            this.anchorRecycler.removeOnItemTouchListener(anchorContinueTouchListener);
        if(this.anchorRecycler != null)
            this.anchorRecycler.setOnTouchListener(null);
        this.anchorRecycler = recyclerView;
        if(this.anchorRecycler != null) {
            this.anchorRecycler.setOnTouchListener(this::handleAnchorContinueTouch);
            anchorContinueTouchListener = new RecyclerView.SimpleOnItemTouchListener() {
                @Override
                public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent event) {
                    if(event.getActionMasked() == MotionEvent.ACTION_DOWN
                            && listener != null
                            && activeHomeTab == 0
                            && isLikelyContinueTapZone(rv, event.getY())) {
                        handleAnchorContinueTouch(rv, event);
                    }
                    return false;
                }

                @Override
                public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent event) {
                    handleAnchorContinueTouch(rv, event);
                }
            };
            this.anchorRecycler.addOnItemTouchListener(anchorContinueTouchListener);
            anchorScrollListener = new RecyclerView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                    super.onScrollStateChanged(recyclerView, newState);
                    PerformanceMonitor.phase(newState == RecyclerView.SCROLL_STATE_IDLE ? "idle" : "scrolling");
                    if(newState == RecyclerView.SCROLL_STATE_IDLE)
                        recyclerView.postDelayed(() -> {
                            if(anchorRecycler == recyclerView
                                    && recyclerView.getScrollState() == RecyclerView.SCROLL_STATE_IDLE) {
                                applyPendingRows();
                                PerformanceMonitor.reportNow("home_scroll_idle");
                            }
                        }, 250);
                }
            };
            this.anchorRecycler.addOnScrollListener(anchorScrollListener);
        } else {
            anchorScrollListener = null;
            anchorContinueTouchListener = null;
        }
    }

    public void refreshLocalState() {
        updateRows(buildRowsForCurrentTab(false));
        scheduleContinueProgressBackfill();
    }

    public void setHomeTab(int tabPosition) {
        if(activeHomeTab == tabPosition && hasDisplayContent(rows))
            return;
        activeHomeTab = tabPosition;
        List<Object> nextRows = buildRowsForCurrentTab(fetcher != null || !hasFetchedContent());
        updateRows(nextRows);
        scheduleContinueProgressBackfill();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if(viewType == HERO)
            return new HeroHolder(inflater.inflate(R.layout.item_webtoon_hero, parent, false));
        if(viewType == HOME_SECTION)
            return new HomeSectionHolder(inflater.inflate(R.layout.item_webtoon_section, parent, false));
        if(viewType == ACTION_STRIP)
            return new ActionStripHolder(inflater.inflate(R.layout.item_home_action_strip, parent, false));
        if(viewType == CATEGORY)
            return new CategoryHolder(inflater.inflate(R.layout.item_webtoon_category_panel, parent, false));
        if(viewType == GROUP)
            return new GroupHolder(inflater.inflate(R.layout.item_webtoon_group_header, parent, false));
        return new SectionHolder(inflater.inflate(R.layout.item_webtoon_section, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object row = rows.get(position);
        if(holder instanceof HeroHolder)
            ((HeroHolder) holder).bind((HeroRow) row);
        else if(holder instanceof HomeSectionHolder)
            ((HomeSectionHolder) holder).bind((HomeSection) row);
        else if(holder instanceof ActionStripHolder)
            ((ActionStripHolder) holder).bind();
        else if(holder instanceof CategoryHolder)
            ((CategoryHolder) holder).bind();
        else if(holder instanceof GroupHolder)
            ((GroupHolder) holder).bind((String) row);
        else
            ((SectionHolder) holder).bind((Ranking<?>) row);
    }

    @Override
    public int getItemViewType(int position) {
        if(rows.get(position) instanceof HeroRow)
            return HERO;
        if(rows.get(position) instanceof HomeSection)
            return HOME_SECTION;
        if(rows.get(position) instanceof ActionStrip)
            return ACTION_STRIP;
        if(rows.get(position) instanceof CategoryPanel)
            return CATEGORY;
        return rows.get(position) instanceof String ? GROUP : SECTION;
    }

    @Override
    public int getItemCount() {
        return rows == null ? 0 : rows.size();
    }

    public boolean hasDisplayContent() {
        return hasDisplayContent(rows);
    }

    private boolean hasDisplayContent(List<Object> candidateRows) {
        if(candidateRows == null || candidateRows.size() == 0)
            return false;
        for(Object row : candidateRows) {
            if(row instanceof HeroRow)
                return true;
            if(row instanceof HomeSection)
                return true;
            if(row instanceof Ranking && ((Ranking<?>) row).size() > 0)
                return true;
            if(row instanceof ActionStrip)
                return true;
            if(row instanceof CategoryPanel)
                return true;
        }
        return false;
    }

    @Override
    public long getItemId(int position) {
        if(rows == null || position < 0 || position >= rows.size())
            return RecyclerView.NO_ID;
        return rowKey(rows.get(position)).hashCode();
    }

    public int getScrollPositionForHomeTab(int tabPosition) {
        return 0;
    }

    public boolean hasCategoryPanel() {
        return findCategoryPosition() >= 0;
    }

    private int findHomeSectionPosition(String homeTitle, String fallbackSectionTitle, int fallback) {
        if(rows == null)
            return fallback;
        for(int i = 0; i < rows.size(); i++)
            if(rows.get(i) instanceof HomeSection && ((HomeSection) rows.get(i)).title.equals(homeTitle))
                return i;
        return findSectionPosition(fallbackSectionTitle, null, fallback);
    }

    private int findSectionPosition(String preferredTitle, String fallbackTitle, int fallback) {
        if(rows == null)
            return fallback;
        for(int i = 0; i < rows.size(); i++) {
            if(!(rows.get(i) instanceof Ranking))
                continue;
            SectionName name = parseSectionName(((Ranking<?>) rows.get(i)).getName());
            if(preferredTitle != null && name.title.contains(preferredTitle))
                return i;
        }
        if(fallbackTitle != null)
            for(int i = 0; i < rows.size(); i++) {
                if(!(rows.get(i) instanceof Ranking))
                    continue;
                SectionName name = parseSectionName(((Ranking<?>) rows.get(i)).getName());
                if(name.title.contains(fallbackTitle))
                    return i;
            }
        return fallback;
    }

    private int findCategoryPosition() {
        if(rows == null)
            return -1;
        for(int i = 0; i < rows.size(); i++)
            if(rows.get(i) instanceof CategoryPanel)
                return i;
        return -1;
    }

    public void showHomeRows() {
        updateRows(buildRows(dataSet, false, false));
    }

    public void showCategoryRows() {
        ensureCategoryOnlyRows();
    }

    private void ensureCategoryOnlyRows() {
        List<Object> categoryRows = new ArrayList<>();
        categoryRows.add(new CategoryPanel(categoryKey()));
        updateRows(categoryRows);
    }

    private List<Object> buildRows(List<Ranking<?>> sections, boolean includeEmpty) {
        return buildRows(sections, includeEmpty, false);
    }

    private List<Object> buildRowsForCurrentTab(boolean allowPlaceholder) {
        List<Object> nextRows = buildRows(dataSet, false);
        if(!hasDisplayContent(nextRows) && allowPlaceholder) {
            List<Object> placeholderRows = buildInitialPlaceholderRows();
            if(placeholderRows.size() > 0)
                return placeholderRows;
        }
        return nextRows;
    }

    private List<Object> buildInitialPlaceholderRows() {
        List<Object> result = new ArrayList<>();
        if(activeHomeTab == 3) {
            result.add(new CategoryPanel(categoryKey()));
            return result;
        }
        if(activeHomeTab == 0) {
            result.add(new HeroRow(new ArrayList<>()));
            result.add(new ActionStrip());
            result.add(new HomeSection("이번 주 인기", "전체보기", "", new ArrayList<>(), STYLE_RANKING));
            result.add(new HomeSection("신작 업데이트", "전체보기", "", new ArrayList<>(), STYLE_STANDARD));
            return result;
        }
        List<Object> tabRows = buildTabRows(dataSet, true, activeHomeTab == 1 ? "인기순" : freshSectionTitle());
        if(tabRows.size() > 0)
            result.addAll(tabRows);
        return result;
    }

    private Ranking<?> firstNamedSection(List<Ranking<?>> sections, String titlePart) {
        if(sections == null || titlePart == null)
            return null;
        for(Ranking<?> section : sections) {
            if(section == null)
                continue;
            SectionName name = parseSectionName(section.getName());
            if(name.title.contains(titlePart))
                return section;
        }
        return null;
    }

    private List<Object> buildRows(List<Ranking<?>> sections, boolean includeEmpty, boolean includeCategoryPanel) {
        List<Object> result = new ArrayList<>();
        if(activeHomeTab == 3) {
            result.add(new CategoryPanel(categoryKey()));
            return result;
        }
        if(activeHomeTab == 1 || activeHomeTab == 2)
            return buildTabRows(sections, includeEmpty, activeHomeTab == 1 ? "인기순" : freshSectionTitle());
        List<Object> contentRows = new ArrayList<>();
        List<Title> seedTitles = collectTitles(sections, 24);
        List<Title> recentTitles = recentTitles();
        boolean hasServerTitles = seedTitles.size() > 0;
        List<Title> heroTitles = titlesWithThumbnails(seedTitles, 5);
        if(HOME_HERO_ENABLED && heroTitles.size() > 0)
            result.add(new HeroRow(heroTitles));
        List<Title> continueTitles = recentTitles;
        if(continueTitles.size() > 0)
            result.add(new HomeSection("이어보기", "전체보기", "", continueTitles, STYLE_CONTINUE));
        SectionPick popular = findSection(sections, "인기순");
        List<Title> popularTitles = popular == null ? new ArrayList<>() : titlesFromRanking(popular.ranking, 8);
        boolean popularFeatured = popularTitles.size() > 0;
        if(popularFeatured)
            result.add(new HomeSection("이번 주 인기", "전체보기", popular == null ? "" : popular.name.path, popularTitles, STYLE_RANKING));
        SectionPick fresh = findSection(sections, freshSectionTitle());
        List<Title> freshTitles = fresh == null ? new ArrayList<>() : titlesFromRanking(fresh.ranking, 8);
        boolean freshFeatured = freshTitles.size() > 0;
        if(freshFeatured)
            result.add(new HomeSection("신작 업데이트", "전체보기", fresh == null ? "" : fresh.name.path, freshTitles, STYLE_STANDARD));
        String lastGroup = "";
        if(sections == null) {
            if(includeCategoryPanel)
                result.add(new CategoryPanel(categoryKey()));
            return result;
        }
        int extraSections = 0;
        for(Ranking<?> section : sections) {
            if(section == null)
                continue;
            if(!includeEmpty && section.size() == 0)
                continue;
            if((popularFeatured && section == popular.ranking) || (freshFeatured && section == fresh.ranking))
                continue;
            if(extraSections >= HOME_EXTRA_SECTION_LIMIT)
                break;
            SectionName name = parseSectionName(section.getName());
            if(!name.group.equals(lastGroup)) {
                contentRows.add(name.group);
                lastGroup = name.group;
            }
            contentRows.add(section);
            extraSections++;
        }
        result.addAll(contentRows);
        if(includeCategoryPanel)
            result.add(new CategoryPanel(categoryKey()));
        return result;
    }

    private String freshSectionTitle() {
        return baseMode == base_comic ? "최신" : "신작";
    }

    private List<Object> buildTabRows(List<Ranking<?>> sections, boolean includeEmpty, String titlePart) {
        List<Object> result = new ArrayList<>();
        String lastGroup = "";
        if(sections == null)
            return result;
        for(Ranking<?> section : sections) {
            if(section == null)
                continue;
            if(!includeEmpty && section.size() == 0)
                continue;
            SectionName name = parseSectionName(section.getName());
            if(!name.title.contains(titlePart))
                continue;
            if(!name.group.equals(lastGroup)) {
                result.add(name.group);
                lastGroup = name.group;
            }
            result.add(section);
        }
        return result;
    }

    private SectionName parseSectionName(String raw) {
        if(raw == null)
            return new SectionName("작품", "", "");
        String[] parts = raw.split("\\|", 3);
        if(parts.length == 1)
            return new SectionName("작품", raw, "");
        return new SectionName(parts[0], parts.length > 1 ? parts[1] : "", parts.length > 2 ? parts[2] : "");
    }

    static class SectionName {
        String group;
        String title;
        String path;
        SectionName(String group, String title, String path) {
            this.group = group;
            this.title = title;
            this.path = path;
        }
    }

    private String categoryKey() {
        return baseMode + ":" + isNtkSite();
    }

    static class CategoryPanel {
        final String key;

        CategoryPanel(String key) {
            this.key = key == null ? "" : key;
        }
    }

    static class ActionStrip {
    }

    static class HeroRow {
        List<Title> titles;
        int index = 0;

        HeroRow(List<Title> titles) {
            this.titles = titles == null ? new ArrayList<>() : new ArrayList<>(titles);
        }

        Title current() {
            if(titles == null || titles.size() == 0)
                return null;
            if(index < 0)
                index = 0;
            if(index >= titles.size())
                index = titles.size() - 1;
            return titles.get(index);
        }

        void move(int delta) {
            if(titles == null || titles.size() == 0)
                return;
            index = (index + delta + titles.size()) % titles.size();
        }
    }

    static class HomeSection {
        String title;
        String action;
        String path;
        List<Title> titles;
        int style;

        HomeSection(String title, String action, String path, List<Title> titles, int style) {
            this.title = title;
            this.action = action;
            this.path = path == null ? "" : path;
            this.titles = titles == null ? new ArrayList<>() : titles;
            this.style = style;
        }
    }

    static class SectionPick {
        Ranking<?> ranking;
        SectionName name;

        SectionPick(Ranking<?> ranking, SectionName name) {
            this.ranking = ranking;
            this.name = name;
        }
    }

    private static class SectionResult {
        int index;
        Ranking<?> ranking;

        SectionResult(int index, Ranking<?> ranking) {
            this.index = index;
            this.ranking = ranking;
        }
    }

    private static class SectionBatch {
        final List<SectionResult> results;

        SectionBatch(List<SectionResult> results) {
            this.results = results;
        }
    }

    private static class HomeSnapshot {
        long savedAt;
        List<CachedSection> sections;
    }

    private static class CachedSection {
        String name;
        List<CachedTitle> titles;
    }

    private static class CachedTitle {
        String name;
        int id;
        String thumb;
        String author;
        List<String> tags;
        String release;
        String path;
        int baseMode;

        CachedTitle() {
        }

        CachedTitle(MTitle source, int baseMode) {
            this.name = source.getName();
            this.id = source.getId();
            this.thumb = MainPageWebtoon.resolveCoverThumb(name, id, source.getThumb(), baseMode);
            this.author = source.getAuthor();
            this.tags = source.getTags();
            this.release = source.getRelease();
            this.path = source.getPath();
            this.baseMode = baseMode;
        }

        Title toTitle() {
            MTitle title = new MTitle(name, id, MainPageWebtoon.resolveCoverThumb(name, id, thumb, baseMode), author, tags, release, baseMode);
            title.setPath(path);
            return new Title(title);
        }
    }

    private String homeCacheKey() {
        return HOME_CACHE_KEY_PREFIX + (siteNtkSnapshot ? "ntk_" : "wfwf_") + baseMode;
    }

    private boolean isNtkSite() {
        return getHttpClient().isNtk();
    }

    private List<Ranking<?>> initialDataSetForSite() {
        if(siteNtkSnapshot && baseMode == base_webtoon)
            return MainPageWebtoon.getFastNtkWebtoonDataSet();
        return MainPageWebtoon.getBlankDataSet(baseMode, siteNtkSnapshot);
    }

    private boolean refreshSiteSnapshot() {
        boolean ntk = isNtkSite();
        if(ntk == siteNtkSnapshot)
            return false;
        resetForSiteChange();
        return true;
    }

    private boolean showCachedHomeRows() {
        List<Ranking<?>> cached = loadHomeSnapshot();
        if(cached == null || cached.size() == 0)
            return false;
        dataSet = cached;
        List<Object> cachedRows = buildRows(dataSet, false);
        if(!hasDisplayContent(cachedRows))
            return false;
        initialRowsShown = true;
        updateRows(cachedRows);
        if(hasHero(cachedRows))
            scrollHeroToTop();
        scheduleThumbnailPreload(dataSet);
        return true;
    }

    private void loadCachedHomeRowsAsync() {
        if(cacheLoadInFlight)
            return;
        cacheLoadInFlight = true;
        final boolean ntk = siteNtkSnapshot;
        final String cacheKey = homeCacheKey();
        AppDispatchers.submitIo(() -> {
            List<Ranking<?>> cached = loadHomeSnapshot(cacheKey);
            AppDispatchers.runOnMain(() -> {
                cacheLoadInFlight = false;
                if(cached != null && cached.size() > 0 && ntk == siteNtkSnapshot)
                    applyCachedHomeRows(cached);
            });
        });
    }

    private void applyCachedHomeRows(List<Ranking<?>> cached) {
        if(cached == null || cached.size() == 0)
            return;
        if(fetcher != null && hasCompleteHomeSections(dataSet))
            return;
        dataSet = cached;
        List<Object> cachedRows = buildRows(dataSet, false);
        if(!hasDisplayContent(cachedRows))
            return;
        initialRowsShown = true;
        updateRows(cachedRows);
        if(hasHero(cachedRows))
            scrollHeroToTop();
        scheduleThumbnailPreload(dataSet);
    }

    private List<Ranking<?>> loadHomeSnapshot() {
        return loadHomeSnapshot(homeCacheKey());
    }

    private List<Ranking<?>> loadHomeSnapshot(String cacheKey) {
        try {
            String json = CacheFileStore.read(context, cacheKey);
            if(json == null || json.length() == 0) {
                json = p.getSharedPref().getString(cacheKey, "");
                if(json != null && json.length() > 0)
                    CacheFileStore.write(context, cacheKey, json);
            }
            if(json == null || json.length() == 0)
                return null;
            HomeSnapshot snapshot = new Gson().fromJson(json, new TypeToken<HomeSnapshot>(){}.getType());
            if(snapshot == null || snapshot.sections == null)
                return null;
            ArrayList<Ranking<?>> restored = new ArrayList<>();
            for(CachedSection cachedSection : snapshot.sections) {
                if(cachedSection == null || cachedSection.name == null || cachedSection.titles == null || cachedSection.titles.size() == 0)
                    continue;
                Ranking<Title> ranking = new Ranking<>(cachedSection.name);
                for(CachedTitle item : cachedSection.titles) {
                    if(item == null || item.id <= 0)
                        continue;
                    ranking.add(item.toTitle());
                }
                if(ranking.size() > 0)
                    restored.add(ranking);
            }
            return canUseHomeSnapshot(restored) ? restored : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void saveHomeSnapshot(List<Ranking<?>> sections) {
        try {
            if(!canUseHomeSnapshot(sections))
                return;
            HomeSnapshot snapshot = new HomeSnapshot();
            snapshot.savedAt = System.currentTimeMillis();
            snapshot.sections = new ArrayList<>();
            int sectionCount = 0;
            for(Ranking<?> section : sections) {
                if(section == null || section.size() == 0)
                    continue;
                if(!shouldKeepHomeCacheSection(section, sectionCount))
                    continue;
                CachedSection cachedSection = new CachedSection();
                cachedSection.name = section.getName();
                cachedSection.titles = new ArrayList<>();
                for(Object item : section) {
                    if(!(item instanceof MTitle))
                        continue;
                    MTitle title = ((MTitle) item).clone();
                    if(title.getId() <= 0)
                        continue;
                    title.setBaseMode(baseMode);
                    cachedSection.titles.add(new CachedTitle(title, baseMode));
                    if(cachedSection.titles.size() >= HOME_CACHE_MAX_TITLES_PER_SECTION)
                        break;
                }
                if(cachedSection.titles.size() > 0) {
                    snapshot.sections.add(cachedSection);
                    sectionCount++;
                }
                if(sectionCount >= HOME_CACHE_MAX_SECTIONS)
                    break;
            }
            if(snapshot.sections.size() == 0)
                return;
            CacheFileStore.write(context, homeCacheKey(), new Gson().toJson(snapshot));
        } catch (Exception ignored) {
        }
    }

    private boolean shouldKeepHomeCacheSection(Ranking<?> section, int keptSections) {
        if(siteNtkSnapshot)
            return true;
        if(keptSections < 2)
            return true;
        SectionName name = parseSectionName(section.getName());
        return name.title.contains("인기순") || name.title.contains(freshSectionTitle());
    }

    private boolean canUseHomeSnapshot(List<Ranking<?>> sections) {
        if(siteNtkSnapshot)
            return collectTitles(sections, 1).size() > 0;
        return hasCompleteHomeSections(sections);
    }

    private boolean hasRequiredHomeSections(List<Ranking<?>> sections) {
        if(sections == null)
            return false;
        return hasMatchingSectionTitles(sections, "인기순")
                && hasMatchingSectionTitles(sections, freshSectionTitle());
    }

    private boolean hasCompleteHomeSections(List<Ranking<?>> sections) {
        return collectTitles(sections, 1).size() > 0 && hasRequiredHomeSections(sections);
    }

    private boolean hasMatchingSectionTitles(List<Ranking<?>> sections, String titlePart) {
        if(sections == null || titlePart == null)
            return false;
        for(Ranking<?> section : sections) {
            if(section == null)
                continue;
            SectionName name = parseSectionName(section.getName());
            if(name.title.contains(titlePart) && titlesFromRanking(section, 1).size() > 0)
                return true;
        }
        return false;
    }

    private Ranking<?> findRanking(List<Ranking<?>> sections, String titlePart) {
        if(sections == null || titlePart == null)
            return null;
        for(Ranking<?> section : sections) {
            if(section == null)
                continue;
            SectionName name = parseSectionName(section.getName());
            if(name.title.contains(titlePart))
                return section;
        }
        return null;
    }

    private List<Title> collectTitles(List<Ranking<?>> sections, int limit) {
        ArrayList<Title> titles = new ArrayList<>();
        if(sections == null)
            return titles;
        for(Ranking<?> section : sections) {
            appendTitles(section, titles, limit);
            if(titles.size() >= limit)
                break;
        }
        return titles;
    }

    private List<Title> titlesWithThumbnails(List<Title> source, int limit) {
        ArrayList<Title> titles = new ArrayList<>();
        if(source == null)
            return titles;
        for(Title title : source) {
            if(title == null)
                continue;
            String thumb = title.getThumb();
            if(thumb == null || thumb.trim().length() == 0)
                continue;
            titles.add(title);
            if(limit > 0 && titles.size() >= limit)
                break;
        }
        return titles;
    }

    private List<Title> titlesFromRanking(Ranking<?> ranking, int limit) {
        ArrayList<Title> titles = new ArrayList<>();
        appendTitles(ranking, titles, limit);
        return titles;
    }

    private List<Title> recentTitles() {
        ArrayList<Title> titles = new ArrayList<>();
        try {
            appendRecentTitlesForCurrentMode(titles, 6);
            if(titles.size() == 0)
                appendRecentTitlesForAnyMode(titles, 6);
        } catch (Exception ignored) {
        }
        for(Title title : titles) {
            int bookmark = p.getBookmark(title);
            if(bookmark > 0)
                title.setBookmark(bookmark);
        }
        return titles;
    }

    private void appendRecentTitlesForCurrentMode(List<Title> target, int limit) {
        List<MTitle> recent = Utils.snapshotList(p.getRecent());
        if(recent == null || target == null)
            return;
        for(MTitle item : recent) {
            if(item == null || item.getBaseMode() != baseMode)
                continue;
            Title title = item instanceof Title ? (Title) item : new Title(item);
            if(containsTitle(target, title))
                continue;
            target.add(title);
            if(limit > 0 && target.size() >= limit)
                return;
        }
    }

    private void appendRecentTitlesForAnyMode(List<Title> target, int limit) {
        List<MTitle> recent = Utils.snapshotList(p.getRecent());
        if(recent == null || target == null)
            return;
        for(MTitle item : recent) {
            if(item == null)
                continue;
            Title title = item instanceof Title ? (Title) item : new Title(item);
            if(containsTitle(target, title))
                continue;
            target.add(title);
            if(limit > 0 && target.size() >= limit)
                return;
        }
    }

    private List<Title> mergeTitles(List<Title> primary, List<Title> secondary, int limit) {
        ArrayList<Title> titles = new ArrayList<>();
        appendTitles(primary, titles, limit);
        appendTitles(secondary, titles, limit);
        return titles;
    }

    private String firstSectionPath(List<Ranking<?>> sections) {
        if(sections == null)
            return "";
        for(Ranking<?> section : sections) {
            if(section == null || section.size() == 0)
                continue;
            return parseSectionName(section.getName()).path;
        }
        return "";
    }

    private void appendTitles(List<?> source, List<Title> target, int limit) {
        if(source == null || target == null)
            return;
        for(Object item : source) {
            if(!(item instanceof MTitle))
                continue;
            Title title = item instanceof Title ? (Title) item : new Title((MTitle) item);
            if(containsTitle(target, title))
                continue;
            target.add(title);
            if(limit > 0 && target.size() >= limit)
                return;
        }
    }

    private boolean containsTitle(List<Title> titles, Title target) {
        if(titles == null || target == null)
            return false;
        for(Title title : titles) {
            if(title == null)
                continue;
            if(title.getBaseMode() == target.getBaseMode() && title.getId() == target.getId())
                return true;
            if(title.getName() != null && title.getName().equals(target.getName()))
                return true;
        }
        return false;
    }

    private SectionPick findSection(List<Ranking<?>> sections, String titlePart) {
        if(sections == null || titlePart == null)
            return null;
        for(Ranking<?> section : sections) {
            if(section == null || section.size() == 0)
                continue;
            SectionName name = parseSectionName(section.getName());
            if(name.title.contains(titlePart))
                return new SectionPick(section, name);
        }
        return null;
    }

    private void bindTitleThumb(ImageView thumbView, Title title, int widthDp, int heightDp) {
        if(title == null || save || title.getThumb() == null || title.getThumb().length() == 0) {
            bindStaticThumb(thumbView, "placeholder", R.drawable.app_cover_placeholder);
            return;
        }
        Object source = getGlideUrl(title.getThumb(), title.getBaseMode());
        bindGlideThumb(thumbView, source, widthDp, heightDp, R.drawable.app_cover_placeholder);
    }

    private void bindGlideThumb(ImageView thumbView, Object source, int widthDp, int heightDp, int placeholderRes) {
        String key = String.valueOf(source);
        if(key.equals(thumbView.getTag()))
            return;
        Glide.with(thumbView).clear(thumbView);
        thumbView.setTag(key);
        Glide.with(thumbView)
                .load(source)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .override(dp(widthDp), dp(heightDp))
                .thumbnail(0.25f)
                .dontAnimate()
                .placeholder(placeholderRes)
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                        return false;
                    }
                })
                .into(thumbView);
    }

    private void bindStaticThumb(ImageView thumbView, String key, int resId) {
        if(key.equals(thumbView.getTag()))
            return;
        Glide.with(thumbView).clear(thumbView);
        thumbView.setTag(key);
        thumbView.setImageResource(resId);
    }

    class HeroHolder extends RecyclerView.ViewHolder {
        View card;
        ImageView thumb;
        TextView title;
        TextView badge;
        TextView meta;
        TextView dots;
        TextView readButton;
        float downX;
        float downY;
        boolean dragging;
        boolean animating;

        HeroHolder(View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.hero_card);
            thumb = itemView.findViewById(R.id.hero_thumb);
            title = itemView.findViewById(R.id.hero_title);
            badge = itemView.findViewById(R.id.hero_badge);
            meta = itemView.findViewById(R.id.hero_meta);
            dots = itemView.findViewById(R.id.hero_dots);
            readButton = itemView.findViewById(R.id.hero_read_button);
        }

        void bind(HeroRow row) {
            bindHero(row);
            card.setOnClickListener(v -> {
                Title hero = row == null ? null : row.current();
                if(listener != null && hero != null)
                    listener.clickedTitle(hero);
            });
            readButton.setOnClickListener(v -> {
                Title hero = row == null ? null : row.current();
                if(listener != null && hero != null)
                    listener.clickedTitle(hero);
            });
            card.setOnTouchListener((v, event) -> {
                if(animating)
                    return true;
                if(row == null || row.titles == null || row.titles.size() < 2)
                    return false;
                switch(event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getX();
                        downY = event.getY();
                        dragging = false;
                        card.animate().cancel();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float moveDx = event.getX() - downX;
                        float moveDy = event.getY() - downY;
                        if(!dragging && Math.abs(moveDy) > dp(10) && Math.abs(moveDy) > Math.abs(moveDx)) {
                            v.getParent().requestDisallowInterceptTouchEvent(false);
                            resetDrag();
                            return false;
                        }
                        if(Math.abs(moveDx) > dp(8) && Math.abs(moveDx) > Math.abs(moveDy)) {
                            dragging = true;
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                            float resistance = Math.max(-dp(44), Math.min(dp(44), moveDx * 0.22f));
                            card.setTranslationX(resistance);
                            card.setScaleX(0.992f);
                            card.setScaleY(0.992f);
                            card.setAlpha(0.94f);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        float dx = event.getX() - downX;
                        float dy = event.getY() - downY;
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                        if(Math.abs(dx) > dp(48) && Math.abs(dx) > Math.abs(dy)) {
                            animateHeroChange(row, dx < 0 ? 1 : -1);
                        } else if(Math.abs(dx) < dp(8) && Math.abs(dy) < dp(8)) {
                            v.performClick();
                        } else {
                            resetDrag();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                        resetDrag();
                        return true;
                }
                return false;
            });
        }

        private void animateHeroChange(HeroRow row, int direction) {
            animating = true;
            dragging = false;
            float width = card.getWidth() > 0 ? card.getWidth() : dp(320);
            float outX = direction > 0 ? -width * 0.32f : width * 0.32f;
            float inX = -outX;
            dots.animate().alpha(0.35f).setDuration(90).start();
            card.animate()
                    .translationX(outX)
                    .alpha(0f)
                    .scaleX(0.985f)
                    .scaleY(0.985f)
                    .setDuration(130)
                    .withEndAction(() -> {
                        row.move(direction);
                        bindHero(row);
                        card.setTranslationX(inX);
                        card.setAlpha(0f);
                        card.setScaleX(0.985f);
                        card.setScaleY(0.985f);
                        card.animate()
                                .translationX(0f)
                                .alpha(1f)
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(180)
                                .withEndAction(() -> {
                                    animating = false;
                                    dots.animate().alpha(1f).setDuration(90).start();
                                })
                                .start();
                    })
                    .start();
        }

        private void resetDrag() {
            dragging = false;
            card.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(140)
                    .start();
        }

        private void bindHero(HeroRow row) {
            Title hero = row == null ? null : row.current();
            title.setText(hero == null ? "" : hero.getName());
            String release = hero == null ? "" : hero.getRelease();
            meta.setText(release == null || release.length() == 0 ? "지금 볼만한 추천 작품" : release);
            badge.setText("추천");
            bindTitleThumb(thumb, hero, 240, 140);
            dots.setText(heroDots(row));
        }

        private String heroDots(HeroRow row) {
            if(row == null || row.titles == null || row.titles.size() <= 1)
                return "";
            StringBuilder builder = new StringBuilder();
            int count = Math.min(5, row.titles.size());
            int selected = row.index % count;
            for(int i = 0; i < count; i++) {
                if(i > 0)
                    builder.append("  ");
                builder.append(i == selected ? "●" : "○");
            }
            return builder.toString();
        }
    }

    class HomeSectionHolder extends RecyclerView.ViewHolder {
        TextView title;
        TextView action;
        RecyclerView list;
        float continueDownX;
        float continueDownY;
        boolean continueTouchMoved;

        HomeSectionHolder(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.webtoon_section_title);
            action = itemView.findViewById(R.id.webtoon_section_count);
            list = itemView.findViewById(R.id.webtoon_section_list);
            LinearLayoutManager manager = new NpaLinearLayoutManager(context);
            manager.setOrientation(RecyclerView.HORIZONTAL);
            list.setLayoutManager(manager);
            list.setNestedScrollingEnabled(false);
            list.setHasFixedSize(true);
            list.setOverScrollMode(View.OVER_SCROLL_NEVER);
            list.setItemAnimator(null);
            list.setRecycledViewPool(sharedHomePool);
            list.setItemViewCacheSize(6);
            manager.setInitialPrefetchItemCount(0);
        }

        void bind(HomeSection section) {
            setTextIfChanged(title, section.title);
            setTextIfChanged(action, section.action);
            boolean hasAction = section.path.length() > 0;
            setVisibilityIfChanged(action, hasAction ? View.VISIBLE : View.INVISIBLE);
            action.setOnClickListener(v -> {
                if(listener != null && section.path.length() > 0)
                    listener.clickedCategoryPath(section.title, section.path);
            });
            ViewGroup.LayoutParams params = list.getLayoutParams();
            int targetHeight = section.style == STYLE_RANKING ? dp(222) : dp(238);
            if(params.height != targetHeight) {
                params.height = targetHeight;
                list.setLayoutParams(params);
            }
            RecyclerView.Adapter adapter = list.getAdapter();
            if(adapter instanceof HomeTitleAdapter && ((HomeTitleAdapter) adapter).style == section.style)
                ((HomeTitleAdapter) adapter).setItems(section.titles);
            else
                list.setAdapter(new HomeTitleAdapter(section.titles, section.style));
            if(section.style == STYLE_CONTINUE)
                list.setOnTouchListener((v, event) -> handleContinueSectionTouch(event));
            else
                list.setOnTouchListener(null);
        }

        private boolean handleContinueSectionTouch(MotionEvent event) {
            RecyclerView.Adapter adapter = list.getAdapter();
            if(!(adapter instanceof HomeTitleAdapter))
                return false;
            HomeTitleAdapter homeAdapter = (HomeTitleAdapter) adapter;
            switch(event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    continueDownX = event.getX();
                    continueDownY = event.getY();
                    continueTouchMoved = false;
                    homeAdapter.warmupContinueAt(list, continueDownX, continueDownY);
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if(Math.abs(event.getX() - continueDownX) > dp(14) || Math.abs(event.getY() - continueDownY) > dp(14))
                        continueTouchMoved = true;
                    return false;
                case MotionEvent.ACTION_UP:
                    return false;
                case MotionEvent.ACTION_CANCEL:
                    continueTouchMoved = false;
                    return false;
            }
            return false;
        }
    }

    class HomeTitleAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        List<Title> items;
        int style;
        String itemsKey;

        HomeTitleAdapter(List<Title> items, int style) {
            this.items = items == null ? new ArrayList<>() : items;
            this.style = style;
            this.itemsKey = titleListKey(this.items);
            setHasStableIds(true);
        }

        void setItems(List<Title> items) {
            List<Title> next = items == null ? new ArrayList<>() : new ArrayList<>(items);
            String nextKey = titleListKey(next);
            if(nextKey.equals(itemsKey))
                return;
            this.items = next;
            this.itemsKey = nextKey;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if(viewType == VIEW_TYPE_HOME_RANKING)
                return new RankHolder(inflater.inflate(R.layout.item_home_rank_card, parent, false));
            return new ContinueHolder(inflater.inflate(R.layout.item_home_continue_card, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Title item = position < items.size() ? items.get(position) : null;
            if(holder instanceof RankHolder)
                ((RankHolder) holder).bind(item, position);
            else
                ((ContinueHolder) holder).bind(item, position);
        }

        @Override
        public int getItemCount() {
            int realCount = items == null ? 0 : items.size();
            return Math.min(realCount, 4);
        }

        @Override
        public int getItemViewType(int position) {
            if(style == STYLE_RANKING)
                return VIEW_TYPE_HOME_RANKING;
            if(style == STYLE_STANDARD)
                return VIEW_TYPE_HOME_STANDARD;
            return VIEW_TYPE_HOME_CONTINUE;
        }

        @Override
        public long getItemId(int position) {
            if(items != null && position < items.size()) {
                Title item = items.get(position);
                return (((long) item.getBaseMode()) << 32) ^ item.getId();
            }
            return -1000L - position - style * 100L;
        }

        class ContinueHolder extends RecyclerView.ViewHolder {
            View card;
            ImageView thumb;
            ImageView siteIcon;
            TextView name;
            TextView episode;
            TextView percent;
            android.widget.ProgressBar progress;
            String boundKey;

            ContinueHolder(View itemView) {
                super(itemView);
                card = itemView.findViewById(R.id.home_continue_card);
                thumb = itemView.findViewById(R.id.home_continue_thumb);
                siteIcon = itemView.findViewById(R.id.home_continue_site_icon);
                name = itemView.findViewById(R.id.home_continue_title);
                episode = itemView.findViewById(R.id.home_continue_episode);
                percent = itemView.findViewById(R.id.home_continue_percent);
                progress = itemView.findViewById(R.id.home_continue_progress);
            }

            void bind(Title item, int position) {
                boolean continueStyle = style == STYLE_CONTINUE;
                int progressPercent = continueStyle ? readingProgressPercent(item) : 0;
                String sourceSite = continueStyle ? sourceSiteForContinueItem(item) : "";
                String nextKey = titleContentKey(item) + ":" + continueStyle + ":" + progressPercent + ":" + sourceSite;
                if(!nextKey.equals(boundKey)) {
                    setTextIfChanged(name, item == null ? "" : item.getName());
                    setVisibilityIfChanged(episode, continueStyle ? View.VISIBLE : View.GONE);
                    setVisibilityIfChanged(siteIcon, continueStyle ? View.VISIBLE : View.GONE);
                    setVisibilityIfChanged(progress, continueStyle ? View.VISIBLE : View.GONE);
                    setVisibilityIfChanged(percent, continueStyle ? View.VISIBLE : View.GONE);
                    if(continueStyle) {
                        setTextIfChanged(episode, progressLabel(item));
                        bindContinueSiteIcon(siteIcon, sourceSite);
                        if(progress.getProgress() != progressPercent)
                            progress.setProgress(progressPercent);
                        setTextIfChanged(percent, progressPercent + "%");
                    }
                    boundKey = nextKey;
                }
                bindTitleThumb(thumb, item, 120, 112);
                card.setOnClickListener(v -> {
                    openContinueOrTitle(item, continueStyle);
                });
                card.setOnTouchListener((v, event) -> {
                    if(!continueStyle || item == null)
                        return false;
                    if(event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        warmupContinueViewer(item);
                    }
                    return false;
                });
                card.setOnLongClickListener(v -> {
                    if(!continueStyle || listener == null || item == null)
                        return false;
                    listener.longClickedContinue(v, item);
                    return true;
                });
            }
        }

        private void bindContinueSiteIcon(ImageView view, String sourceSite) {
            boolean ntk = "ntk".equals(sourceSite);
            view.setImageResource(ntk ? R.drawable.ic_site_ntk : R.drawable.ic_site_wfwf);
            view.setContentDescription(ntk ? "NTK" : "WFWF");
        }

        private String sourceSiteForContinueItem(Title item) {
            if(item == null || p == null)
                return "wfwf";
            String source = item.getSourceSite();
            if(source == null || source.length() == 0)
                source = p.resolveKnownSourceSite(item);
            return "ntk".equals(source) ? "ntk" : "wfwf";
        }

        private void warmupContinueViewer(Title item) {
            if(item == null)
                return;
            Manga manga = resolveContinueManga(item);
            if(manga == null)
                return;
            ViewerWarmupManager.warmupContinueImmediate(context, manga, item);
        }

        void warmupContinueAt(RecyclerView recyclerView, float x, float y) {
            if(style != STYLE_CONTINUE)
                return;
            warmupContinueViewer(continueItemAt(recyclerView, x, y));
        }

        private Title continueItemAt(RecyclerView recyclerView, float x, float y) {
            if(items == null || items.size() == 0)
                return null;
            View child = recyclerView == null ? null : recyclerView.findChildViewUnder(x, y);
            if(child != null) {
                int position = recyclerView.getChildAdapterPosition(child);
                if(position >= 0 && position < items.size())
                    return items.get(position);
            }
            return items.get(0);
        }

        private void openContinueOrTitle(Title item, boolean continueStyle) {
            if(listener == null || item == null)
                return;
            Manga manga = continueStyle ? resolveContinueManga(item) : null;
            if(manga != null) {
                if(!markContinueOpen(item))
                    return;
                ViewerWarmupManager.warmupContinueImmediate(context, manga, item);
                listener.clickedManga(manga);
            } else {
                listener.clickedTitle(item);
            }
        }

        private Manga resolveContinueManga(Title item) {
            if(item == null)
                return null;
            p.ensureSourceSiteForTitle(item);
            int bookmark = p.getBookmark(item);
            if(bookmark <= 0)
                bookmark = item.getBookmark();
            if(bookmark <= 0)
                bookmark = item.getBookmarkEpisodeId();
            if(bookmark <= 0)
                return null;

            List<Manga> eps = snapshotEpisodes(item);
            Manga resolved = findEpisodeById(eps, bookmark);
            if(resolved == null) {
                int progressIndex = item.getBookmarkEpisodeIndex();
                if(progressIndex <= 0 && bookmark > 0 && eps != null && bookmark <= eps.size())
                    progressIndex = bookmark;
                resolved = episodeAt(eps, progressIndex);
            }
            if(resolved == null)
                resolved = new Manga(bookmark, "", "", item.getBaseMode());
            resolved.setTitle(item);
            resolved.setTitleId(item.getId());
            if(eps != null && eps.size() > 0)
                resolved.setEps(eps);
            return resolved;
        }

        private Manga findEpisodeById(List<Manga> eps, int bookmark) {
            if(eps == null)
                return null;
            for(Manga episode : eps)
                if(episode != null && episode.getId() == bookmark)
                    return episode;
            return null;
        }

        private Manga episodeAt(List<Manga> eps, int oneBasedIndex) {
            if(eps == null || oneBasedIndex <= 0 || oneBasedIndex > eps.size())
                return null;
            return eps.get(oneBasedIndex - 1);
        }

        private int readingProgressPercent(Title item) {
            if(item == null)
                return 0;
            int watchedCount = watchedEpisodeCount(item);
            int episodeCount = totalEpisodeCount(item);
            if(watchedCount > 0 && episodeCount > 0) {
                if(watchedCount >= episodeCount)
                    return 100;
                return Math.max(1, Math.min(99, (int) Math.floor(watchedCount * 100f / episodeCount)));
            }
            return 0;
        }

        private String progressLabel(Title item) {
            if(item == null)
                return "이어보기";
            int watchedCount = watchedEpisodeCount(item);
            int episodeCount = totalEpisodeCount(item);
            if(watchedCount > 0 && episodeCount > 0)
                return watchedCount + "/" + episodeCount + "화";
            if(item.getBookmarkEpisodeId() > 0 || item.getBookmark() > 0 || p.getBookmark(item) > 0)
                return "확인 중";
            return "이어보기";
        }

        private int watchedEpisodeCount(Title item) {
            int episodeIndex = item.getBookmarkEpisodeIndex();
            int episodeCount = totalEpisodeCount(item);
            if(episodeIndex <= 0)
                episodeIndex = item.getBookmarkIndex();
            if(episodeIndex <= 0 || episodeCount <= 0)
                return 0;
            return Math.max(1, Math.min(episodeCount, episodeCount - episodeIndex + 1));
        }

        private int totalEpisodeCount(Title item) {
            int episodeCount = item.getEpisodeCount();
            if(episodeCount <= 0)
                episodeCount = item.getEpsCount();
            return episodeCount;
        }

        class RankHolder extends RecyclerView.ViewHolder {
            View card;
            ImageView thumb;
            TextView index;
            TextView title;
            TextView meta;
            String boundKey;

            RankHolder(View itemView) {
                super(itemView);
                card = itemView.findViewById(R.id.home_rank_card);
                thumb = itemView.findViewById(R.id.home_rank_thumb);
                index = itemView.findViewById(R.id.home_rank_index);
                title = itemView.findViewById(R.id.home_rank_title);
                meta = itemView.findViewById(R.id.home_rank_meta);
            }

            void bind(Title item, int position) {
                String release = item == null ? "" : item.getRelease();
                String nextKey = titleContentKey(item) + ":" + position;
                if(!nextKey.equals(boundKey)) {
                    setTextIfChanged(index, String.valueOf(position + 1));
                    setTextIfChanged(title, item == null ? "" : item.getName());
                    setTextIfChanged(meta, release == null || release.length() == 0 ? "인기 작품" : release);
                    boundKey = nextKey;
                }
                bindTitleThumb(thumb, item, 112, 110);
                card.setOnClickListener(v -> {
                    if(listener != null && item != null)
                        listener.clickedTitle(item);
                });
                title.setOnClickListener(v -> {
                    if(listener != null && item != null)
                        listener.clickedTitle(item);
                });
            }
        }
    }

    private boolean markContinueOpen(Title item) {
        String key = titleKey(item) + ":" + (item == null ? 0 : item.getBookmark()) + ":" + (item == null ? 0 : item.getBookmarkEpisodeId());
        long now = System.currentTimeMillis();
        if(key.equals(lastContinueOpenKey) && now - lastContinueOpenAt < 700)
            return false;
        lastContinueOpenKey = key;
        lastContinueOpenAt = now;
        return true;
    }

    private boolean handleAnchorContinueTouch(View view, MotionEvent event) {
        if(listener == null || activeHomeTab != 0 || !isLikelyContinueTapZone(view, event.getY()))
            return false;
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                anchorDownX = event.getX();
                anchorDownY = event.getY();
                anchorTouchMoved = false;
                warmupFirstContinueTitle();
                return false;
            case MotionEvent.ACTION_MOVE:
                if(Math.abs(event.getX() - anchorDownX) > dp(14) || Math.abs(event.getY() - anchorDownY) > dp(14))
                    anchorTouchMoved = true;
                return false;
            case MotionEvent.ACTION_UP:
                return false;
            case MotionEvent.ACTION_CANCEL:
                anchorTouchMoved = false;
                return false;
        }
        return false;
    }

    private boolean isLikelyContinueTapZone(View view, float y) {
        if(view == null || view.getHeight() <= 0)
            return false;
        return y >= Math.max(dp(700), view.getHeight() * 0.68f);
    }

    private void warmupFirstContinueTitle() {
        Title item = firstContinueTitle();
        Manga manga = resolveContinueMangaForWarmup(item);
        if(manga != null)
            ViewerWarmupManager.warmupContinueImmediate(context, manga, item);
    }

    private Title firstContinueTitle() {
        if(rows != null) {
            for(Object row : rows) {
                if(!(row instanceof HomeSection))
                    continue;
                HomeSection section = (HomeSection) row;
                if(!"이어보기".equals(section.title) || section.titles == null || section.titles.size() == 0)
                    continue;
                return section.titles.get(0);
            }
        }
        List<MTitle> recent = Utils.snapshotList(p.getRecent());
        if(recent == null || recent.size() == 0)
            return null;
        for(MTitle item : recent) {
            if(item == null || item.getId() <= 0)
                continue;
            Title title = item instanceof Title ? (Title) item : new Title(item);
            int bookmark = p.getBookmark(title);
            if(bookmark <= 0)
                bookmark = title.getBookmark();
            if(bookmark <= 0)
                bookmark = item.getBookmarkEpisodeId();
            if(bookmark <= 0)
                continue;
            title.setBookmark(bookmark);
            return title;
        }
        return null;
    }

    private List<Manga> snapshotEpisodes(Title item) {
        return Utils.snapshotEpisodes(item);
    }

    class ActionStripHolder extends RecyclerView.ViewHolder {
        View updates;
        View bookmarks;
        View downloads;
        View genres;

        ActionStripHolder(View itemView) {
            super(itemView);
            updates = itemView.findViewById(R.id.home_action_updates);
            bookmarks = itemView.findViewById(R.id.home_action_bookmarks);
            downloads = itemView.findViewById(R.id.home_action_downloads);
            genres = itemView.findViewById(R.id.home_action_genres);
        }

        void bind() {
            updates.setOnClickListener(v -> sendHomeAction(ACTION_UPDATES));
            bookmarks.setOnClickListener(v -> sendHomeAction(ACTION_BOOKMARKS));
            downloads.setOnClickListener(v -> sendHomeAction(ACTION_DOWNLOADS));
            genres.setOnClickListener(v -> sendHomeAction(ACTION_GENRES));
        }

        void sendHomeAction(int action) {
            if(listener != null)
                listener.clickedHomeAction(action);
        }
    }

    class CategoryHolder extends RecyclerView.ViewHolder {
        ViewGroup filterSections;
        ViewGroup statusFilters;
        ViewGroup genreFilters;

        CategoryHolder(View itemView) {
            super(itemView);
            filterSections = itemView.findViewById(R.id.webtoon_filter_sections);
            statusFilters = itemView.findViewById(R.id.webtoon_status_filters);
            genreFilters = itemView.findViewById(R.id.webtoon_genre_filters);
        }

        void bind() {
            Object tag = filterSections.getTag();
            String tagKey = baseMode + ":" + isNtkSite();
            if(tagKey.equals(tag) && filterSections.getChildCount() > 0)
                return;
            filterSections.setTag(tagKey);
            statusFilters.setVisibility(View.GONE);
            genreFilters.setVisibility(View.GONE);
            filterSections.removeAllViews();
            bindFilters(filterGroupsForCurrentSite());
        }

        String[][] filterGroupsForCurrentSite() {
            if(isNtkSite())
                return baseMode == base_comic ? MainPageWebtoon.NTK_COMIC_FILTER_GROUPS : MainPageWebtoon.NTK_WEBTOON_FILTER_GROUPS;
            return baseMode == base_comic ? MainPageWebtoon.COMIC_FILTER_GROUPS : MainPageWebtoon.WEBTOON_FILTER_GROUPS;
        }

        void bindFilters(String[][] groups) {
            for(String[] group : groups) {
                if(group.length == 0)
                    continue;
                SectionName groupName = parseSectionName(group[0]);
                if(baseMode == base_webtoon && groupName.group.startsWith("완결"))
                    continue;
                String displayGroup = groupName.group;
                if(baseMode == base_webtoon && displayGroup.startsWith("연재 "))
                    displayGroup = displayGroup.substring(3);

                LinearLayout groupBlock = new LinearLayout(context);
                groupBlock.setOrientation(LinearLayout.VERTICAL);
                groupBlock.setPadding(0, dp(6), 0, dp(10));
                filterSections.addView(groupBlock, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                TextView label = new TextView(context);
                label.setText(displayGroup);
                label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                label.setTextColor(ContextCompat.getColor(context, dark ? android.R.color.white : R.color.appText));
                label.setTextSize(13);
                label.setSingleLine(true);
                label.setGravity(Gravity.CENTER_VERTICAL);
                groupBlock.addView(label, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));
                addFilterChipGrid(groupBlock, group);
            }
        }

        void addFilterChipGrid(LinearLayout groupBlock, String[] items) {
            LinearLayout row = null;
            int column = 0;
            for(String item : items) {
                if(column == 0) {
                    row = new LinearLayout(context);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    groupBlock.addView(row, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
                }
                SectionName filter = parseSectionName(item);
                TextView chip = createFilterChip(filter.title, clicked -> {
                    if(listener != null)
                        listener.clickedCategoryPath(filter.title, filter.path);
                });
                row.addView(chip, chipGridParams(column));
                column++;
                if(column == 3)
                    column = 0;
            }
            while(row != null && column > 0 && column < 3) {
                View spacer = new View(context);
                row.addView(spacer, chipGridParams(column));
                column++;
            }
        }

        LinearLayout.LayoutParams chipGridParams(int column) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(34), 1f);
            params.setMargins(0, 0, column == 2 ? 0 : dp(8), dp(6));
            return params;
        }

        TextView createFilterChip(String label, FilterClick click) {
            TextView chip = new TextView(context);
            chip.setText(label);
            chip.setSingleLine(true);
            chip.setGravity(Gravity.CENTER);
            chip.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            chip.setTextSize(13);
            chip.setTextColor(ContextCompat.getColor(context, dark ? android.R.color.white : R.color.appText));
            chip.setPadding(dp(12), 0, dp(12), 0);
            chip.setBackground(filterBackground());
            chip.setOnClickListener(v -> click.onClick(label));
            return chip;
        }

        GradientDrawable filterBackground() {
            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(dp(8));
            background.setColor(ContextCompat.getColor(context, dark ? R.color.colorDarkBackground : R.color.appCard));
            background.setStroke(dp(1), ContextCompat.getColor(context, dark ? R.color.colorDarkWindowBackground : R.color.appDivider));
            return background;
        }
    }

    interface FilterClick {
        void onClick(String label);
    }

    int dp(int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    private void setTextIfChanged(TextView view, CharSequence text) {
        CharSequence next = text == null ? "" : text;
        if(!TextUtils.equals(view.getText(), next))
            view.setText(next);
    }

    private void setVisibilityIfChanged(View view, int visibility) {
        if(view.getVisibility() != visibility)
            view.setVisibility(visibility);
    }

    class GroupHolder extends RecyclerView.ViewHolder {
        TextView title;

        GroupHolder(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.webtoon_group_title);
        }

        void bind(String group) {
            setTextIfChanged(title, group);
        }
    }

    class SectionHolder extends RecyclerView.ViewHolder {
        TextView title;
        TextView count;
        RecyclerView list;

        SectionHolder(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.webtoon_section_title);
            count = itemView.findViewById(R.id.webtoon_section_count);
            list = itemView.findViewById(R.id.webtoon_section_list);
            LinearLayoutManager manager = new NpaLinearLayoutManager(context);
            manager.setOrientation(RecyclerView.HORIZONTAL);
            list.setLayoutManager(manager);
            list.setNestedScrollingEnabled(false);
            list.setHasFixedSize(true);
            list.setOverScrollMode(View.OVER_SCROLL_NEVER);
            list.setItemAnimator(null);
            list.setRecycledViewPool(sharedHomePool);
            list.setItemViewCacheSize(6);
            manager.setInitialPrefetchItemCount(0);
        }

        void bind(Ranking<?> section) {
            SectionName name = parseSectionName(section.getName());
            setTextIfChanged(title, name.title);
            setTextIfChanged(count, "전체보기");
            boolean hasAction = name.path.length() > 0;
            setVisibilityIfChanged(count, hasAction ? View.VISIBLE : View.INVISIBLE);
            count.setOnClickListener(v -> {
                if(listener != null && name.path.length() > 0)
                    listener.clickedCategoryPath(name.title, name.path);
            });
            RecyclerView.Adapter adapter = list.getAdapter();
            if(adapter instanceof WebtoonCardAdapter)
                ((WebtoonCardAdapter) adapter).setItems(section);
            else
                list.setAdapter(new WebtoonCardAdapter(section));
        }
    }

    class WebtoonCardAdapter extends RecyclerView.Adapter<WebtoonCardAdapter.CardHolder> {
        List<?> items;
        String itemsKey;

        WebtoonCardAdapter(List<?> items) {
            this.items = items;
            this.itemsKey = cardListKey(items);
            setHasStableIds(true);
        }

        void setItems(List<?> items) {
            List<?> next = items == null ? new ArrayList<>() : new ArrayList<>(items);
            String nextKey = cardListKey(next);
            if(nextKey.equals(itemsKey))
                return;
            this.items = next;
            this.itemsKey = nextKey;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public CardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new CardHolder(inflater.inflate(R.layout.item_webtoon_card, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull CardHolder holder, int position) {
            Object item = items.get(position);
            if(!(item instanceof Title))
                return;

            Title title = (Title) item;
            String meta = title.getTags().size() > 0 ? TextUtils.join(" / ", title.getTags()) : title.getRelease();
            String nextKey = cardContentKey(title);
            if(!nextKey.equals(holder.boundKey)) {
                setTextIfChanged(holder.name, title.getName());
                setTextIfChanged(holder.meta, meta == null ? "" : meta);
                holder.boundKey = nextKey;
            }

            String thumb = title.getThumb();
            if(save || thumb == null || thumb.length() == 0) {
                bindStaticThumb(holder.thumb, "placeholder", R.drawable.app_cover_placeholder);
            } else {
                bindGlideThumb(holder.thumb, getGlideUrl(thumb, title.getBaseMode()), 128, 156, R.drawable.app_cover_placeholder);
            }

            holder.card.setOnClickListener(v -> {
                if(listener != null)
                    listener.clickedTitle(title);
            });
        }

        @Override
        public int getItemCount() {
            return items == null ? 0 : items.size();
        }

        @Override
        public int getItemViewType(int position) {
            return VIEW_TYPE_WEBTOON_CARD;
        }

        @Override
        public long getItemId(int position) {
            if(items == null || position < 0 || position >= items.size())
                return RecyclerView.NO_ID;
            Object item = items.get(position);
            if(item instanceof Title) {
                Title title = (Title) item;
                return (((long) title.getBaseMode()) << 32) ^ title.getId();
            }
            return item.hashCode();
        }

        class CardHolder extends RecyclerView.ViewHolder {
            View card;
            ImageView thumb;
            TextView name;
            TextView meta;
            String boundKey;

            CardHolder(View itemView) {
                super(itemView);
                card = itemView.findViewById(R.id.webtoon_card);
                thumb = itemView.findViewById(R.id.webtoon_thumb);
                name = itemView.findViewById(R.id.webtoon_name);
                meta = itemView.findViewById(R.id.webtoon_meta);
                if(dark)
                    card.setBackgroundColor(ContextCompat.getColor(context, R.color.colorDarkBackground));
            }
        }
    }

    private void preloadThumbnails(List<Ranking<?>> sections) {
        if(save || sections == null)
            return;
        int maxPerFetch = p.getDataSave() ? 4 : PRELOAD_THUMB_MAX_PER_FETCH;
        if(preloadCount >= maxPerFetch)
            return;
        for(Ranking<?> section : sections) {
            if(section == null)
                continue;
            for(Object item : section) {
                if(!(item instanceof Title))
                    continue;
                String thumb = ((Title) item).getThumb();
                if(thumb == null || thumb.length() == 0)
                    continue;
                String key = ((Title) item).getBaseMode() + ":" + thumb;
                if(!preloadedThumbs.add(key))
                    continue;
                trimPreloadedThumbs();
                Glide.with(context)
                        .load(getGlideUrl(thumb, ((Title) item).getBaseMode()))
                        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                        .override(dp(128), dp(156))
                        .preload();
                if(++preloadCount >= maxPerFetch)
                    return;
            }
        }
    }

    private void trimPreloadedThumbs() {
        while(preloadedThumbs.size() > PRELOADED_THUMB_LIMIT) {
            Iterator<String> iterator = preloadedThumbs.iterator();
            if(!iterator.hasNext())
                return;
            iterator.next();
            iterator.remove();
        }
    }

    private void updateRows(List<Object> newRows) {
        final List<Object> oldRows = rows == null ? Collections.emptyList() : new ArrayList<>(rows);
        final List<Object> nextRows = newRows == null ? new ArrayList<>() : new ArrayList<>(newRows);
        if(oldRows.size() > 0 && rowsContentSignature(oldRows).equals(rowsContentSignature(nextRows))) {
            rows = nextRows;
            return;
        }
        if(anchorRecycler != null && anchorRecycler.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
            pendingRows = nextRows;
            return;
        }
        pendingRows = null;
        final ScrollAnchor anchor = captureScrollAnchor(oldRows);
        if(oldRows.size() == 0 && nextRows.size() > 0) {
            Runnable applyFirstContent = () -> {
                rows = nextRows;
                notifyItemRangeInserted(0, nextRows.size());
                restoreScrollAnchor(anchor);
                if(!firstContentLogged && hasDisplayContent(rows)) {
                    firstContentLogged = true;
                    PerfTrace.end("home_first_content_ms", firstContentStartedAt);
                }
            };
            if(anchorRecycler != null)
                anchorRecycler.post(applyFirstContent);
            else
                MAIN.post(applyFirstContent);
            return;
        }
        final int generation = ++rowDiffGeneration;
        ROW_DIFF_EXECUTOR.execute(() -> {
            DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override
                public int getOldListSize() {
                    return oldRows.size();
                }

                @Override
                public int getNewListSize() {
                    return nextRows.size();
                }

                @Override
                public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                    return rowKey(oldRows.get(oldItemPosition)).equals(rowKey(nextRows.get(newItemPosition)));
                }

                @Override
                public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                    return rowContentKey(oldRows.get(oldItemPosition)).equals(rowContentKey(nextRows.get(newItemPosition)));
                }
            }, false);
            postRowDiff(generation, nextRows, diff, anchor);
        });
    }

    private void postRowDiff(int generation, List<Object> nextRows, DiffUtil.DiffResult diff, ScrollAnchor anchor) {
        Runnable apply = () -> {
            if(generation != rowDiffGeneration)
                return;
            rows = nextRows;
            diff.dispatchUpdatesTo(this);
            restoreScrollAnchor(anchor);
            if(!firstContentLogged && hasDisplayContent(rows)) {
                firstContentLogged = true;
                PerfTrace.end("home_first_content_ms", firstContentStartedAt);
            }
        };
        if(anchorRecycler != null)
            anchorRecycler.post(apply);
        else
            MAIN.post(apply);
    }

    private void applyPendingRows() {
        if(pendingRows == null)
            return;
        List<Object> rowsToApply = pendingRows;
        pendingRows = null;
        updateRows(rowsToApply);
    }

    private String rowsContentSignature(List<Object> candidateRows) {
        if(candidateRows == null || candidateRows.size() == 0)
            return "";
        StringBuilder builder = new StringBuilder(candidateRows.size() * 32);
        for(Object row : candidateRows)
            builder.append(rowContentKey(row)).append('\n');
        return builder.toString();
    }

    private ScrollAnchor captureScrollAnchor(List<Object> currentRows) {
        if(anchorRecycler == null || anchorRecycler.getScrollState() == RecyclerView.SCROLL_STATE_IDLE)
            return null;
        RecyclerView.LayoutManager manager = anchorRecycler.getLayoutManager();
        if(!(manager instanceof LinearLayoutManager) || currentRows == null || currentRows.size() == 0)
            return null;
        LinearLayoutManager linear = (LinearLayoutManager) manager;
        int position = linear.findFirstVisibleItemPosition();
        if(position == RecyclerView.NO_POSITION || position < 0 || position >= currentRows.size())
            return null;
        View child = linear.findViewByPosition(position);
        if(child == null)
            return null;
        return new ScrollAnchor(rowKey(currentRows.get(position)), child.getTop() - anchorRecycler.getPaddingTop());
    }

    private void restoreScrollAnchor(ScrollAnchor anchor) {
        if(anchor == null || anchorRecycler == null || rows == null)
            return;
        for(int i = 0; i < rows.size(); i++) {
            if(!rowKey(rows.get(i)).equals(anchor.key))
                continue;
            RecyclerView.LayoutManager manager = anchorRecycler.getLayoutManager();
            if(manager instanceof LinearLayoutManager) {
                final int position = i;
                anchorRecycler.post(() -> {
                    RecyclerView.LayoutManager postedManager = anchorRecycler.getLayoutManager();
                    if(postedManager instanceof LinearLayoutManager)
                        ((LinearLayoutManager) postedManager).scrollToPositionWithOffset(position, anchor.offset);
                });
            }
            return;
        }
    }

    private String titleKey(Title title) {
        if(title == null)
            return "";
        return title.getBaseMode() + ":" + title.getId();
    }

    private String titleListKey(List<Title> titles) {
        if(titles == null || titles.size() == 0)
            return "";
        StringBuilder builder = new StringBuilder();
        int count = Math.min(titles.size(), 8);
        for(int i = 0; i < count; i++) {
            if(i > 0)
                builder.append('|');
            builder.append(titleContentKey(titles.get(i)));
        }
        builder.append("#").append(titles.size());
        return builder.toString();
    }

    private String titleContentKey(Title title) {
        if(title == null)
            return "";
        return titleKey(title) + ":" + title.getName() + ":" + title.getThumb() + ":"
                + title.getBookmarkEpisodeId() + ":" + title.getBookmarkEpisodeIndex() + ":"
                + title.getEpisodeCount() + ":" + title.getBookmark();
    }

    private String cardKey(Object item) {
        if(item instanceof Title)
            return "title:" + titleKey((Title)item);
        if(item instanceof MTitle) {
            MTitle title = (MTitle)item;
            return "mtitle:" + title.getBaseMode() + ":" + title.getId();
        }
        return String.valueOf(item == null ? "" : item.hashCode());
    }

    private String cardListKey(List<?> items) {
        if(items == null || items.size() == 0)
            return "";
        StringBuilder builder = new StringBuilder();
        int count = Math.min(items.size(), 12);
        for(int i = 0; i < count; i++) {
            if(i > 0)
                builder.append('|');
            builder.append(cardContentKey(items.get(i)));
        }
        builder.append("#").append(items.size());
        return builder.toString();
    }

    private String cardContentKey(Object item) {
        if(item instanceof Title)
            return titleContentKey((Title)item);
        if(item instanceof MTitle) {
            MTitle title = (MTitle)item;
            return cardKey(item) + ":" + title.getName() + ":" + title.getThumb();
        }
        return String.valueOf(item);
    }

    private String rowKey(Object row) {
        if(row instanceof HeroRow)
            return "hero";
        if(row instanceof HomeSection)
            return "home:" + ((HomeSection) row).title;
        if(row instanceof ActionStrip)
            return "home:actions";
        if(row instanceof CategoryPanel)
            return "category:" + ((CategoryPanel) row).key;
        if(row instanceof String)
            return "group:" + row;
        if(row instanceof Ranking)
            return "section:" + ((Ranking<?>) row).getName();
        return String.valueOf(row);
    }

    private String rowContentKey(Object row) {
        if(row instanceof HeroRow) {
            HeroRow hero = (HeroRow) row;
            StringBuilder builder = new StringBuilder("hero:").append(hero.titles.size());
            for(Title title : hero.titles)
                if(title != null)
                    builder.append(':').append(title.getBaseMode()).append('/').append(title.getId());
            return builder.toString();
        }
        if(row instanceof HomeSection) {
            HomeSection section = (HomeSection) row;
            StringBuilder builder = new StringBuilder(rowKey(row)).append(':').append(section.style).append(':').append(section.titles.size());
            for(Title title : section.titles)
                if(title != null) {
                    builder.append(':').append(title.getBaseMode()).append('/').append(title.getId());
                    if(section.style == STYLE_CONTINUE)
                        builder.append('@')
                                .append(title.getBookmarkEpisodeId()).append('/')
                                .append(title.getBookmarkEpisodeIndex()).append('/')
                                .append(title.getEpisodeCount()).append('/')
                                .append(title.getBookmark());
                }
            return builder.toString();
        }
        if(row instanceof Ranking) {
            Ranking<?> ranking = (Ranking<?>) row;
            StringBuilder builder = new StringBuilder(rowKey(row)).append(':').append(ranking.size());
            for(Object item : ranking) {
                if(item instanceof Title) {
                    Title title = (Title) item;
                    builder.append(':').append(title.getBaseMode()).append('/').append(title.getId());
                } else if(item != null) {
                    builder.append(':').append(item.hashCode());
                }
            }
            return builder.toString();
        }
        if(row instanceof CategoryPanel)
            return rowKey(row);
        return rowKey(row);
    }

    private static class ScrollAnchor {
        final String key;
        final int offset;

        ScrollAnchor(String key, int offset) {
            this.key = key;
            this.offset = offset;
        }
    }

    private class Fetcher {
        private MangaRepository.Cancellation cancellation;
        private List<Ranking<?>> finalDataSet;
        private boolean keepExistingRowsDuringFetch;
        private AppDispatchers.TaskHandle handle;
        private volatile boolean cancelled = false;

        void start() {
            prepare();
            handle = AppDispatchers.submitIo(() -> {
                Boolean result = fetchSections();
                AppDispatchers.runOnMain(() -> finish(result));
            });
        }

        private void prepare() {
            siteNtkSnapshot = isNtkSite();
            boolean hadInitialRows = rows != null && rows.size() > 0 && hasDisplayContent();
            boolean hadCompleteServerHome = hasCompleteHomeSections(dataSet);
            keepExistingRowsDuringFetch = hadInitialRows && hadCompleteServerHome;
            if(!hadInitialRows || collectTitles(dataSet, 1).size() == 0)
                dataSet = initialDataSetForSite();
            preloadedThumbs.clear();
            preloadCount = 0;
            pendingRows = null;
            initialRowsShown = keepExistingRowsDuringFetch;
            List<Object> warmRows = buildRows(dataSet, false);
            if(!hasDisplayContent(warmRows))
                warmRows = buildInitialPlaceholderRows();
            if(!initialRowsShown && hasDisplayContent(warmRows)) {
                initialRowsShown = true;
                updateRows(warmRows);
                if(hasHero(warmRows))
                    scrollHeroToTop();
            }
        }

        private Boolean fetchSections() {
            cancellation = MangaRepository.cancellation();
            boolean ntk = siteNtkSnapshot;
            String[][] sections = MainPageWebtoon.getSections(baseMode, ntk);
            CompletionService<SectionResult> completion = AppDispatchers.ioCompletionService();
            MainPageWebtoon parser = MangaRepository.createWebtoonParser(baseMode);
            List<Ranking<?>> fetchedSections = MainPageWebtoon.getBlankDataSet(baseMode, ntk);
            int submitted = 0;
            int loaded = 0;
            List<SectionResult> pendingResults = new ArrayList<>();
            List<Future> running = new ArrayList<>();
            boolean firstScreenPublished = false;

            try {
                for(int i = 0; i < sections.length; i++) {
                    final int index = i;
                    final String[] section = sections[i];
                    if(!shouldFetchHomeSection(section, ntk))
                        continue;
                    running.add(completion.submit(AppDispatchers.safeCallable(() -> new SectionResult(index,
                            MangaRepository.loadWebtoonSection(parser, section[0], section[1], baseMode, cancellation)))));
                    submitted++;
                }

                for(int i = 0; i < submitted && !cancelled; i++) {
                    Future future = completion.take();
                    SectionResult result = (SectionResult) future.get();
                    if(result != null && result.ranking != null) {
                        if(result.ranking.size() > 0)
                            loaded++;
                        if(result.index >= 0) {
                            if(result.index < fetchedSections.size())
                                fetchedSections.set(result.index, result.ranking);
                            else
                                fetchedSections.add(result.ranking);
                        }
                        pendingResults.add(result);
                        int batchSize = firstScreenPublished ? SECTION_BATCH_SIZE : FIRST_SCREEN_BATCH_SIZE;
                        if(pendingResults.size() >= batchSize) {
                            postProgress(new SectionBatch(new ArrayList<>(pendingResults)));
                            pendingResults.clear();
                            firstScreenPublished = true;
                        }
                    }
                }
                if(pendingResults.size() > 0)
                    postProgress(new SectionBatch(new ArrayList<>(pendingResults)));
                if(baseMode == base_webtoon)
                    MainPageWebtoon.enhanceWebtoonClassification(fetchedSections);
                else if(baseMode == base_comic)
                    MainPageWebtoon.enhanceComicClassification(fetchedSections);
                finalDataSet = fetchedSections;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                if(!isCancelled())
                    ml.melun.mangaview.report.CrashReporter.record(e);
            } finally {
                for(Future future : running)
                    if(future != null && !future.isDone())
                        future.cancel(true);
            }
            return loaded > 0;
        }

        private boolean shouldFetchHomeSection(String[] section, boolean ntk) {
            if(!ntk || baseMode != base_webtoon || section == null || section.length < 2)
                return true;
            String path = section[1];
            return "/ing".equals(path)
                    || "/end".equals(path)
                    || "/ing?sort=hot".equals(path)
                    || "/end?sort=hot".equals(path);
        }

        private void postProgress(SectionBatch batch) {
            AppDispatchers.runOnMain(() -> applyProgress(batch));
        }

        private void applyProgress(SectionBatch batch) {
            if(batch == null || cancelled)
                return;
            if(siteNtkSnapshot != isNtkSite()) {
                cancel(true);
                resetForSiteChange();
                return;
            }
            if(batch.results == null || batch.results.size() == 0)
                return;
            if(dataSet == null)
                dataSet = initialDataSetForSite();
            List<Ranking<?>> loadedSections = new ArrayList<>();
            for(SectionResult result : batch.results) {
                if(result == null || result.index < 0)
                    continue;
                if(result.index < dataSet.size())
                    dataSet.set(result.index, result.ranking);
                else
                    dataSet.add(result.ranking);
                if(result.ranking != null && result.ranking.size() > 0)
                    loadedSections.add(result.ranking);
            }
            if(loadedSections.size() == 0)
                return;
            if(shouldHoldExistingRowsDuringFetch()) {
                scheduleThumbnailPreload(loadedSections);
                return;
            }
            if(!initialRowsShown) {
                List<Object> firstRows = buildRows(dataSet, false);
                if(!hasDisplayContent(firstRows))
                    return;
                initialRowsShown = true;
                updateRows(firstRows);
                if(hasHero(firstRows))
                    scrollHeroToTop();
                scheduleThumbnailPreload(loadedSections);
                return;
            }
            List<Object> progressRows = buildRowsForCurrentTab(true);
            if(!hasDisplayContent(progressRows) && activeHomeTab != 3)
                return;
            updateRows(progressRows);
            scheduleThumbnailPreload(loadedSections);
        }

        private void finish(Boolean hasAnyResult) {
            if(cancelled)
                return;
            if(siteNtkSnapshot != isNtkSite()) {
                resetForSiteChange();
                return;
            }
            if(fetcher == this)
                fetcher = null;
            if(!hasAnyResult) {
                notifyFetchFinished(false);
                if(!hasDisplayContent() && listener != null)
                    listener.captchaCallback();
                return;
            }
            if(finalDataSet != null)
                dataSet = finalDataSet;
            List<Object> finalRows = buildRows(dataSet, false);
            boolean shouldShowTop = !initialRowsShown && hasHero(finalRows);
            initialRowsShown = true;
            updateRows(finalRows);
            saveHomeSnapshot(dataSet);
            if(shouldShowTop)
                scrollHeroToTop();
            scheduleContinueProgressBackfill();
            notifyFetchFinished(hasRequiredHomeSections(dataSet));
        }

        private void notifyFetchFinished(boolean success) {
            if(fetchStateListener != null)
                fetchStateListener.onFetchFinished(baseMode, success);
        }

        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            if(cancellation != null)
                cancellation.cancel();
            if(fetcher == this)
                fetcher = null;
            return handle == null || handle.cancel();
        }

        private boolean isCancelled() {
            return cancelled;
        }

        private boolean shouldHoldExistingRowsDuringFetch() {
            return keepExistingRowsDuringFetch && activeHomeTab == 0 && hasDisplayContent(rows);
        }
    }

    private void scrollHeroToTop() {
        if(anchorRecycler == null)
            return;
        anchorRecycler.post(() -> {
            RecyclerView.LayoutManager manager = anchorRecycler.getLayoutManager();
            if(manager instanceof LinearLayoutManager)
                ((LinearLayoutManager) manager).scrollToPositionWithOffset(0, 0);
            else if(manager != null)
                manager.scrollToPosition(0);
        });
    }

    private void scheduleThumbnailPreload(List<Ranking<?>> sections) {
        if(anchorRecycler != null)
            anchorRecycler.postDelayed(() -> {
                if(anchorRecycler == null || anchorRecycler.getScrollState() == RecyclerView.SCROLL_STATE_SETTLING)
                    return;
                preloadThumbnails(sections);
            }, 120);
        else
            preloadThumbnails(sections);
    }

    private boolean hasHero(List<Object> candidateRows) {
        if(candidateRows == null)
            return false;
        for(Object row : candidateRows)
            if(row instanceof HeroRow)
                return true;
        return false;
    }

    private Manga resolveContinueMangaForWarmup(Title item) {
        if(item == null)
            return null;
        int bookmark = p.getBookmark(item);
        if(bookmark <= 0)
            bookmark = item.getBookmark();
        if(bookmark <= 0)
            bookmark = item.getBookmarkEpisodeId();
        if(bookmark <= 0)
            return null;
        List<Manga> eps = snapshotEpisodes(item);
        Manga resolved = findEpisodeByIdForWarmup(eps, bookmark);
        if(resolved == null) {
            int progressIndex = item.getBookmarkEpisodeIndex();
            if(progressIndex <= 0 && eps != null && bookmark <= eps.size())
                progressIndex = bookmark;
            resolved = episodeAtForWarmup(eps, progressIndex);
        }
        if(resolved == null)
            resolved = new Manga(bookmark, "", "", item.getBaseMode());
        resolved.setTitle(item);
        resolved.setTitleId(item.getId());
        if(eps != null && eps.size() > 0)
            resolved.setEps(eps);
        return resolved;
    }

    private Manga findEpisodeByIdForWarmup(List<Manga> eps, int bookmark) {
        if(eps == null)
            return null;
        for(Manga episode : eps)
            if(episode != null && episode.getId() == bookmark)
                return episode;
        return null;
    }

    private Manga episodeAtForWarmup(List<Manga> eps, int oneBasedIndex) {
        if(eps == null || oneBasedIndex <= 0 || oneBasedIndex > eps.size())
            return null;
        return eps.get(oneBasedIndex - 1);
    }

    private void scheduleContinueProgressBackfill() {
        if(continueProgressBackfillRunning || !hasMissingContinueProgress())
            return;
        continueProgressBackfillRunning = true;
        AppDispatchers.runUserAction(() -> {
            long versionBefore = p.getLocalDataVersion();
            try {
                MangaRepository.backfillRecentProgress(4);
            } catch (Exception e) {
                ml.melun.mangaview.report.CrashReporter.record(e);
            }
            Runnable done = () -> {
                continueProgressBackfillRunning = false;
                if(versionBefore != p.getLocalDataVersion())
                    updateRows(buildRows(dataSet, false));
            };
            if(anchorRecycler != null)
                anchorRecycler.post(done);
            else
                MAIN.post(done);
        });
    }

    private boolean hasMissingContinueProgress() {
        List<MTitle> recent = Utils.snapshotList(p.getRecent());
        if(recent == null)
            return false;
        for(MTitle item : recent) {
            if(item == null || item.getBaseMode() != baseMode)
                continue;
            if(item.getBookmarkEpisodeIndex() > 0 && item.getEpisodeCount() > 0)
                continue;
            Title title = item instanceof Title ? (Title) item : new Title(item);
            int bookmark = p.getBookmark(title);
            if(bookmark <= 0)
                bookmark = item.getBookmarkEpisodeId();
            if(bookmark > 0)
                return true;
        }
        return false;
    }
}
