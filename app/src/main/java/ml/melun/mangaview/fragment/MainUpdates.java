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
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.omadahealth.github.swipyrefreshlayout.library.SwipyRefreshLayout;

import java.util.ArrayList;
import java.util.List;

import ml.melun.mangaview.R;
import ml.melun.mangaview.Utils;
import ml.melun.mangaview.adapter.UpdatedAdapter;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.mangaview.UpdatedManga;
import ml.melun.mangaview.runtime.PerformanceMonitor;
import ml.melun.mangaview.state.UiState;
import ml.melun.mangaview.ui.NpaLinearLayoutManager;
import ml.melun.mangaview.ui.RecyclerPerformance;
import ml.melun.mangaview.viewmodel.UpdatesViewModel;

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
    UpdatesViewModel viewModel;
    boolean loading;

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
        RecyclerPerformance.tune(recyclerView, 20);
        RecyclerPerformance.bindImageRequestPausing(recyclerView);
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if(getContext() == null || !isAdded())
                    return;
                PerformanceMonitor.phase(newState == RecyclerView.SCROLL_STATE_IDLE ? "idle" : "scrolling");
                if(newState == RecyclerView.SCROLL_STATE_IDLE)
                    PerformanceMonitor.reportNow("updates_scroll_idle");
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

        viewModel = new ViewModelProvider(this).get(UpdatesViewModel.class);
        viewModel.reset(p.getBaseMode());
        viewModel.state().observe(getViewLifecycleOwner(), this::renderUpdatesState);
        swipe.setOnRefreshListener(direction -> loadMore());
        loadMore();
        return root;
    }

    public void refreshIfEmpty() {
        if(adapter != null && adapter.getItemCount() == 0 && !loading)
            loadMore();
    }

    private void loadMore() {
        if(loading) {
            if(swipe != null)
                swipe.setRefreshing(false);
            return;
        }
        viewModel.loadMore(p.getBaseMode());
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
        if(viewModel != null)
            viewModel.cancelActiveLoad();
        super.onDestroyView();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void renderUpdatesState(UiState state) {
        if(state instanceof UiState.Loading) {
            loading = true;
            updateState(true);
            if(swipe != null)
                swipe.setRefreshing(true);
            return;
        }
        loading = false;
        if(state instanceof UiState.Content) {
            List<UpdatedManga> result = (List<UpdatedManga>) ((UiState.Content) state).getValue();
            if(result != null && result.size() > 0)
                adapter.addData(new ArrayList<>(result));
        } else if(state instanceof UiState.Error && adapter.getItemCount() == 0 && getContext() != null) {
            Utils.showCaptchaPopup(getContext(), p);
        }
        if(swipe != null)
            swipe.setRefreshing(false);
        updateState(false);
    }
}
