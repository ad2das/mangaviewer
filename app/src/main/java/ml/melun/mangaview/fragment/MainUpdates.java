package ml.melun.mangaview.fragment;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.omadahealth.github.swipyrefreshlayout.library.SwipyRefreshLayout;

import java.util.ArrayList;

import ml.melun.mangaview.R;
import ml.melun.mangaview.Utils;
import ml.melun.mangaview.adapter.UpdatedAdapter;
import ml.melun.mangaview.mangaview.CustomHttpClient;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.mangaview.UpdatedList;
import ml.melun.mangaview.mangaview.UpdatedManga;
import ml.melun.mangaview.task.LifecycleTask;
import ml.melun.mangaview.ui.NpaLinearLayoutManager;

import static ml.melun.mangaview.MainApplication.httpClient;
import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.episodeIntent;

public class MainUpdates extends Fragment {
    RecyclerView recyclerView;
    SwipyRefreshLayout swipe;
    ProgressBar progress;
    View emptyState;
    TextView emptyTitle;
    TextView emptyMessage;
    UpdatedAdapter adapter;
    UpdatedList updated;
    LoadUpdates loadTask;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.content_updates, container, false);
        recyclerView = root.findViewById(R.id.updates_list);
        swipe = root.findViewById(R.id.updates_swipe);
        progress = root.findViewById(R.id.updates_progress);
        emptyState = root.findViewById(R.id.updates_empty_state);
        emptyTitle = root.findViewById(R.id.updates_empty_title);
        emptyMessage = root.findViewById(R.id.updates_empty_message);

        recyclerView.setLayoutManager(new NpaLinearLayoutManager(getContext()));
        recyclerView.setHasFixedSize(true);
        recyclerView.setItemViewCacheSize(20);
        recyclerView.setItemAnimator(null);
        recyclerView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if(getContext() == null)
                    return;
                if(newState == RecyclerView.SCROLL_STATE_IDLE)
                    Glide.with(MainUpdates.this).resumeRequests();
                else
                    Glide.with(MainUpdates.this).pauseRequests();
            }
        });
        adapter = new UpdatedAdapter(getContext());
        adapter.setOnClickListener(new UpdatedAdapter.onclickListener() {
            @Override
            public void onClick(Manga manga) {
                Title title = manga == null ? null : manga.getTitle();
                if(title != null)
                    onEpsClick(title);
            }

            @Override
            public void onEpsClick(Title title) {
                Intent eps = episodeIntent(getContext(), title);
                eps.putExtra("online", true);
                startActivity(eps);
            }
        });
        recyclerView.setAdapter(adapter);

        updated = new UpdatedList(p.getBaseMode());
        swipe.setOnRefreshListener(direction -> loadMore());
        loadMore();
        return root;
    }

    public void refreshIfEmpty() {
        if(adapter != null && adapter.getItemCount() == 0 && loadTask == null)
            loadMore();
    }

    private void loadMore() {
        if(loadTask != null) {
            if(swipe != null)
                swipe.setRefreshing(false);
            return;
        }
        if(updated != null && updated.isLast()) {
            if(swipe != null)
                swipe.setRefreshing(false);
            return;
        }
        loadTask = new LoadUpdates();
        loadTask.executeOnExecutor(LifecycleTask.USER_ACTION_EXECUTOR);
    }

    private void updateState(boolean loading) {
        if(progress != null)
            progress.setVisibility(loading && adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
        boolean empty = adapter == null || adapter.getItemCount() == 0;
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyState.setVisibility(!loading && empty ? View.VISIBLE : View.GONE);
        emptyTitle.setText("업데이트를 불러오지 못했습니다");
        emptyMessage.setText("연결 상태를 확인한 뒤 아래로 당겨 다시 시도해 주세요");
    }

    @Override
    public void onDestroyView() {
        if(loadTask != null) {
            loadTask.cancel(true);
            loadTask = null;
        }
        super.onDestroyView();
    }

    private class LoadUpdates extends LifecycleTask<Void, Void, ArrayList<UpdatedManga>> {
        private CustomHttpClient.RequestGroup requestGroup;
        private int resultCode = 0;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            updateState(true);
            if(swipe != null)
                swipe.setRefreshing(true);
        }

        @Override
        protected ArrayList<UpdatedManga> doInBackground(Void... voids) {
            requestGroup = new CustomHttpClient.RequestGroup();
            try {
                httpClient.runWithRequestGroup(requestGroup, () -> {
                    updated.fetch(httpClient);
                    return null;
                });
                ArrayList<UpdatedManga> result = updated.getResult();
                return result == null ? new ArrayList<>() : result;
            } catch (Exception e) {
                if(!isCancelled())
                    e.printStackTrace();
                resultCode = 1;
                return new ArrayList<>();
            }
        }

        @Override
        protected void onPostExecute(ArrayList<UpdatedManga> result) {
            super.onPostExecute(result);
            if(loadTask != this || getContext() == null)
                return;
            loadTask = null;
            if(resultCode != 0 && adapter.getItemCount() == 0)
                Utils.showCaptchaPopup(getContext(), p);
            if(result != null && result.size() > 0)
                adapter.addData(result);
            if(swipe != null)
                swipe.setRefreshing(false);
            updateState(false);
        }

        @Override
        protected void onCancelled(ArrayList<UpdatedManga> result) {
            super.onCancelled(result);
            if(loadTask == this)
                loadTask = null;
            if(swipe != null)
                swipe.setRefreshing(false);
            updateState(false);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if(requestGroup != null)
                requestGroup.cancel();
            return super.cancel(mayInterruptIfRunning);
        }
    }
}
