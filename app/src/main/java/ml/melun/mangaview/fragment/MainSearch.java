package ml.melun.mangaview.fragment;

import android.content.Intent;
import android.content.DialogInterface;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.appcompat.widget.PopupMenu;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.tabs.TabLayout;
import com.omadahealth.github.swipyrefreshlayout.library.SwipyRefreshLayout;
import com.omadahealth.github.swipyrefreshlayout.library.SwipyRefreshLayoutDirection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ml.melun.mangaview.ui.NpaLinearLayoutManager;
import ml.melun.mangaview.R;
import ml.melun.mangaview.Preference;
import ml.melun.mangaview.Utils;
import ml.melun.mangaview.adapter.TitleAdapter;
import ml.melun.mangaview.activity.AdvSearchActivity;
import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Search;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.repository.MangaRepository;
import ml.melun.mangaview.repository.OfflineStore;
import ml.melun.mangaview.runtime.AppDispatchers;
import ml.melun.mangaview.runtime.PerformanceMonitor;
import ml.melun.mangaview.runtime.PerfTrace;

import static ml.melun.mangaview.MainApplication.getHttpClient;
import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.episodeIntent;
import static ml.melun.mangaview.Utils.openViewer;
import static ml.melun.mangaview.Utils.popup;
import static ml.melun.mangaview.Utils.showPopup;
import static ml.melun.mangaview.activity.CaptchaActivity.RESULT_CAPTCHA;

public class MainSearch extends Fragment {
    private static final String ARG_LIBRARY_MODE = "libraryMode";
    private static final int KEYBOARD_SHOW_DELAY_MS = 120;
    private static final int KEYBOARD_SHOW_MAX_ATTEMPTS = 3;
    private static final long DESTINATION_LAUNCH_DEBOUNCE_MS = 1500L;
    SwipyRefreshLayout swipe;
    FloatingActionButton advSearchBtn;
    View noresult;
    TextView noResultText;
    private EditText searchBox;
    View searchSubmitButton;
    RecyclerView searchResult;
    Spinner searchMode, baseMode;
    TitleAdapter searchAdapter;
    Search search;
    SearchManga searchTask;
    String activeSearchKey = null;
    Fragment fragment;
    LinearLayoutCompat optionsPanel;
    String prequery = null;
    boolean pendingOpenSearch = false;
    boolean onlineSearchMode = false;
    TextView libraryCount;
    View libraryMeta;
    TabLayout libraryTab;
    ArrayList<Title> offlineTitles = new ArrayList<>();
    LoadOfflineTitles offlineTask;
    boolean offlineTitlesLoaded = false;
    int pendingBaseMode = -1;
    String activeLibraryQuery = null;
    Preference.LocalChangeListener localChangeListener;
    float listDownX;
    float listDownY;
    long listDownWallTime;
    Runnable pendingListLongPress;
    boolean listLongPressHandled = false;
    boolean listMovedBeyondTapSlop = false;
    boolean listDownOnResume = false;
    int listScrollState = RecyclerView.SCROLL_STATE_IDLE;
    long lastTitlePopupAt = 0;
    int lastTitlePopupId = -1;
    int lastTitlePopupBaseMode = -1;
    boolean pendingLibraryRefresh = false;
    boolean libraryMode = true;
    long searchFirstStartedAt = 0L;
    AppDispatchers.TaskHandle libraryFilterTask;
    int libraryFilterGeneration = 0;
    long librarySnapshotVersion = -1L;
    final ArrayList<Title>[] librarySnapshots = new ArrayList[4];
    int keyboardShowGeneration = 0;
    boolean suppressNextAutoCaptchaOpen = false;

    public static MainSearch newSearchTab() {
        MainSearch fragment = new MainSearch();
        Bundle args = new Bundle();
        args.putBoolean(ARG_LIBRARY_MODE, false);
        fragment.setArguments(args);
        return fragment;
    }

