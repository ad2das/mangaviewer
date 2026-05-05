package ml.melun.mangaview.fragment;

import android.content.Intent;
import android.net.Uri;
import ml.melun.mangaview.task.LifecycleTask;
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
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.appcompat.widget.PopupMenu;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.tabs.TabLayout;
import com.bumptech.glide.Glide;
import com.omadahealth.github.swipyrefreshlayout.library.SwipyRefreshLayout;
import com.omadahealth.github.swipyrefreshlayout.library.SwipyRefreshLayoutDirection;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import ml.melun.mangaview.ui.NpaLinearLayoutManager;
import ml.melun.mangaview.R;
import ml.melun.mangaview.Preference;
import ml.melun.mangaview.Utils;
import ml.melun.mangaview.adapter.TitleAdapter;
import ml.melun.mangaview.activity.AdvSearchActivity;
import ml.melun.mangaview.mangaview.CustomHttpClient;
import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Search;
import ml.melun.mangaview.mangaview.Title;

import static ml.melun.mangaview.MainApplication.httpClient;
import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.episodeIntent;
import static ml.melun.mangaview.Utils.openViewer;
import static ml.melun.mangaview.Utils.popup;
import static ml.melun.mangaview.Utils.readFileToString;
import static ml.melun.mangaview.Utils.readUriToString;
import static ml.melun.mangaview.Utils.useScopedStorageHome;
import static ml.melun.mangaview.activity.CaptchaActivity.RESULT_CAPTCHA;

public class MainSearch extends Fragment {
    SwipyRefreshLayout swipe;
    FloatingActionButton advSearchBtn;
    TextView noresult;
    private EditText searchBox;
    TextView searchSubmitButton;
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
    TabLayout libraryTab;
    ArrayList<Title> offlineTitles = new ArrayList<>();
    LoadOfflineTitles offlineTask;
    int pendingBaseMode = -1;
    String activeLibraryQuery = null;
    Preference.LocalChangeListener localChangeListener;
    float listDownX;
    float listDownY;
    long listDownTime;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        ViewGroup rootView = (ViewGroup) inflater.inflate(R.layout.content_search , container, false);

