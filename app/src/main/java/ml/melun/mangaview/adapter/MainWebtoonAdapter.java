package ml.melun.mangaview.adapter;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import ml.melun.mangaview.task.LifecycleTask;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import ml.melun.mangaview.R;
import ml.melun.mangaview.mangaview.CustomHttpClient;
import ml.melun.mangaview.mangaview.MainPageWebtoon;
import ml.melun.mangaview.mangaview.Ranking;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.ui.NpaLinearLayoutManager;

import static ml.melun.mangaview.MainApplication.httpClient;
import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.getGlideUrl;
import static ml.melun.mangaview.mangaview.MTitle.base_comic;
import static ml.melun.mangaview.mangaview.MTitle.base_webtoon;

public class MainWebtoonAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int GROUP = 23;
    private static final int SECTION = 24;
    private static final int CATEGORY = 25;

    Context context;
    boolean dark;
    boolean save;
    LayoutInflater inflater;
    List<Ranking<?>> dataSet;
    List<Object> rows;
    MainAdapter.onItemClick listener;
    int baseMode;
    Fetcher fetcher;
    private final Set<String> preloadedThumbs = new LinkedHashSet<>();
    private static final int PRELOADED_THUMB_LIMIT = 120;
    private static final int PRELOAD_THUMB_MAX_PER_FETCH = 36;
    private static final int SECTION_BATCH_SIZE = 4;
    private int preloadCount = 0;

    public MainWebtoonAdapter(Context context){
        this(context, base_webtoon);
    }

    public MainWebtoonAdapter(Context context, int baseMode){
        this.context = context;
        this.baseMode = baseMode;
        this.dark = p.getDarkTheme();
        this.save = p.getDataSave();
        inflater = LayoutInflater.from(context);
        dataSet = MainPageWebtoon.getBlankDataSet(baseMode);
        rows = buildRows(dataSet, true);
        setHasStableIds(true);
    }

    public void fetch(){
        if(fetcher != null)
            fetcher.cancel(true);
        fetcher = new Fetcher();
        fetcher.executeOnExecutor(LifecycleTask.THREAD_POOL_EXECUTOR);
    }

    public void cancelFetch() {
        if(fetcher != null) {
            fetcher.cancel(true);
            fetcher = null;
        }
    }

    public void setLoading(){
        dataSet = MainPageWebtoon.getBlankDataSet(baseMode);
        updateRows(buildRows(dataSet, true));
    }

    public void setListener(MainAdapter.onItemClick listener){
        this.listener = listener;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if(viewType == CATEGORY)
            return new CategoryHolder(inflater.inflate(R.layout.item_webtoon_category_panel, parent, false));
        if(viewType == GROUP)
            return new GroupHolder(inflater.inflate(R.layout.item_webtoon_group_header, parent, false));
        return new SectionHolder(inflater.inflate(R.layout.item_webtoon_section, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if(rows == null || position < 0 || position >= rows.size())
            return;
        Object row = rows.get(position);
        if(holder instanceof CategoryHolder)
            ((CategoryHolder) holder).bind();
        else if(holder instanceof GroupHolder)
            ((GroupHolder) holder).bind((String) row);
        else
            ((SectionHolder) holder).bind((Ranking<?>) row);
    }

    @Override
    public int getItemViewType(int position) {
        if(rows == null || position < 0 || position >= rows.size())
            return SECTION;
        if(rows.get(position) instanceof CategoryPanel)
            return CATEGORY;
        return rows.get(position) instanceof String ? GROUP : SECTION;
    }

    @Override
    public int getItemCount() {
        return rows == null ? 0 : rows.size();
    }

    @Override
    public long getItemId(int position) {
        if(rows == null || position < 0 || position >= rows.size())
            return RecyclerView.NO_ID;
        return rowKey(rows.get(position)).hashCode();
    }

    private List<Object> buildRows(List<Ranking<?>> sections, boolean includeEmpty) {
        List<Object> result = new ArrayList<>();
        List<Object> contentRows = new ArrayList<>();
        String lastGroup = "";
        if(sections == null) {
            result.add(new CategoryPanel());
            return result;
        }
        for(Ranking<?> section : sections) {
            if(section == null)
                continue;
            if(!includeEmpty && section.size() == 0)
                continue;
            SectionName name = parseSectionName(section.getName());
            if(!name.group.equals(lastGroup)) {
                contentRows.add(name.group);
                lastGroup = name.group;
            }
            contentRows.add(section);
        }
        result.add(new CategoryPanel());
        result.addAll(contentRows);
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

    static class CategoryPanel {
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
            if(tag instanceof Integer && ((Integer) tag) == baseMode && filterSections.getChildCount() > 0)
                return;
            filterSections.setTag(baseMode);
            statusFilters.setVisibility(View.GONE);
            genreFilters.setVisibility(View.GONE);
            filterSections.removeAllViews();
            bindFilters(baseMode == base_comic ? MainPageWebtoon.COMIC_FILTER_GROUPS : MainPageWebtoon.WEBTOON_FILTER_GROUPS);
        }

        void bindFilters(String[][] groups) {
            for(String[] group : groups) {
                if(group.length == 0)
                    continue;
                SectionName groupName = parseSectionName(group[0]);
                TextView label = new TextView(context);
                label.setText(groupName.group);
                label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                label.setTextSize(14);
                label.setPadding(0, dp(10), 0, dp(6));
                filterSections.addView(label);

                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                filterSections.addView(row);
                int used = 0;
                int max = context.getResources().getDisplayMetrics().widthPixels - dp(40);
                for(String item : group) {
                    SectionName filter = parseSectionName(item);
                    TextView chip = createFilterChip(filter.title, clicked -> {
                        if(listener != null)
                            listener.clickedCategoryPath(filter.title, filter.path);
                    });
                    int width = estimateChipWidth(filter.title);
                    if(used > 0 && used + width > max) {
                        row = new LinearLayout(context);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        filterSections.addView(row);
                        used = 0;
                    }
                    row.addView(chip);
                    used += width;
                }
            }
        }

        TextView createFilterChip(String label, FilterClick click) {
            TextView chip = new TextView(context);
            chip.setText(label);
            chip.setSingleLine(true);
            chip.setGravity(Gravity.CENTER);
            chip.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            chip.setTextSize(13);
            chip.setPadding(dp(12), 0, dp(12), 0);
            chip.setBackground(filterBackground());
            chip.setOnClickListener(v -> click.onClick(label));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
            params.setMargins(0, 0, dp(8), dp(8));
            chip.setLayoutParams(params);
            return chip;
        }

        int estimateChipWidth(String label) {
            return dp(32 + Math.max(2, label == null ? 2 : label.length()) * 15);
        }

        GradientDrawable filterBackground() {
            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(dp(6));
            background.setColor(ContextCompat.getColor(context, dark ? R.color.colorDarkBackground : R.color.colorBackground));
            background.setStroke(dp(1), ContextCompat.getColor(context, dark ? R.color.colorDarkWindowBackground : R.color.colorPrimaryDark));
            return background;
        }
    }

    interface FilterClick {
        void onClick(String label);
    }

    int dp(int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    class GroupHolder extends RecyclerView.ViewHolder {
        TextView title;

        GroupHolder(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.webtoon_group_title);
        }

        void bind(String group) {
            title.setText(group);
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
            manager.setInitialPrefetchItemCount(6);
            list.setLayoutManager(manager);
            list.setNestedScrollingEnabled(false);
            list.setHasFixedSize(true);
            list.setItemViewCacheSize(12);
        }

        void bind(Ranking<?> section) {
            SectionName name = parseSectionName(section.getName());
            title.setText(name.title);
            count.setText("전체보기");
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

        WebtoonCardAdapter(List<?> items) {
            this.items = items;
            setHasStableIds(true);
        }

        void setItems(List<?> items) {
            int oldSize = this.items == null ? 0 : this.items.size();
            this.items = items;
            int newSize = this.items == null ? 0 : this.items.size();
            int commonSize = Math.min(oldSize, newSize);
            if(commonSize > 0)
                notifyItemRangeChanged(0, commonSize);
            if(newSize > oldSize)
                notifyItemRangeInserted(oldSize, newSize - oldSize);
            else if(oldSize > newSize)
                notifyItemRangeRemoved(newSize, oldSize - newSize);
        }

        @NonNull
        @Override
        public CardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new CardHolder(inflater.inflate(R.layout.item_webtoon_card, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull CardHolder holder, int position) {
            if(items == null || position < 0 || position >= items.size())
                return;
            Object item = items.get(position);
            if(!(item instanceof Title))
                return;

            Title title = (Title) item;
            holder.name.setText(title.getName());
            String meta = title.getRelease();
            if((meta == null || meta.length() == 0) && title.getTags().size() > 0)
                meta = TextUtils.join(" / ", title.getTags());
            holder.meta.setText(meta == null ? "" : meta);

            Glide.with(holder.thumb).clear(holder.thumb);
            String thumb = title.getThumb();
            if(save || thumb == null || thumb.length() == 0) {
                holder.thumb.setImageResource(R.mipmap.ic_launcher);
            } else {
                Glide.with(holder.thumb)
                        .load(getGlideUrl(thumb, title.getBaseMode()))
                        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                        .override(dp(180), dp(220))
                        .thumbnail(0.25f)
                        .dontAnimate()
                        .placeholder(R.mipmap.ic_launcher)
                        .into(holder.thumb);
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
            CardView card;
            ImageView thumb;
            TextView name;
            TextView meta;

            CardHolder(View itemView) {
                super(itemView);
                card = itemView.findViewById(R.id.webtoon_card);
                thumb = itemView.findViewById(R.id.webtoon_thumb);
                name = itemView.findViewById(R.id.webtoon_name);
                meta = itemView.findViewById(R.id.webtoon_meta);
                if(dark)
                    card.setCardBackgroundColor(ContextCompat.getColor(context, R.color.colorDarkBackground));
            }
        }
    }

    private void preloadThumbnails(List<Ranking<?>> sections) {
        if(save || sections == null)
            return;
        if(preloadCount >= PRELOAD_THUMB_MAX_PER_FETCH)
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
                        .override(dp(180), dp(220))
                        .preload();
                if(++preloadCount >= PRELOAD_THUMB_MAX_PER_FETCH)
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
        rows = nextRows;
        diff.dispatchUpdatesTo(this);
    }

    private String rowKey(Object row) {
        if(row instanceof CategoryPanel)
            return "category";
        if(row instanceof String)
            return "group:" + row;
        if(row instanceof Ranking)
            return "section:" + ((Ranking<?>) row).getName();
        return String.valueOf(row);
    }

    private String rowContentKey(Object row) {
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
        return rowKey(row);
    }

    private class Fetcher extends LifecycleTask<Void, SectionBatch, Boolean> {
        private CustomHttpClient.RequestGroup requestGroup;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            dataSet = MainPageWebtoon.getBlankDataSet(baseMode);
            preloadedThumbs.clear();
            preloadCount = 0;
            updateRows(buildRows(dataSet, false));
        }

        @Override
        protected Boolean doInBackground(Void... params) {
            requestGroup = new CustomHttpClient.RequestGroup();
            return fetchSections(requestGroup);
        }

        private Boolean fetchSections(CustomHttpClient.RequestGroup requestGroup) {
            String[][] sections = MainPageWebtoon.getSections(baseMode);
            ExecutorService executor = Executors.newFixedThreadPool(Math.min(4, sections.length));
            ExecutorCompletionService<SectionResult> completion = new ExecutorCompletionService<>(executor);
            MainPageWebtoon parser = new MainPageWebtoon(baseMode);
            int submitted = 0;
            int loaded = 0;
            List<SectionResult> pendingResults = new ArrayList<>();

            try {
                for(int i = 0; i < sections.length; i++) {
                    final int index = i;
                    final String[] section = sections[i];
                    completion.submit(() -> httpClient.runWithRequestGroup(requestGroup, () ->
                            new SectionResult(index, parser.parseWolfTitle(httpClient, section[0], section[1], baseMode))));
                    submitted++;
                }

                for(int i = 0; i < submitted && !isCancelled(); i++) {
                    Future<SectionResult> future = completion.take();
                    SectionResult result = future.get();
                    if(result != null && result.ranking != null) {
                        if(result.ranking.size() > 0)
                            loaded++;
                        pendingResults.add(result);
                        if(pendingResults.size() >= SECTION_BATCH_SIZE) {
                            publishProgress(new SectionBatch(new ArrayList<>(pendingResults)));
                            pendingResults.clear();
                        }
                    }
                }
                if(pendingResults.size() > 0)
                    publishProgress(new SectionBatch(new ArrayList<>(pendingResults)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                if(!isCancelled())
                    e.printStackTrace();
            } finally {
                executor.shutdownNow();
            }
            return loaded > 0;
        }

        @Override
        protected void onProgressUpdate(SectionBatch... values) {
            super.onProgressUpdate(values);
            if(values == null || values.length == 0 || values[0] == null || isCancelled())
                return;
            SectionBatch batch = values[0];
            if(batch.results == null || batch.results.size() == 0)
                return;
            if(dataSet == null)
                dataSet = MainPageWebtoon.getBlankDataSet(baseMode);
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
            if(baseMode == base_webtoon)
                MainPageWebtoon.enhanceWebtoonClassification(dataSet);
            preloadThumbnails(loadedSections);
            updateRows(buildRows(dataSet, false));
        }

        @Override
        protected void onPostExecute(Boolean hasAnyResult) {
            super.onPostExecute(hasAnyResult);
            if(fetcher == this)
                fetcher = null;
            if(!hasAnyResult) {
                if(listener != null)
                    listener.captchaCallback();
                return;
            }
            if(baseMode == base_webtoon)
                MainPageWebtoon.enhanceWebtoonClassification(dataSet);
            updateRows(buildRows(dataSet, false));
        }

        @Override
        protected void onCancelled(Boolean result) {
            super.onCancelled(result);
            if(fetcher == this)
                fetcher = null;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if(requestGroup != null)
                requestGroup.cancel();
            return super.cancel(mayInterruptIfRunning);
        }
    }
}