    public static MainSearch newLibraryTab() {
        MainSearch fragment = new MainSearch();
        Bundle args = new Bundle();
        args.putBoolean(ARG_LIBRARY_MODE, true);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        libraryMode = args == null || args.getBoolean(ARG_LIBRARY_MODE, true);
        onlineSearchMode = !libraryMode;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        ViewGroup rootView = (ViewGroup) inflater.inflate(R.layout.content_search , container, false);

        //search content
        noresult = rootView.findViewById(R.id.noResult);
        noResultText = rootView.findViewById(R.id.noResultText);
        searchBox = rootView.findViewById(R.id.searchBox);
        searchSubmitButton = rootView.findViewById(R.id.searchSubmitButton);
        searchResult = rootView.findViewById(R.id.searchResult);
        searchResult.setLayoutManager(new NpaLinearLayoutManager(getContext()));
        searchResult.setHasFixedSize(true);
        searchResult.setItemViewCacheSize(12);
        searchResult.setItemAnimator(null);
        searchResult.setOverScrollMode(View.OVER_SCROLL_NEVER);
        searchResult.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                listScrollState = newState;
                if(newState != RecyclerView.SCROLL_STATE_IDLE) {
                    listMovedBeyondTapSlop = true;
                    cancelTitleListLongPress();
                }
                if(getContext() == null || !isAdded())
                    return;
                PerformanceMonitor.phase(newState == RecyclerView.SCROLL_STATE_IDLE ? "idle" : "scrolling");
                if(newState == RecyclerView.SCROLL_STATE_IDLE) {
                    applyPendingLibraryRefreshIfIdle();
                    warmupVisibleResumeItems();
                    PerformanceMonitor.reportNow(libraryMode && !onlineSearchMode ? "library_scroll_idle" : "search_scroll_idle");
                }
            }
        });
        searchResult.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent event) {
                if(searchAdapter == null)
                    return false;
                if(event.getAction() == MotionEvent.ACTION_DOWN) {
                    listDownX = event.getX();
                    listDownY = event.getY();
                    listDownWallTime = System.currentTimeMillis();
                    listLongPressHandled = false;
                    listMovedBeyondTapSlop = false;
                    listDownOnResume = isTouchOnResumeButton(event.getX(), event.getY());
                    if(listDownOnResume)
                        warmupResumeAtTouch(event.getX(), event.getY());
                    scheduleTitleListLongPress();
                    return false;
                }
                if(event.getAction() == MotionEvent.ACTION_MOVE) {
                    if(listLongPressHandled)
                        return true;
                    if(movedBeyondListTapSlop(event)) {
                        listMovedBeyondTapSlop = true;
                        cancelTitleListLongPress();
                    }
                    return false;
                }
                if(event.getAction() == MotionEvent.ACTION_CANCEL) {
                    cancelTitleListLongPress();
                    listLongPressHandled = false;
                    listDownOnResume = false;
                    return false;
                }
                if(event.getAction() == MotionEvent.ACTION_UP) {
                    cancelTitleListLongPress();
                    if(listLongPressHandled) {
                        listLongPressHandled = false;
                        listDownOnResume = false;
                        return true;
                    }
                    if(!listMovedBeyondTapSlop
                            && listScrollState == RecyclerView.SCROLL_STATE_IDLE
                            && System.currentTimeMillis() - listDownWallTime < ViewConfiguration.getLongPressTimeout())
                        return handleTitleListTap(event.getX(), event.getY());
                    listDownOnResume = false;
                    return false;
                }
                return false;
            }

            @Override
            public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent event) {
                if(event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                    cancelTitleListLongPress();
                    listLongPressHandled = false;
                    listDownOnResume = false;
                }
            }
        });
        searchMode = rootView.findViewById(R.id.searchMode);
        baseMode = rootView.findViewById(R.id.searchBaseMode);
        advSearchBtn = rootView.findViewById(R.id.advSearchBtn);
        swipe = rootView.findViewById(R.id.searchSwipe);
        updateSwipeEnabled();
        optionsPanel = rootView.findViewById(R.id.searchOptionPanel);
        libraryCount = rootView.findViewById(R.id.libraryCount);
        libraryMeta = rootView.findViewById(R.id.libraryMeta);
        libraryTab = rootView.findViewById(R.id.libraryTab);
        fragment = this;
        localChangeListener = scope -> {
            if(!isLibraryChange(scope) || searchResult == null)
                return;
            invalidateLibrarySnapshots(scope);
            searchResult.post(this::refreshLibraryFromPreferences);
        };
        p.addLocalChangeListener(localChangeListener);
        setupLibraryTabs();
        if(!libraryMode) {
            if(libraryTab != null)
                libraryTab.setVisibility(View.GONE);
            if(libraryMeta != null)
                libraryMeta.setVisibility(View.GONE);
            if(optionsPanel != null)
                optionsPanel.setVisibility(View.VISIBLE);
            noResultText.setText("검색어를 입력하면 작품을 찾아드립니다");
            noresult.setVisibility(View.VISIBLE);
        }
        if(p.getDarkTheme()){
            searchMode.setPopupBackgroundResource(R.color.colorDarkWindowBackground);
            baseMode.setPopupBackgroundResource(R.color.colorDarkWindowBackground);
        }
        applySearchTheme(rootView);

        searchBox.setOnFocusChangeListener((view, b) -> {
            if(optionsPanel != null)
                optionsPanel.setVisibility(onlineSearchMode || !libraryMode ? View.VISIBLE : View.GONE);
            updateAdvSearchVisibility();
        });

        advSearchBtn.setOnClickListener(v -> {
            if(getContext() == null || !canLaunchDestination())
                return;
            Intent advSearch = new Intent(getContext(), AdvSearchActivity.class);
            startActivity(advSearch);
        });
        searchSubmitButton.setOnClickListener(v -> searchSubmit());

        searchBox.setSingleLine(true);
        searchBox.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchBox.setOnEditorActionListener((v, actionId, event) -> {
            if(actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || event != null && event.getAction()==KeyEvent.ACTION_DOWN && keyCodeIsEnter(event)){
                searchSubmit();
                return true;
            }
            return false;
        });

        AdapterView.OnItemSelectedListener mlistener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                optionUpdate();
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {
                optionUpdate();
            }
        };
        baseMode.setOnItemSelectedListener(mlistener);
        searchMode.setOnItemSelectedListener(mlistener);

        baseMode.setSelection(libraryMode ? baseModePosition(p.getBaseMode()) : 0);
        if(pendingBaseMode > 0)
            applyBaseMode(pendingBaseMode);
        if(prequery == null && libraryMode && !onlineSearchMode)
            showLibrary();
        if(pendingOpenSearch || !libraryMode)
            enterSearchMode();



        swipe.setOnRefreshListener(direction -> {
            if(search==null || !onlineSearchMode) swipe.setRefreshing(false);
            else {
                if (!search.isLast()) {
                    if(searchTask == null) {
                        activeSearchKey = null;
                        searchTask = new SearchManga(search, false, false);
                        searchTask.start();
                    }
                } else swipe.setRefreshing(false);
            }
        });
        return rootView;
    }

    private void applySearchTheme(ViewGroup rootView) {
        if(getContext() == null || !p.getDarkTheme())
            return;

        int windowBackground = ContextCompat.getColor(getContext(), R.color.colorDarkWindowBackground);
        int surface = ContextCompat.getColor(getContext(), R.color.colorDarkSurface);
        int surfaceElevated = ContextCompat.getColor(getContext(), R.color.colorDarkSurfaceElevated);
        int divider = ContextCompat.getColor(getContext(), R.color.colorDarkDivider);
        int text = ContextCompat.getColor(getContext(), R.color.colorDarkText);
        int secondary = ContextCompat.getColor(getContext(), R.color.colorDarkTextSecondary);
        int accent = ContextCompat.getColor(getContext(), R.color.appAccent);

        rootView.setBackgroundColor(windowBackground);
        View searchCardView = rootView.findViewById(R.id.searchCard);
        if(searchCardView instanceof CardView)
            ((CardView) searchCardView).setCardBackgroundColor(surface);
        if(searchBox != null) {
            searchBox.setTextColor(text);
            searchBox.setHintTextColor(secondary);
        }
        if(searchResult != null)
            searchResult.setBackgroundColor(windowBackground);
        if(swipe != null)
            swipe.setBackgroundColor(windowBackground);
        if(optionsPanel != null)
            optionsPanel.setBackgroundColor(windowBackground);
        if(libraryMeta != null)
            libraryMeta.setBackgroundColor(windowBackground);
        if(libraryCount != null)
            libraryCount.setTextColor(secondary);
        TextView librarySort = rootView.findViewById(R.id.librarySort);
        if(librarySort != null)
            librarySort.setTextColor(secondary);
        if(libraryTab != null) {
            libraryTab.setBackgroundColor(windowBackground);
            libraryTab.setTabTextColors(secondary, accent);
            libraryTab.setSelectedTabIndicatorColor(accent);
        }
        if(noresult != null)
            noresult.setBackground(makeRoundedBackground(surface, divider, 16));
        if(noResultText != null)
            noResultText.setTextColor(secondary);
        styleFilterSpinner(baseMode, surfaceElevated, divider, text);
        styleFilterSpinner(searchMode, surfaceElevated, divider, text);
    }

    private void styleFilterSpinner(Spinner spinner, int background, int stroke, int text) {
        if(spinner == null)
            return;
        spinner.setBackground(makeRoundedBackground(background, stroke, 10));
        spinner.post(() -> {
            View selected = spinner.getSelectedView();
            if(selected instanceof TextView)
                ((TextView) selected).setTextColor(text);
        });
    }

    private GradientDrawable makeRoundedBackground(int color, int strokeColor, int radiusDp) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radiusDp));
        background.setStroke(dp(1), strokeColor);
        return background;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onResume() {
        super.onResume();
        if(prequery != null){
            applyPendingSearch();
        } else if(libraryMode && search == null && !onlineSearchMode) {
            int tab = getLibraryTabPosition();
            if(tab == 0 || tab == 3) {
                offlineTitles = new ArrayList<>();
                offlineTitlesLoaded = false;
            }
            showLibrary();
        }
    }

    void optionUpdate(){
        //shows or hides options
        //p.setBaseMode(baseMode.getSelectedItemPosition()+1);
    }

    public void setSearch(String prequery){
        this.prequery = prequery;
        onlineSearchMode = true;
        activeLibraryQuery = null;
        updateSwipeEnabled();
        if(searchBox != null)
            applyPendingSearch();
    }

    public void setBaseMode(int mode) {
        pendingBaseMode = mode;
        if(baseMode != null)
            applyBaseMode(mode);
    }

    private void applyBaseMode(int mode) {
        int position = baseModePosition(mode);
        if(position < 0 || position >= baseMode.getCount())
            return;
        baseMode.setSelection(position);
    }

    private int baseModePosition(int mode) {
        if(mode == MTitle.base_comic)
            return 1;
        if(mode == MTitle.base_webtoon)
            return 2;
        return 0;
    }

    private int selectedSearchBaseMode() {
        if(baseMode == null)
            return MTitle.base_auto;
        switch(baseMode.getSelectedItemPosition()) {
            case 1:
                return MTitle.base_comic;
            case 2:
                return MTitle.base_webtoon;
            default:
                return MTitle.base_auto;
        }
    }

    public void enterSearchMode() {
        pendingOpenSearch = true;
        if(searchBox == null)
            return;
        pendingOpenSearch = false;
        if(!libraryMode)
            searchBox.setHint("전체 검색");
        searchBox.setVisibility(View.VISIBLE);
        if(optionsPanel != null)
            optionsPanel.setVisibility(View.VISIBLE);
        searchBox.requestFocus();
        updateAdvSearchVisibility();
        scheduleShowKeyboard(++keyboardShowGeneration, 0);
    }

    private void scheduleShowKeyboard(int generation, int attempt) {
        if(searchBox == null)
            return;
        searchBox.postDelayed(() -> showKeyboardIfReady(generation, attempt), KEYBOARD_SHOW_DELAY_MS);
    }

    private void showKeyboardIfReady(int generation, int attempt) {
        if(generation != keyboardShowGeneration || getContext() == null || searchBox == null || !isAdded())
            return;
        if(getActivity() == null || !searchBox.isAttachedToWindow() || !searchBox.hasWindowFocus() || !getActivity().hasWindowFocus()) {
            if(attempt < KEYBOARD_SHOW_MAX_ATTEMPTS)
                scheduleShowKeyboard(generation, attempt + 1);
            return;
        }
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if(imm != null)
            imm.showSoftInput(searchBox, InputMethodManager.SHOW_IMPLICIT);
    }

    public void enterLibraryMode() {
        if(!libraryMode) {
            enterSearchMode();
            return;
        }
        pendingOpenSearch = false;
        onlineSearchMode = false;
        updateSwipeEnabled();
        prequery = null;
        activeLibraryQuery = null;
        search = null;
        activeSearchKey = null;
        if(searchTask != null) {
            searchTask.cancel(true);
            searchTask = null;
        }
        if(searchAdapter != null)
            searchAdapter.setDeferThumbnails(false);
        if(searchBox != null)
            searchBox.setText("");
        if(searchBox != null)
            searchBox.setHint("보관함에서 검색");
        if(searchResult != null)
            showLibrary();
    }

    private void setupLibraryTabs() {
        if(libraryTab == null || libraryTab.getTabCount() > 0)
            return;
        libraryTab.addTab(libraryTab.newTab().setText("전체"));
        libraryTab.addTab(libraryTab.newTab().setText("최근"));
        libraryTab.addTab(libraryTab.newTab().setText("좋아요"));
        libraryTab.addTab(libraryTab.newTab().setText("저장됨"));
        libraryTab.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                if(onlineSearchMode)
                    return;
                showLibrary();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });
    }

    private void showLibrary() {
        if(!libraryMode)
            return;
        if(searchResult == null || getContext() == null)
            return;
        if(searchBox != null)
            searchBox.setHint("보관함에서 검색");
        onlineSearchMode = false;
        updateSwipeEnabled();
        updateSearchChrome(false);
        if(activeLibraryQuery != null && activeLibraryQuery.length() > 0) {
            performLibrarySearch(activeLibraryQuery);
            return;
        }
        if(libraryFilterTask != null) {
            libraryFilterTask.cancel();
            libraryFilterTask = null;
        }
        libraryFilterGeneration++;
        if(searchAdapter == null)
            searchAdapter = new TitleAdapter(getContext());
        searchAdapter.setResume(true);
        searchAdapter.setForceThumbnail(false);
        searchAdapter.setDeferThumbnails(false);
        int tab = getLibraryTabPosition();
        ArrayList<Title> data = getLibraryTitles(tab);
        loadOfflineTitlesIfNeeded(tab);
        bindLibraryData(data, libraryEmptyMessage(tab));
    }

    private void loadOfflineTitlesIfNeeded(int tab) {
        if(shouldLoadOfflineTitles(tab, offlineTitlesLoaded, offlineTask != null)) {
            offlineTask = new LoadOfflineTitles();
            offlineTask.start();
        }
    }

    private static boolean shouldLoadOfflineTitles(int tab, boolean loaded, boolean loading) {
        return (tab == 0 || tab == 3) && !loaded && !loading;
    }

    static boolean shouldLoadOfflineTitlesForTest(int tab, boolean loaded, boolean loading) {
        return shouldLoadOfflineTitles(tab, loaded, loading);
    }

    private int getLibraryTabPosition() {
        return libraryTab == null ? 0 : libraryTab.getSelectedTabPosition();
    }

    private ArrayList<Title> getLibraryTitles(int tab) {
        long version = p.getLocalDataVersion();
        if(version != librarySnapshotVersion) {
            for(int i = 0; i < librarySnapshots.length; i++)
                librarySnapshots[i] = null;
            librarySnapshotVersion = version;
        }
        if(tab >= 0 && tab < librarySnapshots.length && librarySnapshots[tab] != null)
            return new ArrayList<>(librarySnapshots[tab]);
        ArrayList<Title> data = new ArrayList<>();
        if(tab == 1) {
            appendUnique(data, Utils.snapshotList(p.getRecent()));
        } else if(tab == 2) {
            appendUnique(data, Utils.snapshotList(p.getFavorite()));
        } else if(tab == 3) {
            appendUnique(data, offlineTitles);
        } else {
            appendUnique(data, Utils.snapshotList(p.getRecent()));
            appendUnique(data, Utils.snapshotList(p.getFavorite()));
            appendUnique(data, offlineTitles);
        }
        if(tab >= 0 && tab < librarySnapshots.length)
            librarySnapshots[tab] = new ArrayList<>(data);
        return data;
    }

    private void invalidateLibrarySnapshots(String scope) {
        if("recent".equals(scope)) {
            librarySnapshots[0] = null;
            librarySnapshots[1] = null;
        } else if("favorite".equals(scope)) {
            librarySnapshots[0] = null;
            librarySnapshots[2] = null;
        } else {
            for(int i = 0; i < librarySnapshots.length; i++)
                librarySnapshots[i] = null;
        }
        librarySnapshotVersion = -1L;
    }

    private String libraryEmptyMessage(int tab) {
        if(tab == 1)
            return "최근 읽은 작품이 없습니다";
        if(tab == 2)
            return "보관함에 담긴 작품이 없습니다";
        if(tab == 3)
            return "저장된 작품이 없습니다";
        return "최근 읽거나 보관하거나 저장한 작품이 없습니다";
    }

    private void bindLibraryData(ArrayList<Title> data, String emptyMessage) {
        if(searchAdapter == null)
            searchAdapter = new TitleAdapter(getContext());
        searchAdapter.setResume(true);
        searchAdapter.setForceThumbnail(false);
        searchAdapter.setDeferThumbnails(false);
        searchAdapter.setLongClickEnabled(true);
        if(searchResult.getAdapter() != searchAdapter)
            searchResult.setAdapter(searchAdapter);
        searchResult.stopScroll();
        searchAdapter.setDataImmediate(data);
        updateAdvSearchVisibility();
        if(swipe != null)
            swipe.setRefreshing(false);
        if(libraryCount != null)
            libraryCount.setText(data.size() + "개 작품");
        noResultText.setText(emptyMessage);
        noresult.setVisibility(data.size() == 0 ? View.VISIBLE : View.GONE);
        scheduleVisibleResumeWarmup();
        searchAdapter.setClickListener(new TitleAdapter.ItemClickListener() {
            @Override
            public void onLongClick(View view, int position) {
                Title title = searchAdapter.getItem(position);
                if(title == null)
                    return;
                showLibraryTitlePopup(view, title);
            }

            @Override
            public void onResumeClick(int position, int id) {
                Title title = resolveLatestTitleForResume(searchAdapter.getItem(position));
                if(title == null)
                    return;
                int bookmark = resolveLatestBookmark(title, id);
                if(!canLaunchDestination())
                    return;
                openResume(title, bookmark);
            }

            @Override
            public void onItemClick(int position) {
                Title title = searchAdapter.getItem(position);
                if(title == null)
                    return;
                if(!canLaunchDestination())
                    return;
                if(isOfflineTitle(title)) {
                    Intent episodeView = episodeIntent(getContext(), title);
                    episodeView.putExtra("online", false);
                    startActivity(episodeView);
                } else if(title.getId() > 0) {
                    title = resolveLatestTitleForEpisode(title);
                    startActivity(episodeIntent(getContext(), title));
                }
            }
        });
    }

    private boolean isRecentTitle(Title title) {
        return title != null && p.findRecentTitle(title) != null;
    }

    private void showLibraryTitlePopup(View view, Title title) {
        if(getContext() == null || title == null)
            return;
        long now = System.currentTimeMillis();
        if(title.getId() == lastTitlePopupId
                && title.getBaseMode() == lastTitlePopupBaseMode
                && now - lastTitlePopupAt < 600)
            return;
        lastTitlePopupAt = now;
        lastTitlePopupId = title.getId();
        lastTitlePopupBaseMode = title.getBaseMode();
        PopupMenu popup = new PopupMenu(getContext(), view);
        popup.getMenuInflater().inflate(R.menu.title_options, popup.getMenu());

        boolean recent = isRecentTitle(title);
        boolean favorite = p.findFavorite(title) > -1;
        boolean offline = isOfflineTitle(title);
        boolean allTab = getLibraryTabPosition() == 0;

        popup.getMenu().findItem(R.id.del).setVisible(allTab ? recent || favorite : recent);
        popup.getMenu().findItem(R.id.del).setTitle("삭제");
        if(!allTab)
            popup.getMenu().findItem(favorite ? R.id.favDel : R.id.favAdd).setVisible(true);
        popup.getMenu().findItem(R.id.remove).setVisible(offline);

        popup.setOnMenuItemClickListener(item -> {
            switch(item.getItemId()) {
                case R.id.del:
                    if(allTab)
                        deleteLibraryListTitle(title);
                    else
                        removeRecentTitle(title);
                    break;
                case R.id.favAdd:
                case R.id.favDel:
                    p.toggleFavorite(title, 0);
                    refreshLibraryFromPreferences();
                    break;
                case R.id.remove:
                    confirmDeleteOfflineTitle(title);
                    break;
            }
            return true;
        });
        popup.show();
    }

    private void deleteLibraryListTitle(Title title) {
        if(title == null)
            return;
        if(isRecentTitle(title))
            p.removeRecent(title);
        if(p.findFavorite(title) > -1)
            p.toggleFavorite(title, 0);
        if(activeLibraryQuery != null && activeLibraryQuery.length() > 0)
            performLibrarySearch(activeLibraryQuery);
        else
            showLibrary();
    }

    private void removeRecentTitle(Title title) {
        if(title == null)
            return;
        p.removeRecent(title);
        if(activeLibraryQuery != null && activeLibraryQuery.length() > 0)
            performLibrarySearch(activeLibraryQuery);
        else
            showLibrary();
    }

    private boolean isLibraryChange(String scope) {
        return "recent".equals(scope)
                || "favorite".equals(scope)
                || "bookmark".equals(scope)
                || "pageBookmark".equals(scope);
    }

    private void refreshLibraryFromPreferences() {
        if(searchResult == null || getContext() == null || search != null)
            return;
        if(searchResult.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
            pendingLibraryRefresh = true;
            return;
        }
        if(activeLibraryQuery != null && activeLibraryQuery.length() > 0)
            performLibrarySearch(activeLibraryQuery);
        else
            showLibrary();
    }

    private void applyPendingLibraryRefreshIfIdle() {
        if(!pendingLibraryRefresh || searchResult == null)
            return;
        if(searchResult.getScrollState() != RecyclerView.SCROLL_STATE_IDLE)
            return;
        pendingLibraryRefresh = false;
        refreshLibraryFromPreferences();
    }

    private void scheduleVisibleResumeWarmup() {
        if(searchResult == null)
            return;
        searchResult.post(this::warmupVisibleResumeItems);
    }

    private void warmupVisibleResumeItems() {
        if(searchResult == null || searchAdapter == null)
            return;
        if(searchResult.getScrollState() != RecyclerView.SCROLL_STATE_IDLE)
            return;
        searchAdapter.warmupVisibleResumeItems(searchResult);
    }

    private void updateSwipeEnabled() {
        if(swipe != null)
            swipe.setEnabled(!libraryMode || onlineSearchMode);
    }

    private Title resolveLatestTitleForResume(Title title) {
        if(title == null)
            return null;
        if(isOfflineTitle(title)) {
            int bookmark = resolveSharedBookmark(title, title.getBookmark());
            if(bookmark > 0)
                title.setBookmark(bookmark);
            return title;
        }
        MTitle stored = p.findRecentTitle(title);
        if(stored == null)
            stored = p.findFavoriteTitle(title);
        if(stored == null)
            return title;
        Title latest = stored instanceof Title ? (Title) stored : new Title(stored);
        int bookmark = p.getBookmark(latest);
        if(bookmark <= 0)
            bookmark = latest.getBookmarkEpisodeId();
        if(bookmark > 0)
            latest.setBookmark(bookmark);
        return latest;
    }

    private Title resolveLatestTitleForEpisode(Title title) {
        if(title == null)
            return null;
        if(!"ntk".equals(title.getSourceSite()))
            return title;
        Title stored = chooseStoredTitleForEpisode(title, p.getRecent(), p.getFavorite());
        return stored == null ? title : stored;
    }

    static Title chooseStoredTitleForEpisodeForTest(Title title, List<MTitle> recent, List<MTitle> favorite) {
        return chooseStoredTitleForEpisode(title, recent, favorite);
    }

    private static Title chooseStoredTitleForEpisode(Title title, List<MTitle> recent, List<MTitle> favorite) {
        Title stored = storedTitleWithSameName(title, recent);
        if(stored == null)
            stored = storedTitleWithSameName(title, favorite);
        return stored;
    }

    private static Title storedTitleWithSameName(Title title, List<MTitle> source) {
        if(title == null || source == null || !"ntk".equals(title.getSourceSite()))
            return null;
        String name = normalizedTitleName(title);
        if(name.length() == 0)
            return null;
        for(MTitle stored : source) {
            if(!isUsableStoredNtkTitle(title, stored, name))
                continue;
            return stored instanceof Title ? (Title) stored : new Title(stored);
        }
        return null;
    }

    private static boolean isUsableStoredNtkTitle(Title title, MTitle stored, String normalizedName) {
        if(stored == null || stored.getId() <= 0)
            return false;
        if(stored.getId() == title.getId())
            return false;
        if(stored.getBaseMode() != title.getBaseMode())
            return false;
        if(!"ntk".equals(stored.getSourceSite()))
            return false;
        if(!normalizedName.equals(normalizedTitleName(stored)))
            return false;
        return stored.getBookmarkEpisodeId() > 0
                || stored.getBookmarkEpisodeIndex() > 0
                || stored.getEpisodeCount() > 0
                || (stored.getPath() != null && stored.getPath().length() > 0);
    }

    private static String normalizedTitleName(MTitle title) {
        String name = title == null ? null : title.getName();
        return name == null ? "" : name.trim().replaceAll("\\s+", " ");
    }

    static String normalizedTitleNameForTest(MTitle title) {
        return normalizedTitleName(title);
    }

    private MTitle findStoredTitle(Title title, List<MTitle> source) {
        if(title == null || source == null)
            return null;
        for(MTitle stored : source) {
            if(stored != null
                    && stored.getId() == title.getId()
                    && stored.getBaseMode() == title.getBaseMode()
                    && sameSourceSite(stored, title))
                return stored;
        }
        return null;
    }

    private int resolveLatestBookmark(Title title, int fallback) {
        if(title == null)
            return fallback;
        if(isOfflineTitle(title))
            return resolveSharedBookmark(title, fallback);
        return resolveSharedBookmark(title, fallback);
    }

    private int resolveSharedBookmark(Title title, int fallback) {
        if(title == null)
            return fallback;
        int bookmark = p.getBookmark(title);
        if(bookmark <= 0)
            bookmark = title.getBookmark();
        if(bookmark <= 0)
            bookmark = title.getBookmarkEpisodeId();
        if(bookmark <= 0)
            bookmark = fallback;
        if(bookmark > 0)
            title.setBookmark(bookmark);
        return bookmark;
    }

    private void openResume(Title title, int bookmark) {
        if(getContext() == null || title == null || bookmark <= 0)
            return;
        if(isOfflineTitle(title)) {
            openOfflineResume(title, bookmark);
            return;
        }
        if(title.getId() <= 0)
            return;
        Manga manga = new Manga(bookmark, "", "", title.getBaseMode());
        manga.setTitle(title);
        manga.setTitleId(title.getId());
        if("ntk".equals(title.getSourceSite()) && title.getResumeNtkEpisodePath().length() > 0)
            manga.setNtkEpisodePath(title.getResumeNtkEpisodePath());
        Utils.openContinueViewer(getContext(), manga, -1);
    }

    private void openOfflineResume(Title title, int bookmark) {
        Context context = getContext();
        if(context == null)
            return;
        Context appContext = context.getApplicationContext();
        AppDispatchers.submitIo(() -> {
            Manga manga = OfflineStore.resolveResumeManga(appContext, title, bookmark);
            AppDispatchers.runOnMain(() -> {
                if(getContext() == null)
                    return;
                if(manga == null) {
                    confirmOnlineResume(title, bookmark);
                    return;
                }
                manga.setTitle(title);
                manga.setTitleId(title.getId());
                openViewer(getContext(), manga, -1);
            });
        });
    }

    private void confirmOnlineResume(Title title, int bookmark) {
        if(getContext() == null || title == null || title.getId() <= 0 || bookmark <= 0)
            return;
        DialogInterface.OnClickListener listener = (dialog, which) -> {
            if(which == DialogInterface.BUTTON_POSITIVE)
                openOnlineResume(title, bookmark);
        };
        AlertDialog.Builder builder = p.getDarkTheme()
                ? new AlertDialog.Builder(getContext(), R.style.darkDialog)
                : new AlertDialog.Builder(getContext());
        builder.setMessage("해당 회차가 저장되어 있지 않습니다.\n온라인으로 이어보시겠습니까?")
                .setPositiveButton("네", listener)
                .setNegativeButton("아니오", listener)
                .show();
    }

    private void openOnlineResume(Title title, int bookmark) {
        Title onlineTitle = new Title(title.getName(), title.getThumb(), title.getAuthor(),
                title.getTags(), title.getRelease(), title.getId(), title.getBaseMode());
        onlineTitle.setSourceSite(title.getSourceSite());
        onlineTitle.setResumeNtkEpisodePath(title.getResumeNtkEpisodePath());
        onlineTitle.setBookmark(bookmark);
        Manga manga = new Manga(bookmark, "", "", title.getBaseMode());
        manga.setTitle(onlineTitle);
        manga.setTitleId(onlineTitle.getId());
        if("ntk".equals(onlineTitle.getSourceSite()) && onlineTitle.getResumeNtkEpisodePath().length() > 0)
            manga.setNtkEpisodePath(onlineTitle.getResumeNtkEpisodePath());
        Utils.openContinueViewer(getContext(), manga, -1);
    }

    private boolean isOfflineTitle(Title title) {
        return title != null && title.getPath() != null && title.getPath().length() > 0;
    }

    private void confirmDeleteOfflineTitle(Title title) {
        if(getContext() == null || !isOfflineTitle(title))
            return;
        DialogInterface.OnClickListener listener = (dialog, which) -> {
            if(which == DialogInterface.BUTTON_POSITIVE)
                deleteOfflineTitle(title);
        };
        AlertDialog.Builder builder = p.getDarkTheme()
                ? new AlertDialog.Builder(getContext(), R.style.darkDialog)
                : new AlertDialog.Builder(getContext());
        builder.setMessage(title.getName() + " 을(를) 저장됨에서 삭제하시겠습니까?")
                .setPositiveButton("네", listener)
                .setNegativeButton("아니오", listener)
                .show();
    }

    private void deleteOfflineTitle(Title title) {
        Context context = getContext();
        if(context == null || title == null)
            return;
        Context appContext = context.getApplicationContext();
        AppDispatchers.submitIo(() -> {
            boolean deleted = OfflineStore.deleteTitle(appContext, title);
            AppDispatchers.runOnMain(() -> {
                if(getContext() == null)
                    return;
                if(deleted) {
                    removeOfflineTitleFromCache(title);
                    Toast.makeText(getContext(), "삭제가 완료되었습니다.", Toast.LENGTH_SHORT).show();
                    refreshLibraryAfterOfflineDelete();
                } else {
                    showPopup(getContext(), "알림", "삭제를 실패했습니다");
                }
            });
        });
    }

    private void removeOfflineTitleFromCache(Title title) {
        if(title == null)
            return;
        for(int i = offlineTitles.size() - 1; i >= 0; i--) {
            Title stored = offlineTitles.get(i);
            if(stored == null)
                continue;
            boolean samePath = title.getPath() != null && title.getPath().equals(stored.getPath());
            boolean sameTitle = title.getId() > 0 && stored.getId() == title.getId() && stored.getBaseMode() == title.getBaseMode();
            if(samePath || sameTitle)
                offlineTitles.remove(i);
        }
    }

    private void refreshLibraryAfterOfflineDelete() {
        offlineTitlesLoaded = true;
        if(activeLibraryQuery != null && activeLibraryQuery.length() > 0)
            performLibrarySearch(activeLibraryQuery);
        else
            showLibrary();
    }

    private void scheduleTitleListLongPress() {
        cancelTitleListLongPress();
        if(searchResult == null)
            return;
        pendingListLongPress = () -> {
            pendingListLongPress = null;
            if(!isAdded() || getContext() == null || searchResult == null || searchAdapter == null)
                return;
            if(!listMovedBeyondTapSlop && listScrollState == RecyclerView.SCROLL_STATE_IDLE)
                listLongPressHandled = handleTitleListLongPress(listDownX, listDownY);
        };
        searchResult.postDelayed(pendingListLongPress, ViewConfiguration.getLongPressTimeout());
    }

    private void cancelTitleListLongPress() {
        if(searchResult != null && pendingListLongPress != null)
            searchResult.removeCallbacks(pendingListLongPress);
        pendingListLongPress = null;
    }

    private boolean movedBeyondListTapSlop(MotionEvent event) {
        if(getContext() == null)
            return false;
        int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        return Math.abs(event.getX() - listDownX) > slop || Math.abs(event.getY() - listDownY) > slop;
    }

    private boolean handleTitleListTap(float x, float y) {
        if(searchResult == null || searchAdapter == null || getContext() == null)
            return false;
        View item = findTitleListItemAt(x, y);
        if(item == null)
            return false;
        int position = positionForTitleListItem(item);
        if(position == RecyclerView.NO_POSITION || position >= searchAdapter.getItemCount())
            return false;
        boolean resumeTap = listDownOnResume && isTouchOnResumeButton(x, y);
        listDownOnResume = false;
        if(resumeTap)
            return searchAdapter.performResumeClick(position);
        return searchAdapter.performItemClick(position);
    }

    private void warmupResumeAtTouch(float x, float y) {
        if(searchResult == null || searchAdapter == null)
            return;
        View item = findTitleListItemAt(x, y);
        if(item == null)
            return;
        int position = positionForTitleListItem(item);
        if(position != RecyclerView.NO_POSITION)
            searchAdapter.warmupResumeClick(position);
    }

    private boolean handleTitleListLongPress(float x, float y) {
        if(searchResult == null || searchAdapter == null || getContext() == null)
            return false;
        View item = findTitleListItemAt(x, y);
        if(item == null)
            return false;
        int position = positionForTitleListItem(item);
        if(position == RecyclerView.NO_POSITION || position >= searchAdapter.getItemCount())
            return false;
        return searchAdapter.performItemLongClick(item, position);
    }

    private int positionForTitleListItem(View item) {
        int position = searchResult.getChildAdapterPosition(item);
        if(position == RecyclerView.NO_POSITION)
            position = searchResult.getChildLayoutPosition(item);
        return position;
    }

    private View findTitleListItemAt(float x, float y) {
        if(searchResult == null)
            return null;
        View child = searchResult.findChildViewUnder(x, y);
        if(child == null)
            return null;
        return searchResult.findContainingItemView(child);
    }

    private boolean isTouchOnResumeButton(float recyclerX, float recyclerY) {
        if(searchResult == null)
            return false;
        View item = findTitleListItemAt(recyclerX, recyclerY);
        if(item == null)
            return false;
        View resume = item.findViewById(R.id.epsButton);
        if(resume == null || resume.getVisibility() != View.VISIBLE || !(item instanceof ViewGroup))
            return false;
        Rect rect = new Rect(0, 0, resume.getWidth(), resume.getHeight());
        ((ViewGroup) item).offsetDescendantRectToMyCoords(resume, rect);
        int childX = Math.round(recyclerX - item.getLeft());
        int childY = Math.round(recyclerY - item.getTop());
        return rect.contains(childX, childY);
    }

    private void appendUnique(ArrayList<Title> target, List<?> source) {
        if(target == null || source == null)
            return;
        for(Object item : source) {
            if(!(item instanceof MTitle))
                continue;
            Title title = item instanceof Title ? (Title) item : new Title((MTitle) item);
            boolean exists = false;
            for(Title existing : target) {
                if(title.getId() > 0 && existing.getBaseMode() == title.getBaseMode() && existing.getId() == title.getId()) {
                    if(sameSourceSite(existing, title)) {
                        exists = true;
                        break;
                    }
                }
                if(title.getId() <= 0 && title.getPath() != null && title.getPath().equals(existing.getPath())) {
                    exists = true;
                    break;
                }
                if(title.getId() <= 0 && title.getPath() == null && title.getName() != null && title.getName().equals(existing.getName())) {
                    exists = true;
                    break;
                }
            }
            if(!exists)
                target.add(title);
        }
    }

    private boolean sameSourceSite(MTitle left, MTitle right) {
        String leftSource = sourceSiteKey(left);
        String rightSource = sourceSiteKey(right);
        return leftSource.equals(rightSource);
    }

    private String sourceSiteKey(MTitle title) {
        if(title == null || p == null)
            return "";
        String source = title.getSourceSite();
        if(source == null || source.length() == 0)
            source = p.resolveKnownSourceSite(title);
        return source == null ? "" : source;
    }

    private boolean keyCodeIsEnter(KeyEvent event) {
        return event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
    }

    private void applyPendingSearch() {
        if(prequery == null || searchBox == null)
            return;
        enterSearchMode();
        searchBox.setText(prequery);
        searchBox.setSelection(searchBox.getText().length());
        prequery = null;
        onlineSearchMode = true;
        searchSubmitOnline();
        searchBox.postDelayed(this::hideKeyboard, 120);
    }

    void searchSubmit(){
        String query = searchBox.getText().toString().trim();
        if(query.length() < 2) {
            showMinimumSearchLengthToast();
            return;
        }
        if(onlineSearchMode || !libraryMode) {
            searchSubmitOnline();
            return;
        }
        performLibrarySearch(query);
    }

    private void performLibrarySearch(String query) {
        if(getContext() == null)
            return;
        onlineSearchMode = false;
        activeLibraryQuery = query;
        search = null;
        activeSearchKey = null;
        if(searchTask != null) {
            searchTask.cancel(true);
            searchTask = null;
        }
        hideKeyboard();
        int tab = getLibraryTabPosition();
        loadOfflineTitlesIfNeeded(tab);
        if(libraryFilterTask != null)
            libraryFilterTask.cancel();
        final int generation = ++libraryFilterGeneration;
        final String filterQuery = query;
        final ArrayList<Title> source = getLibraryTitles(tab);
        libraryFilterTask = AppDispatchers.submitUiDiff(() -> {
            ArrayList<Title> data = new ArrayList<>();
            for(Title title : source)
                if(matchesLibraryQuery(title, filterQuery))
                    data.add(title);
            AppDispatchers.runOnMain(() -> {
                if(generation != libraryFilterGeneration || getContext() == null)
                    return;
                libraryFilterTask = null;
                bindLibraryData(data, "서재에서 \"" + filterQuery + "\" 검색 결과가 없습니다");
            });
        });
    }

    private boolean matchesLibraryQuery(Title title, String query) {
        if(title == null || query == null)
            return false;
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        if(normalized.length() == 0)
            return true;
        if(containsIgnoreCase(title.getName(), normalized))
            return true;
        if(containsIgnoreCase(title.getAuthor(), normalized))
            return true;
        if(containsIgnoreCase(title.getRelease(), normalized))
            return true;
        if(containsIgnoreCase(title.getBaseModeStr(), normalized))
            return true;
        List<String> tags = title.getTags();
        if(tags != null)
            for(String tag : tags)
                if(containsIgnoreCase(tag, normalized))
                    return true;
        return false;
    }

    static boolean matchesLibraryQueryForTest(Title title, String query) {
        return new MainSearch().matchesLibraryQuery(title, query);
    }

    private boolean containsIgnoreCase(String value, String normalizedQuery) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(normalizedQuery);
    }

    private void hideKeyboard() {
        if(searchBox == null)
            return;
        searchBox.clearFocus();
        InputMethodManager imm = (InputMethodManager) searchBox.getContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if(imm != null)
            imm.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
    }

    private void searchSubmitOnline(){
        String query = searchBox.getText().toString().trim();
        if(query.length() < 2) {
            showMinimumSearchLengthToast();
            return;
        }
        if(query.length()>0) {
            onlineSearchMode = true;
            updateSearchChrome(true);
            activeLibraryQuery = null;
            hideKeyboard();
            swipe.setRefreshing(true);
            String key = searchKey(query);
            if(searchTask != null && key.equals(activeSearchKey))
                return;
            if(searchAdapter == null)
                searchAdapter = new TitleAdapter(getContext());
            searchAdapter.setDeferThumbnails(true);
            bindOnlineAdapter();
            if(noresult != null)
                noresult.setVisibility(View.GONE);
            updateAdvSearchVisibility();
            int selectedBaseMode = selectedSearchBaseMode();
            search = MangaRepository.createSearch(query, searchMode.getSelectedItemPosition(), selectedBaseMode);
            if(searchTask != null)
                searchTask.cancel(true);
            activeSearchKey = key;
            searchFirstStartedAt = PerfTrace.start("search_first_result_ms");
            searchTask = new SearchManga(search, true, suppressNextAutoCaptchaOpen);
            suppressNextAutoCaptchaOpen = false;
            searchTask.start();
        }
    }

    private String searchKey(String query) {
        return query + "\u001f" + searchMode.getSelectedItemPosition() + "\u001f" + selectedSearchBaseMode();
    }

    private void updateSearchChrome(boolean online) {
        if(optionsPanel != null)
            optionsPanel.setVisibility(online || !libraryMode ? View.VISIBLE : View.GONE);
        if(libraryTab != null)
            libraryTab.setVisibility(online ? View.GONE : View.VISIBLE);
        if(libraryMeta != null)
            libraryMeta.setVisibility(online ? View.GONE : View.VISIBLE);
        if(!libraryMode) {
            if(libraryTab != null)
                libraryTab.setVisibility(View.GONE);
            if(libraryMeta != null)
                libraryMeta.setVisibility(View.GONE);
        }
    }

    private void bindOnlineAdapter() {
        if(searchResult == null || searchAdapter == null)
            return;
        searchAdapter.setResume(true);
        searchAdapter.setForceThumbnail(false);
        searchAdapter.setLongClickEnabled(true);
        if(searchResult.getAdapter() != searchAdapter)
            searchResult.setAdapter(searchAdapter);
        searchAdapter.setClickListener(new TitleAdapter.ItemClickListener() {
            @Override
            public void onLongClick(View view, int position) {
                Title title = searchAdapter.getItem(position);
                if(title == null)
                    return;
                popup(getContext(),view, position, title, 0, item -> {
                    switch(item.getItemId()){
                        case R.id.favAdd:
                        case R.id.favDel:
                            p.toggleFavorite(title,0);
                            break;
                    }
                    return false;
                }, p);
            }

            @Override
            public void onResumeClick(int position, int id) {
                Title title = resolveLatestTitleForResume(searchAdapter.getItem(position));
                if(title == null)
                    return;
                int bookmark = resolveLatestBookmark(title, id);
                if(!canLaunchDestination())
                    return;
                openResume(title, bookmark);
            }

            @Override
            public void onItemClick(int position) {
                Title title = searchAdapter.getItem(position);
                if(title == null)
                    return;
                if(!canLaunchDestination())
                    return;
                Intent episodeView = episodeIntent(getContext(), title);
                startActivity(episodeView);
            }
        });
    }

    private boolean canLaunchDestination() {
        return isAdded()
                && isResumed()
                && Utils.consumeFocusedDestinationLaunch(getActivity(), DESTINATION_LAUNCH_DEBOUNCE_MS);
    }

    private void releaseDeferredSearchThumbnails() {
        if(searchResult == null || searchAdapter == null)
            return;
        searchResult.post(() -> {
            if(getContext() != null && searchAdapter != null)
                searchAdapter.releaseDeferredThumbnails(searchResult);
        });
    }

    public void selectLibraryTab(int position) {
        if(!libraryMode)
            return;
        if(libraryTab == null) {
            pendingOpenSearch = false;
            return;
        }
        if(position >= 0 && position < libraryTab.getTabCount()) {
            TabLayout.Tab tab = libraryTab.getTabAt(position);
            if(tab != null)
                tab.select();
        }
        enterLibraryMode();
    }

    private void showMinimumSearchLengthToast() {
        if(getContext() != null)
            Toast.makeText(getContext(), "최소 2글자 이상 입력해주세요", Toast.LENGTH_SHORT).show();
    }

    private void updateAdvSearchVisibility() {
        if(advSearchBtn == null || searchBox == null)
            return;
        advSearchBtn.setVisibility(View.GONE);
    }


    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(resultCode == RESULT_CAPTCHA && searchAdapter!=null && search != null) {
            suppressNextAutoCaptchaOpen = true;
            searchSubmit();
        }
    }

    @Override
    public void onDestroyView() {
        keyboardShowGeneration++;
        cancelTitleListLongPress();
        if(localChangeListener != null) {
            p.removeLocalChangeListener(localChangeListener);
            localChangeListener = null;
        }
        if(searchTask != null)
            searchTask.cancel(true);
        if(offlineTask != null)
            offlineTask.cancel(true);
        if(libraryFilterTask != null)
            libraryFilterTask.cancel();
        libraryFilterGeneration++;
        activeSearchKey = null;
        if(searchResult != null) {
            searchResult.stopScroll();
            searchResult.setAdapter(null);
        }
        searchAdapter = null;
        super.onDestroyView();
    }

    private class LoadOfflineTitles {
        private final Context appContext;
        private AppDispatchers.TaskHandle handle;
        private volatile boolean cancelled = false;

        LoadOfflineTitles() {
            Context context = getContext();
            appContext = context == null ? null : context.getApplicationContext();
        }

        void start() {
            handle = AppDispatchers.submitIo(() -> {
                ArrayList<Title> titles = OfflineStore.loadTitles(appContext);
                AppDispatchers.runOnMain(() -> finish(titles));
            });
        }

        private void finish(ArrayList<Title> titles) {
            if(cancelled)
                return;
            if(offlineTask == this)
                offlineTask = null;
            offlineTitles = titles == null ? new ArrayList<>() : titles;
            offlineTitlesLoaded = true;
            invalidateLibrarySnapshots("offline");
            if(search == null) {
                if(searchResult != null && searchResult.getScrollState() != RecyclerView.SCROLL_STATE_IDLE)
                    pendingLibraryRefresh = true;
                else
                    showLibrary();
            }
        }

        boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            if(offlineTask == this)
                offlineTask = null;
            return handle == null || handle.cancel();
        }
    }

    private class SearchManga {
        private final Search targetSearch;
        private final boolean replaceResults;
        private final boolean suppressAutoCaptchaOpen;
        private MangaRepository.Cancellation cancellation;
        private AppDispatchers.TaskHandle handle;
        private volatile boolean cancelled = false;
        private int partialResultCount = 0;
        private Exception searchFailure;
        private long loadStartedAt;

        SearchManga(Search targetSearch, boolean replaceResults, boolean suppressAutoCaptchaOpen) {
            this.targetSearch = targetSearch;
            this.replaceResults = replaceResults;
            this.suppressAutoCaptchaOpen = suppressAutoCaptchaOpen;
        }

        void start() {
            targetSearch.setPartialResultListener(titles -> {
                if(titles == null || titles.size() == 0)
                    return;
                ArrayList<Title> snapshot = new ArrayList<>(titles);
                AppDispatchers.runOnMain(() -> showPartialResults(snapshot));
            });
            long queuedAt = PerfTrace.start("search_task_queue_ms");
            handle = AppDispatchers.submitSearch(() -> {
                PerfTrace.end("search_task_queue_ms", queuedAt);
                Integer result = load();
                AppDispatchers.runOnMain(() -> finish(result));
            });
        }

        private void showPartialResults(ArrayList<Title> titles) {
            if(cancelled || targetSearch != search || getContext() == null || !replaceResults)
                return;
            if(titles == null || titles.size() <= partialResultCount)
                return;
            partialResultCount = titles.size();
            searchAdapter.setDataImmediate(titles);
            bindOnlineAdapter();
            scheduleVisibleResumeWarmup();
            noresult.setVisibility(View.GONE);
            if(searchFirstStartedAt > 0) {
                PerfTrace.end("search_first_result_ms", searchFirstStartedAt);
                searchFirstStartedAt = 0L;
                releaseDeferredSearchThumbnails();
            }
        }

        private Integer load() {
            cancellation = MangaRepository.cancellation();
            loadStartedAt = System.currentTimeMillis();
            try {
                return MangaRepository.search(targetSearch, cancellation);
            } catch (Exception e) {
                searchFailure = e;
                if(!cancelled && MangaRepository.shouldReportSearchFailure(e))
                    ml.melun.mangaview.report.CrashReporter.record(e);
                return 1;
            }
        }

        private void finish(Integer res) {
            if(searchTask == this) {
                searchTask = null;
                activeSearchKey = null;
            }
            targetSearch.setPartialResultListener(null);
            if(cancelled || targetSearch != search || getContext() == null)
                return;
            if(res == null)
                res = 1;
            boolean captchaRequired = !suppressAutoCaptchaOpen && shouldOpenCaptchaAfterSearchFailure(res, loadStartedAt);
            if(captchaRequired)
                Utils.showCaptchaPopup(getContext(), RESULT_CAPTCHA, MainSearch.this, p);
            if(replaceResults) {
                searchAdapter.setDataImmediate(new ArrayList<>(targetSearch.getResult()));
                bindOnlineAdapter();
            }else{
                searchAdapter.addData(new ArrayList<>(targetSearch.getResult()));
            }
            scheduleVisibleResumeWarmup();

            List<Title> latestResults = targetSearch.getResult();
            boolean hasResults = (latestResults != null && latestResults.size() > 0)
                    || (!replaceResults && searchAdapter.getItemCount() > 0);

            if(hasResults) {
                noresult.setVisibility(View.GONE);
                if(replaceResults && searchFirstStartedAt > 0) {
                    PerfTrace.end("search_first_result_ms", searchFirstStartedAt);
                    searchFirstStartedAt = 0L;
                    releaseDeferredSearchThumbnails();
                }
            }else{
                noResultText.setText("\"" + targetSearch.getQuery() + "\" 검색 결과가 없습니다");
                if(res != 0 && !captchaRequired)
                    noResultText.setText("\"" + targetSearch.getQuery() + "\" \uac80\uc0c9 \uacb0\uacfc\ub97c \ubd88\ub7ec\uc624\uc9c0 \ubabb\ud588\uc2b5\ub2c8\ub2e4.\n\ub124\ud2b8\uc6cc\ud06c \ub610\ub294 \uc0ac\uc774\ud2b8 \uc8fc\uc18c\ub97c \ud655\uc778\ud55c \ub4a4 \ub2e4\uc2dc \uc2dc\ub3c4\ud574 \uc8fc\uc138\uc694.");
                noresult.setVisibility(View.VISIBLE);
            }

            swipe.setRefreshing(false);
        }

        boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            targetSearch.setPartialResultListener(null);
            if(cancellation != null)
                cancellation.cancel();
            if(searchTask == this) {
                searchTask = null;
                activeSearchKey = null;
                if(swipe != null)
                    swipe.setRefreshing(false);
            }
            return handle == null || handle.cancel();
        }
    }

    private static boolean shouldOpenCaptchaAfterSearchFailure(int result, long loadStartedAt) {
        return result != 0 && getHttpClient().hasCloudflareChallengeSince(loadStartedAt);
    }
}
