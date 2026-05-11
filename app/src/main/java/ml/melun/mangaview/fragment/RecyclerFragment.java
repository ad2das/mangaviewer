package ml.melun.mangaview.fragment;

import android.app.SearchManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.PopupMenu;
import androidx.appcompat.widget.SearchView;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.AdapterDataObserver;

import com.google.gson.Gson;

import java.util.List;

import ml.melun.mangaview.ui.NpaLinearLayoutManager;
import ml.melun.mangaview.R;
import ml.melun.mangaview.Preference;
import ml.melun.mangaview.Utils;
import ml.melun.mangaview.adapter.TitleAdapter;
import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.repository.OfflineStore;
import ml.melun.mangaview.runtime.AppDispatchers;
import ml.melun.mangaview.runtime.PerformanceMonitor;

import static android.app.Activity.RESULT_OK;
import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.episodeIntent;
import static ml.melun.mangaview.Utils.openViewerPrepared;
import static ml.melun.mangaview.Utils.showPopup;
import static ml.melun.mangaview.Utils.viewerIntent;

public class RecyclerFragment extends Fragment {
    int selectedPosition = -1;
    TitleAdapter titleAdapter;
    RecyclerView recyclerView;
    View emptyState;
    TextView emptyStateTitle;
    TextView emptyStateMessage;
    int mode = -1;
    boolean loaded = false;
    SearchView searchView;
    Preference.LocalChangeListener localChangeListener;
    OfflineReader offlineReader;
    final Handler touchHandler = new Handler(Looper.getMainLooper());
    int touchSlop = 8;
    int listScrollState = RecyclerView.SCROLL_STATE_IDLE;
    float touchDownX = 0f;
    float touchDownY = 0f;
    long touchDownAt = 0L;
    boolean touchMoved = false;
    boolean touchLongPressed = false;
    boolean touchOnResume = false;
    int touchPosition = RecyclerView.NO_POSITION;
    View touchChild;
    View touchAnchor;
    Runnable touchLongPressRunnable;


    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putInt("mode", mode);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        ViewGroup rootView = (ViewGroup) inflater.inflate(R.layout.content_recycler , container, false);
        recyclerView = rootView.findViewById(R.id.recycler_list);
        emptyState = rootView.findViewById(R.id.empty_state);
        emptyStateTitle = rootView.findViewById(R.id.empty_state_title);
        emptyStateMessage = rootView.findViewById(R.id.empty_state_message);
        titleAdapter = new TitleAdapter(getContext());
        recyclerView.setLayoutManager(new NpaLinearLayoutManager(getContext()));
        recyclerView.setHasFixedSize(true);
        recyclerView.setItemViewCacheSize(12);
        recyclerView.setItemAnimator(null);
        recyclerView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        recyclerView.setAdapter(titleAdapter);
        touchSlop = ViewConfiguration.get(rootView.getContext()).getScaledTouchSlop();
        recyclerView.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                handleLibraryTouch(e);
                return false;
            }

            @Override
            public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                handleLibraryTouch(e);
            }
        });
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                listScrollState = newState;
                if(newState != RecyclerView.SCROLL_STATE_IDLE)
                    cancelLibraryTouchClick();
                if(getContext() == null || !isAdded())
                    return;
                PerformanceMonitor.phase(newState == RecyclerView.SCROLL_STATE_IDLE ? "idle" : "scrolling");
                if(newState == RecyclerView.SCROLL_STATE_IDLE)
                    PerformanceMonitor.reportNow("recycler_scroll_idle");
            }
        });
        localChangeListener = scope -> {
            if(!isLibraryChange(scope) || recyclerView == null)
                return;
            recyclerView.post(() -> {
                if(loaded && mode > -1)
                    changeMode(mode);
            });
        };
        p.addLocalChangeListener(localChangeListener);
        titleAdapter.registerAdapterDataObserver(new AdapterDataObserver() {
            @Override
            public void onChanged() {
                updateEmptyState();
            }

            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) {
                updateEmptyState();
            }

            @Override
            public void onItemRangeRemoved(int positionStart, int itemCount) {
                updateEmptyState();
            }
        });
        titleAdapter.setClickListener(new TitleAdapter.ItemClickListener() {
            @Override
            public void onResumeClick(int position, int id) {
                selectedPosition = position;
                Title title = resolveLatestTitleForResume(titleAdapter.getItem(position));
                if(title == null)
                    return;
                int bookmark = resolveLatestBookmark(title, id);
                if(bookmark <= 0)
                    return;
                if(mode == R.id.nav_recent) {
                    Manga manga = new Manga(bookmark, "", "" , title.getBaseMode());
                    manga.setTitle(title);
                    manga.setTitleId(title.getId());
                    openViewer(manga, 2);
                } else if(mode == R.id.nav_favorite) {
                    Manga manga = new Manga(bookmark, "", "", title.getBaseMode());
                    manga.setTitle(title);
                    manga.setTitleId(title.getId());
                    openViewer(manga, -1);
                }
            }

            @Override
            public void onLongClick(View view, int position) {
                Title title = titleAdapter.getItem(position);
                if(title == null)
                    return;
                if(mode == R.id.nav_favorite) {
                    popup(view, position, title, 2);
                }else if(mode == R.id.nav_recent){
                    popup(view, position, title, 1);
                }else if(mode == R.id.nav_download){
                    popup(view, position, title,3);
                }
            }

            @Override
            public void onItemClick(int position) {
                selectedPosition = position;
                Title title = titleAdapter.getItem(position);
                if(title == null)
                    return;
                Intent episodeView = episodeIntent(getContext(), title);
                if(mode == R.id.nav_favorite) {
                    episodeView.putExtra("position", position);
                    episodeView.putExtra("favorite",true);
                    startActivityForResult(episodeView,1);
                }else if(mode == R.id.nav_recent) {
                    episodeView.putExtra("recent",true);
                    startActivityForResult(episodeView,2);
                }else if(mode == R.id.nav_download) {
                    episodeView.putExtra("online", false);
                    startActivity(episodeView);
                }
            }
        });
        if(savedInstanceState != null){
            mode = savedInstanceState.getInt("mode");
        }
        if(mode > -1) {
            loaded = true;
            changeMode(mode);
        }
        return rootView;
    }

    private void handleLibraryTouch(MotionEvent event) {
        if(event == null || recyclerView == null || titleAdapter == null)
            return;
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                beginLibraryTouch(event);
                break;
            case MotionEvent.ACTION_MOVE:
                if(touchPosition != RecyclerView.NO_POSITION && movedPastTouchSlop(event)) {
                    cancelLibraryTouchClick();
                }
                break;
            case MotionEvent.ACTION_UP:
                finishLibraryTouch(event);
                break;
            case MotionEvent.ACTION_CANCEL:
                resetLibraryTouch();
                break;
        }
    }

    private void beginLibraryTouch(MotionEvent event) {
        resetLibraryTouch();
        touchDownX = event.getX();
        touchDownY = event.getY();
        touchDownAt = SystemClock.uptimeMillis();
        touchChild = recyclerView.findChildViewUnder(touchDownX, touchDownY);
        if(touchChild == null)
            return;
        touchPosition = recyclerView.getChildAdapterPosition(touchChild);
        if(touchPosition == RecyclerView.NO_POSITION) {
            resetLibraryTouch();
            return;
        }
        View resume = resumeButtonFor(touchChild);
        touchOnResume = isTouchInsideDescendant(touchChild, resume, touchDownX, touchDownY);
        touchAnchor = touchOnResume && resume != null ? resume : touchChild;
        scheduleLibraryLongPress(touchPosition, touchAnchor);
    }

    private void finishLibraryTouch(MotionEvent event) {
        touchHandler.removeCallbacksAndMessages(null);
        if(touchPosition == RecyclerView.NO_POSITION) {
            resetLibraryTouch();
            return;
        }
        boolean moved = touchMoved || movedPastTouchSlop(event);
        boolean longPressWindow = SystemClock.uptimeMillis() - touchDownAt >= ViewConfiguration.getLongPressTimeout();
        int position = touchPosition;
        boolean resumeTap = touchOnResume;
        boolean canClick = !moved
                && !touchLongPressed
                && !longPressWindow
                && listScrollState == RecyclerView.SCROLL_STATE_IDLE;
        resetLibraryTouchStateOnly();
        if(!canClick)
            return;
        if(resumeTap)
            titleAdapter.performResumeClick(position);
        else
            titleAdapter.performItemClick(position);
    }

    private void scheduleLibraryLongPress(int position, View anchor) {
        touchLongPressRunnable = () -> {
            if(touchMoved
                    || touchLongPressed
                    || listScrollState != RecyclerView.SCROLL_STATE_IDLE
                    || touchPosition != position
                    || titleAdapter == null)
                return;
            touchLongPressed = titleAdapter.performItemLongClick(anchor == null ? recyclerView : anchor, position);
        };
        touchHandler.postDelayed(touchLongPressRunnable, ViewConfiguration.getLongPressTimeout());
    }

    private void cancelLibraryTouchClick() {
        touchMoved = true;
        touchHandler.removeCallbacksAndMessages(null);
    }

    private void resetLibraryTouch() {
        touchHandler.removeCallbacksAndMessages(null);
        resetLibraryTouchStateOnly();
    }

    private void resetLibraryTouchStateOnly() {
        touchMoved = false;
        touchLongPressed = false;
        touchOnResume = false;
        touchPosition = RecyclerView.NO_POSITION;
        touchChild = null;
        touchAnchor = null;
        touchLongPressRunnable = null;
        touchDownAt = 0L;
    }

    private boolean movedPastTouchSlop(MotionEvent event) {
        if(event == null)
            return false;
        return Math.abs(event.getX() - touchDownX) > touchSlop
                || Math.abs(event.getY() - touchDownY) > touchSlop;
    }

    private View resumeButtonFor(View child) {
        return child == null ? null : child.findViewById(R.id.epsButton);
    }

    private boolean isTouchInsideDescendant(View child, View descendant, float recyclerX, float recyclerY) {
        if(child == null || descendant == null || descendant.getVisibility() != View.VISIBLE)
            return false;
        if(!(child instanceof ViewGroup))
            return false;
        Rect rect = new Rect(0, 0, descendant.getWidth(), descendant.getHeight());
        ((ViewGroup) child).offsetDescendantRectToMyCoords(descendant, rect);
        int childX = Math.round(recyclerX - child.getLeft());
        int childY = Math.round(recyclerY - child.getTop());
        return rect.contains(childX, childY);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if(resultCode == RESULT_OK){
            if(titleAdapter != null && titleAdapter.getItemCount() > 0 && selectedPosition > -1) {
                switch (requestCode) {
                    case 1:
                        //favorite result
                        boolean favorite_after = data.getBooleanExtra("favorite", true);
                        if (!favorite_after && titleAdapter != null && titleAdapter.getItemCount() > 0)
                            titleAdapter.remove(selectedPosition);
                        break;
                    case 2:
                        //recent result
                        if (titleAdapter != null && titleAdapter.getItemCount() > 0)
                            titleAdapter.moveItemToTop(selectedPosition);
                        break;

                }
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if(loaded && mode > -1)
            changeMode(mode);
    }

    @Override
    public void onDestroyView() {
        resetLibraryTouch();
        if(localChangeListener != null) {
            p.removeLocalChangeListener(localChangeListener);
            localChangeListener = null;
        }
        if(offlineReader != null) {
            offlineReader.cancel();
            offlineReader = null;
        }
        super.onDestroyView();
        mode = -1;
        loaded = false;
    }

    private boolean isLibraryChange(String scope) {
        return "recent".equals(scope)
                || "favorite".equals(scope)
                || "bookmark".equals(scope)
                || "pageBookmark".equals(scope);
    }

    private Title resolveLatestTitleForResume(Title title) {
        if(title == null)
            return null;
        MTitle stored = mode == R.id.nav_favorite ? p.findFavoriteTitle(title) : p.findRecentTitle(title);
        if(stored == null && mode == R.id.nav_favorite)
            stored = p.findRecentTitle(title);
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

    public void changeMode(int id){
        mode = id;
        if(!loaded)
            return;
        recyclerView.scrollToPosition(0);
        if(searchView != null){
            searchView.clearFocus();
            searchView.setQuery("", false);
        }
        if(id == R.id.nav_recent){
            titleAdapter.setResume(true);
            titleAdapter.setForceThumbnail(false);
            titleAdapter.setData(Utils.snapshotList(p.getRecent()));
        }else if(id == R.id.nav_favorite){
            titleAdapter.setResume(true);
            titleAdapter.setForceThumbnail(false);
            titleAdapter.setData(Utils.snapshotList(p.getFavorite()));
        }else if(id == R.id.nav_download){
            titleAdapter.setResume(false);
            titleAdapter.setForceThumbnail(true);
            titleAdapter.clearData();
            if(offlineReader != null)
                offlineReader.cancel();
            offlineReader = new OfflineReader(getContext());
            offlineReader.start();
        }
        updateEmptyState();
    }

    private void updateEmptyState() {
        if(emptyState == null || titleAdapter == null)
            return;
        boolean isEmpty = titleAdapter.getItemCount() == 0;
        recyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        emptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        if(!isEmpty)
            return;
        if(mode == R.id.nav_recent) {
            emptyStateTitle.setText("아직 읽은 작품이 없습니다");
            emptyStateMessage.setText("작품을 열면 이어볼 수 있도록 여기에 표시됩니다");
        } else if(mode == R.id.nav_favorite) {
            emptyStateTitle.setText("보관함에 담긴 작품이 없습니다");
            emptyStateMessage.setText("마음에 드는 작품을 보관하면 이곳에 모입니다");
        } else if(mode == R.id.nav_download) {
            emptyStateTitle.setText("저장된 작품이 없습니다");
            emptyStateMessage.setText("오프라인으로 볼 작품을 저장하면 여기에 표시됩니다");
        } else {
            emptyStateTitle.setText("표시할 작품이 없습니다");
            emptyStateMessage.setText("작품을 읽거나 저장하면 이곳에 정리됩니다");
        }
    }


    public class OfflineReader {
        final Context appContext;
        AppDispatchers.TaskHandle handle;
        volatile boolean cancelled;

        OfflineReader(Context context) {
            appContext = context == null ? null : context.getApplicationContext();
        }

        void start() {
            handle = AppDispatchers.submitIo(() -> {
                List<Title> titles = OfflineStore.loadTitles(appContext);
                AppDispatchers.runOnMain(() -> finish(titles));
            });
        }

        void finish(List<Title> titles) {
            if(cancelled || offlineReader != this)
                return;
            offlineReader = null;
            titleAdapter.addData(titles);
            updateEmptyState();
        }

        void cancel() {
            cancelled = true;
            if(handle != null)
                handle.cancel();
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.search_menu, menu);
        SearchManager searchManager = (SearchManager) getActivity().getSystemService(Context.SEARCH_SERVICE);
        searchView = (SearchView) menu.findItem(R.id.filter_search).getActionView();
        searchView.setSearchableInfo(searchManager.getSearchableInfo(getActivity().getComponentName()));
        searchView.setMaxWidth(Integer.MAX_VALUE);
        searchView.setQueryHint("검색");
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                titleAdapter.getFilter().filter(query);
                return false;
            }

            @Override
            public boolean onQueryTextChange(String query) {
                titleAdapter.getFilter().filter(query);
                return false;
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        return item.getItemId() == R.id.filter_search;
    }

    void openViewer(Manga manga, int code){
        Title title = manga == null ? null : manga.getTitle();
        if(title == null && selectedPosition > -1)
            title = titleAdapter.getItem(selectedPosition);
        manga.setMode(0);
        if(title != null)
            manga.setTitle(title);
        openViewerPrepared(getContext(), manga, code, false, true, mode == R.id.nav_recent, title, true);
    }

    void popup(View view, final int position, final Title title, final int m){
        PopupMenu popup = new PopupMenu(getContext(), view);
        //Inflating the Popup using xml file
        //todo: clean this part
        popup.getMenuInflater()
                .inflate(R.menu.title_options, popup.getMenu());
        switch(m){
            case 1:
                //최근
                popup.getMenu().findItem(R.id.del).setVisible(true);
            case 0:
                //검색
                popup.getMenu().findItem(R.id.favAdd).setVisible(true);
                popup.getMenu().findItem(R.id.favDel).setVisible(true);
                break;
            case 2:
                //좋아요
                popup.getMenu().findItem(R.id.favDel).setVisible(true);
                break;
            case 3:
                //저장됨
                popup.getMenu().findItem(R.id.favAdd).setVisible(true);
                popup.getMenu().findItem(R.id.favDel).setVisible(true);
                popup.getMenu().findItem(R.id.remove).setVisible(true);
                break;
        }
        //좋아요 추가/제거 중 하나만 남김
        if(m!=2) {
            if (p.findFavorite(title) > -1) popup.getMenu().removeItem(R.id.favAdd);
            else popup.getMenu().removeItem(R.id.favDel);
        }

        //registering popup with OnMenuItemClickListener
        popup.setOnMenuItemClickListener(item -> {
            switch(item.getItemId()){
                case R.id.del:
                    //delete (only in recent)
                    titleAdapter.remove(position);
                    p.removeRecent(position);
                    break;
                case R.id.favAdd:
                case R.id.favDel:
                    //toggle favorite
                    p.toggleFavorite(title,0);
                    if(m==2){
                        titleAdapter.remove(position);
                    }
                    break;
                case R.id.remove:
                    //저장된 만화에서 삭제
                    DialogInterface.OnClickListener dialogClickListener = (dialog, which) -> {
                        if (which == DialogInterface.BUTTON_POSITIVE) {
                            //Yes button clicked
                            if (OfflineStore.deleteTitle(getContext(), title)) {
                                titleAdapter.remove(position);
                                Toast.makeText(getContext(), "삭제가 완료되었습니다.", Toast.LENGTH_SHORT).show();
                            } else showPopup(getContext(), "알림", "삭제를 실패했습니다");
                        }
                    };
                    AlertDialog.Builder builder;
                    if(p.getDarkTheme()) builder = new AlertDialog.Builder(getContext(),R.style.darkDialog);
                    else builder = new AlertDialog.Builder(getContext());
                    builder.setMessage("정말로 삭제 하시겠습니까?").setPositiveButton("네", dialogClickListener)
                            .setNegativeButton("아니오", dialogClickListener).show();
                    break;
            }
            return false;
        });
        popup.show(); //showing popup menu
    }
}