        //search content
        noresult = rootView.findViewById(R.id.noResult);
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
                if(getContext() == null)
                    return;
                if(newState == RecyclerView.SCROLL_STATE_IDLE)
                    Glide.with(MainSearch.this).resumeRequests();
                else
                    Glide.with(MainSearch.this).pauseRequests();
            }
        });
        searchResult.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent event) {
                if(event.getAction() == MotionEvent.ACTION_DOWN) {
                    listDownX = event.getX();
                    listDownY = event.getY();
                    listDownTime = event.getEventTime();
                    return false;
                }
                if(event.getAction() != MotionEvent.ACTION_UP)
                    return false;
                if(Math.abs(event.getX() - listDownX) > dp(12) || Math.abs(event.getY() - listDownY) > dp(12))
                    return false;
                if(event.getEventTime() - listDownTime >= ViewConfiguration.getLongPressTimeout())
                    return false;
                return handleTitleListTap(event.getX(), event.getY());
            }
        });
        searchMode = rootView.findViewById(R.id.searchMode);
        baseMode = rootView.findViewById(R.id.searchBaseMode);
        advSearchBtn = rootView.findViewById(R.id.advSearchBtn);
        swipe = rootView.findViewById(R.id.searchSwipe);
        optionsPanel = rootView.findViewById(R.id.searchOptionPanel);
        libraryCount = rootView.findViewById(R.id.libraryCount);
        libraryTab = rootView.findViewById(R.id.libraryTab);
        fragment = this;
        localChangeListener = scope -> {
            if(!isLibraryChange(scope) || searchResult == null)
                return;
            searchResult.post(this::refreshLibraryFromPreferences);
        };
        p.addLocalChangeListener(localChangeListener);
        setupLibraryTabs();
        if(p.getDarkTheme()){
            searchMode.setPopupBackgroundResource(R.color.colorDarkWindowBackground);
            baseMode.setPopupBackgroundResource(R.color.colorDarkWindowBackground);
        }

        searchBox.setOnFocusChangeListener((view, b) -> {
            optionsPanel.setVisibility(View.GONE);
            updateAdvSearchVisibility();
        });

        advSearchBtn.setOnClickListener(v -> {
            if(getContext() == null)
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

        baseMode.setSelection(p.getBaseMode()-1);
        if(pendingBaseMode > 0)
            applyBaseMode(pendingBaseMode);
        showLibrary();
        if(pendingOpenSearch)
            enterSearchMode();



        swipe.setOnRefreshListener(direction -> {
            if(search==null) swipe.setRefreshing(false);
            else {
                if (!search.isLast()) {
                    if(searchTask == null) {
                        activeSearchKey = null;
                        searchTask = new SearchManga(search);
                        searchTask.executeOnExecutor(LifecycleTask.USER_ACTION_EXECUTOR);
                    }
                } else swipe.setRefreshing(false);
            }
        });
        return rootView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if(prequery != null){
            applyPendingSearch();
        } else if(search == null) {
            showLibrary();
        }
    }

    void optionUpdate(){
        //shows or hides options
        //p.setBaseMode(baseMode.getSelectedItemPosition()+1);
    }

    public void setSearch(String prequery){
        this.prequery = prequery;
        if(searchBox != null)
            applyPendingSearch();
    }

    public void setBaseMode(int mode) {
        pendingBaseMode = mode;
        if(baseMode != null)
            applyBaseMode(mode);
    }

    private void applyBaseMode(int mode) {
        int position = mode - 1;
        if(position < 0 || position >= baseMode.getCount())
            return;
        baseMode.setSelection(position);
    }

    public void enterSearchMode() {
        pendingOpenSearch = true;
        if(searchBox == null)
            return;
        pendingOpenSearch = false;
        searchBox.setVisibility(View.VISIBLE);
        if(optionsPanel != null)
            optionsPanel.setVisibility(View.GONE);
        searchBox.requestFocus();
        updateAdvSearchVisibility();
        searchBox.post(() -> {
            if(getContext() == null)
                return;
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if(imm != null)
                imm.showSoftInput(searchBox, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void setupLibraryTabs() {
        if(libraryTab == null || libraryTab.getTabCount() > 0)
            return;
        libraryTab.addTab(libraryTab.newTab().setText("전체"));
        libraryTab.addTab(libraryTab.newTab().setText("최근"));
        libraryTab.addTab(libraryTab.newTab().setText("보관함"));
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
        if(searchResult == null || getContext() == null)
            return;
        onlineSearchMode = false;
        if(activeLibraryQuery != null && activeLibraryQuery.length() > 0) {
            performLibrarySearch(activeLibraryQuery);
            return;
        }
        if(searchAdapter == null)
            searchAdapter = new TitleAdapter(getContext());
        searchAdapter.setResume(true);
        searchAdapter.setForceThumbnail(false);
        int tab = getLibraryTabPosition();
        ArrayList<Title> data = getLibraryTitles(tab);
        if((tab == 0 || tab == 3) && offlineTitles.size() == 0 && offlineTask == null) {
            offlineTask = new LoadOfflineTitles();
            offlineTask.executeOnExecutor(LifecycleTask.THREAD_POOL_EXECUTOR);
        }
        bindLibraryData(data, libraryEmptyMessage(tab));
    }

    private int getLibraryTabPosition() {
        return libraryTab == null ? 0 : libraryTab.getSelectedTabPosition();
    }

    private ArrayList<Title> getLibraryTitles(int tab) {
        ArrayList<Title> data = new ArrayList<>();
        if(tab == 1) {
            appendUnique(data, p.getRecent());
        } else if(tab == 2) {
            appendUnique(data, p.getFavorite());
        } else if(tab == 3) {
            appendUnique(data, offlineTitles);
        } else {
            appendUnique(data, p.getRecent());
            appendUnique(data, p.getFavorite());
            appendUnique(data, offlineTitles);
        }
        return data;
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
        searchAdapter.setData(data);
        searchResult.setAdapter(searchAdapter);
        updateAdvSearchVisibility();
        if(swipe != null)
            swipe.setRefreshing(false);
        if(libraryCount != null)
            libraryCount.setText(data.size() + "개 작품");
        noresult.setText(emptyMessage);
        noresult.setVisibility(data.size() == 0 ? View.VISIBLE : View.GONE);
        searchAdapter.setClickListener(new TitleAdapter.ItemClickListener() {
            @Override
            public void onLongClick(View view, int position) {
                Title title = searchAdapter.getItem(position);
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
                int bookmark = resolveLatestBookmark(title, id);
                openResume(title, bookmark);
            }

            @Override
            public void onItemClick(int position) {
                Title title = searchAdapter.getItem(position);
                if(isOfflineTitle(title)) {
                    Intent episodeView = episodeIntent(getContext(), title);
                    episodeView.putExtra("online", false);
                    startActivity(episodeView);
                } else if(title.getId() > 0) {
                    startActivity(episodeIntent(getContext(), title));
                }
            }
        });
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
        if(activeLibraryQuery != null && activeLibraryQuery.length() > 0)
            performLibrarySearch(activeLibraryQuery);
        else
            showLibrary();
    }

    private Title resolveLatestTitleForResume(Title title) {
        if(title == null)
            return null;
        MTitle stored = findStoredTitle(title, p.getRecent());
        if(stored == null)
            stored = findStoredTitle(title, p.getFavorite());
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

    private MTitle findStoredTitle(Title title, List<MTitle> source) {
        if(title == null || source == null)
            return null;
        for(MTitle stored : source) {
            if(stored != null
                    && stored.getId() == title.getId()
                    && stored.getBaseMode() == title.getBaseMode())
                return stored;
        }
        return null;
    }

    private int resolveLatestBookmark(Title title, int fallback) {
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
        if(getContext() == null || title == null || title.getId() <= 0 || bookmark <= 0)
            return;
        Manga manga = new Manga(bookmark, "", "", title.getBaseMode());
        manga.setTitle(title);
        manga.setTitleId(title.getId());
        openViewer(getContext(), manga, -1);
    }

    private boolean isOfflineTitle(Title title) {
        return title != null && title.getPath() != null && title.getPath().length() > 0;
    }

    private boolean handleTitleListTap(float x, float y) {
        if(searchResult == null || searchAdapter == null || getContext() == null)
            return false;
        View child = searchResult.findChildViewUnder(x, y);
        if(child == null)
            return false;
        int position = searchResult.getChildAdapterPosition(child);
        if(position == RecyclerView.NO_POSITION || position >= searchAdapter.getItemCount())
            return false;
        Title title = searchAdapter.getItem(position);
        if(title == null)
            return false;
        if(x >= child.getRight() - dp(96)) {
            Title latest = resolveLatestTitleForResume(title);
            int bookmark = resolveLatestBookmark(latest, title.getBookmark());
            openResume(latest, bookmark);
        } else {
            openTitleFromList(title);
        }
        return true;
    }

    private void openTitleFromList(Title title) {
        if(title == null || getContext() == null)
            return;
        if(isOfflineTitle(title)) {
            Intent episodeView = episodeIntent(getContext(), title);
            episodeView.putExtra("online", false);
            startActivity(episodeView);
        } else if(title.getId() > 0) {
            startActivity(episodeIntent(getContext(), title));
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
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
                    exists = true;
                    break;
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
    }

    void searchSubmit(){
        String query = searchBox.getText().toString().trim();
        if(query.length() < 2) {
            showMinimumSearchLengthToast();
            return;
        }
        searchSubmitOnline();
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
        if((tab == 0 || tab == 3) && offlineTitles.size() == 0 && offlineTask == null) {
            offlineTask = new LoadOfflineTitles();
            offlineTask.executeOnExecutor(LifecycleTask.THREAD_POOL_EXECUTOR);
        }
        ArrayList<Title> data = new ArrayList<>();
        for(Title title : getLibraryTitles(tab))
            if(matchesLibraryQuery(title, query))
                data.add(title);
        bindLibraryData(data, "서재에서 \"" + query + "\" 검색 결과가 없습니다");
    }

    private boolean matchesLibraryQuery(Title title, String query) {
        if(title == null || query == null)
            return false;
        String normalized = query.trim().toLowerCase();
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
        for(String tag : title.getTags())
            if(containsIgnoreCase(tag, normalized))
                return true;
        return false;
    }

    private boolean containsIgnoreCase(String value, String normalizedQuery) {
        return value != null && value.toLowerCase().contains(normalizedQuery);
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
            activeLibraryQuery = null;
            hideKeyboard();
            swipe.setRefreshing(true);
            String key = searchKey(query);
            if(searchTask != null && key.equals(activeSearchKey))
                return;
            if(searchAdapter != null) searchAdapter.removeAll();
            else searchAdapter = new TitleAdapter(getContext());
            updateAdvSearchVisibility();
            search = new Search(query,searchMode.getSelectedItemPosition(), baseMode.getSelectedItemPosition()+1);
            if(searchTask != null)
                searchTask.cancel(true);
            activeSearchKey = key;
            searchTask = new SearchManga(search);
            searchTask.executeOnExecutor(LifecycleTask.USER_ACTION_EXECUTOR);
        }
    }

    private String searchKey(String query) {
        return query + "\u001f" + searchMode.getSelectedItemPosition() + "\u001f" + (baseMode.getSelectedItemPosition() + 1);
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
        if(resultCode == RESULT_CAPTCHA && searchAdapter!=null && search != null)
            searchSubmit();
    }

    @Override
    public void onDestroyView() {
        if(localChangeListener != null) {
            p.removeLocalChangeListener(localChangeListener);
            localChangeListener = null;
        }
        if(searchTask != null)
            searchTask.cancel(true);
        if(offlineTask != null)
            offlineTask.cancel(true);
        activeSearchKey = null;
        super.onDestroyView();
    }

    private class LoadOfflineTitles extends LifecycleTask<Void, Void, ArrayList<Title>> {
        @Override
        protected ArrayList<Title> doInBackground(Void... voids) {
            ArrayList<Title> titles = new ArrayList<>();
            if(getContext() == null)
                return titles;
            if(useScopedStorageHome(p.getHomeDir())) {
                Uri uri = Uri.parse(p.getHomeDir());
                DocumentFile home;
                try {
                    home = DocumentFile.fromTreeUri(getContext(), uri);
                } catch (IllegalArgumentException e) {
                    return titles;
                }
                if(home == null || !home.canRead())
                    return titles;
                for(DocumentFile f : home.listFiles()) {
                    if(isCancelled())
                        return titles;
                    if(f.isDirectory())
                        titles.add(readOfflineTitle(f));
                }
            } else {
                File homeDir = new File(p.getHomeDir());
                File[] files = homeDir.exists() ? homeDir.listFiles() : null;
                if(files == null)
                    return titles;
                for(File f : files) {
                    if(isCancelled())
                        return titles;
                    if(f.isDirectory())
                        titles.add(readOfflineTitle(f));
                }
            }
            return titles;
        }

        @Override
        protected void onPostExecute(ArrayList<Title> titles) {
            super.onPostExecute(titles);
            if(offlineTask == this)
                offlineTask = null;
            offlineTitles = titles == null ? new ArrayList<>() : titles;
            if(search == null)
                showLibrary();
        }

        @Override
        protected void onCancelled(ArrayList<Title> titles) {
            super.onCancelled(titles);
            if(offlineTask == this)
                offlineTask = null;
        }

        private Title readOfflineTitle(DocumentFile folder) {
            DocumentFile data = folder.findFile("title.gson");
            if(data != null) {
                try {
                    Title title = new Gson().fromJson(readUriToString(getContext(), data.getUri()), new TypeToken<Title>() {
                    }.getType());
                    title.setPath(folder.getUri().toString());
                    String thumb = title.getThumb();
                    if(thumb != null && thumb.length() > 0) {
                        DocumentFile t = folder.findFile(thumb);
                        if(t != null)
                            title.setThumb(t.getUri().toString());
                    }
                    return title;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            Title title = new Title(folder.getName(), "", "", new ArrayList<>(), "", 0, MTitle.base_auto);
            title.setPath(folder.getUri().toString());
            return title;
        }

        private Title readOfflineTitle(File folder) {
            File data = new File(folder, "title.gson");
            if(data.exists()) {
                try {
                    Title title = new Gson().fromJson(readFileToString(data), new TypeToken<Title>() {
                    }.getType());
                    title.setPath(folder.getAbsolutePath());
                    String thumb = title.getThumb();
                    if(thumb != null && thumb.length() > 0)
                        title.setThumb(folder.getAbsolutePath() + '/' + thumb);
                    return title;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            Title title = new Title(folder.getName(), "", "", new ArrayList<>(), "", 0, MTitle.base_auto);
            title.setPath(folder.getAbsolutePath());
            return title;
        }
    }

    private class SearchManga extends LifecycleTask<Void, Void, Integer>{
        private final Search targetSearch;
        private CustomHttpClient.RequestGroup requestGroup;

        SearchManga(Search targetSearch) {
            this.targetSearch = targetSearch;
        }

        protected void onPreExecute(){
            super.onPreExecute();
        }
        protected Integer doInBackground(Void... params){
            requestGroup = new CustomHttpClient.RequestGroup();
            try {
                return httpClient.runWithRequestGroup(requestGroup, () -> targetSearch.fetch(httpClient));
            } catch (Exception e) {
                if(!isCancelled())
                    e.printStackTrace();
                return 1;
            }
        }
        @Override
        protected void onPostExecute(Integer res){
            super.onPostExecute(res);
            if(searchTask == this) {
                searchTask = null;
                activeSearchKey = null;
            }
            if(isCancelled() || targetSearch != search || getContext() == null)
                return;
            if(res == null)
                res = 1;
            if(res != 0){
                // error
                Utils.showCaptchaPopup(getContext(), 4, fragment, p);
            }

            if(searchAdapter.getItemCount()==0) {
                searchAdapter.addData(targetSearch.getResult());
                searchResult.setAdapter(searchAdapter);
                searchAdapter.setClickListener(new TitleAdapter.ItemClickListener() {
                    @Override
                    public void onLongClick(View view, int position) {
                        //none
                        Title title = searchAdapter.getItem(position);
                        popup(getContext(),view, position, title, 0, item -> {
                            switch(item.getItemId()){
                                case R.id.favAdd:
                                case R.id.favDel:
                                    //toggle favorite
                                    p.toggleFavorite(title,0);
                                    break;
                            }
                            return false;
                        }, p);
                    }

                    @Override
                    public void onResumeClick(int position, int id) {
                        Title title = resolveLatestTitleForResume(searchAdapter.getItem(position));
                        int bookmark = resolveLatestBookmark(title, id);
                        openResume(title, bookmark);
                    }

                    @Override
                    public void onItemClick(int position) {
                        // start intent : Episode viewer
                        Intent episodeView = episodeIntent(getContext(), searchAdapter.getItem(position));
                        startActivity(episodeView);
                    }
                });
            }else{
                searchAdapter.addData(targetSearch.getResult());
            }

            if(searchAdapter.getItemCount()>0) {
                noresult.setVisibility(View.GONE);
            }else{
                noresult.setVisibility(View.VISIBLE);
            }

            swipe.setRefreshing(false);
        }

        @Override
        protected void onCancelled(Integer res) {
            super.onCancelled(res);
            if(searchTask == this) {
                searchTask = null;
                activeSearchKey = null;
                if(swipe != null)
                    swipe.setRefreshing(false);
            }
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if(requestGroup != null)
                requestGroup.cancel();
            return super.cancel(mayInterruptIfRunning);
        }
    }
}
