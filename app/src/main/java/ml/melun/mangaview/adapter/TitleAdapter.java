package ml.melun.mangaview.adapter;
import android.content.Context;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;

import ml.melun.mangaview.R;
import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.MainPageWebtoon;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.runtime.AppDispatchers;
import ml.melun.mangaview.runtime.ContinueReadinessCoordinator;

import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.getGlideUrl;
import static ml.melun.mangaview.Utils.isLocalMediaPath;
import static ml.melun.mangaview.Utils.safeGlideClear;

public class TitleAdapter extends RecyclerView.Adapter<TitleAdapter.ViewHolder> implements Filterable {

    private ArrayList<Title> mData;
    private ArrayList<Title> mDataFiltered;
    private LayoutInflater mInflater;
    private ItemClickListener mClickListener;
    private Context mainContext;
    boolean dark = false;
    boolean save;
    boolean resume = true;
    boolean updated = false;
    boolean forceThumbnail = false;
    boolean deferThumbnails = false;
    boolean longClickEnabled = true;
    String statusFilter = "";
    String path = "";
    Filter filter;
    boolean searching = false;
    private final Executor diffExecutor = AppDispatchers.uiDiff();
    private int diffGeneration = 0;
    private long lastResumeOpenAt = 0L;
    private int lastResumeOpenPosition = RecyclerView.NO_POSITION;
    private final Map<String, String> tagTextCache = new HashMap<>();
    private final Map<String, BindMeta> bindMetaCache = new HashMap<>();
    private final LinkedHashMap<String, Boolean> resumeWarmupKeys = new LinkedHashMap<>(64, 0.75f, true);

    public TitleAdapter(Context context) {
        init(context);
    }
    public TitleAdapter(Context context, boolean online) {
        init(context);
        forceThumbnail = !online;
    }

    public void setForceThumbnail(boolean b){
        this.forceThumbnail = b;
    }

    public void setDeferThumbnails(boolean deferThumbnails) {
        if(this.deferThumbnails == deferThumbnails)
            return;
        this.deferThumbnails = deferThumbnails;
        if(!deferThumbnails)
            notifyAllItemsChanged();
    }

    void init(Context context){
        dark = p.getDarkTheme();
        save = p.getDataSave();
        this.mInflater = LayoutInflater.from(context);
        mainContext = context;
        this.mData = new ArrayList<>();
        this.mDataFiltered = new ArrayList<>();
        setHasStableIds(true);
        filter = new Filter() {
            @Override
            protected FilterResults performFiltering(CharSequence charSequence) {
                String query = charSequence.toString();
                ArrayList<Title> next;
                if(query.isEmpty() || query.length() == 0){
                    next = new ArrayList<>(mData);
                    searching = false;
                }else{
                    searching = true;
                    String normalizedQuery = query.toLowerCase(Locale.ROOT);
                    ArrayList<Title> filtered = new ArrayList<>();
                    for(Title t : mData){
                        if(t.getName().toLowerCase(Locale.ROOT).contains(normalizedQuery)
                                || t.getAuthor().toLowerCase(Locale.ROOT).contains(normalizedQuery))
                            filtered.add(t);
                    }
                    next = filtered;
                }
                FilterResults res = new FilterResults();
                res.values = next;
                return res;
            }

            @Override
            protected void publishResults(CharSequence charSequence, FilterResults filterResults) {
                ArrayList<Title> next = (ArrayList<Title>) filterResults.values;
                dispatchFilteredList(next == null ? new ArrayList<>() : next);
            }
        };
    }


    @Override
    public long getItemId(int position) {
        if(!isValidPosition(position))
            return RecyclerView.NO_ID;
        Title title = mDataFiltered.get(position);
        return (sourceKey(title) + ":" + title.getBaseMode() + ":" + title.getId()).hashCode();
    }

    public void removeAll(){
        diffGeneration++;
        int originSize = mData.size();
        mData.clear();
        mDataFiltered.clear();
        tagTextCache.clear();
        bindMetaCache.clear();
        if(originSize > 0)
            notifyItemRangeRemoved(0,originSize);
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = mInflater.inflate(R.layout.item_title, parent, false);
        return new ViewHolder(view);
    }

    public void addData(List<?> t){
        if(t == null)
            return;
        int oSize = mData.size();
        int inserted = 0;
        for(Object d:t){
            if(d instanceof Title){
                Title title = (Title)d;
                if(!deferThumbnails)
                    applyStoredBookmark(title);
                mData.add(title);
                inserted++;
            } else if(d instanceof MTitle){
                Title d2 = new Title((MTitle)d);
                if(!deferThumbnails)
                    applyStoredBookmark(d2);
                mData.add(d2);
                inserted++;
            }
        }
        bindMetaCache.clear();
        if(inserted <= 0)
            return;
        if(statusFilter.length() == 0) {
            mDataFiltered = mData;
            notifyItemRangeInserted(oSize, inserted);
        } else {
            dispatchFilteredList(filteredByStatus());
        }
    }

    public void preloadThumbnails(int startPosition, int count) {
        if(mDataFiltered == null || count <= 0 || deferThumbnails || (save && !forceThumbnail))
            return;
        int start = Math.max(0, startPosition);
        int end = Math.min(mDataFiltered.size(), start + Math.min(count, save ? 8 : 20));
        for(int i = start; i < end; i++) {
            Title data = mDataFiltered.get(i);
            if(data == null)
                continue;
            String thumb = data.getThumb();
            if(thumb == null || thumb.length() <= 1)
                continue;
            Object source = isLocalMediaPath(thumb) ? thumb : getGlideUrl(thumb, data.getBaseMode());
            Glide.with(mainContext)
                    .load(source)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .override(dp(126), dp(170))
                    .dontAnimate()
                    .preload();
        }
    }

    public void warmupVisibleResumeItems(RecyclerView recyclerView) {
        if(recyclerView == null || mDataFiltered == null || !resume || forceThumbnail)
            return;
        int childCount = recyclerView.getChildCount();
        if(childCount <= 0)
            return;
        int first = Integer.MAX_VALUE;
        int last = RecyclerView.NO_POSITION;
        for(int i = 0; i < childCount; i++) {
            int position = recyclerView.getChildAdapterPosition(recyclerView.getChildAt(i));
            if(!isValidPosition(position))
                continue;
            first = Math.min(first, position);
            last = Math.max(last, position);
        }
        if(first == Integer.MAX_VALUE || last == RecyclerView.NO_POSITION)
            return;
        warmupResumeRange(first, last, save ? 2 : 4);
    }

    public void warmupResumeClick(int position) {
        warmupResumeAt(position, false);
    }

    private void warmupResumeRange(int first, int last, int limit) {
        int warmed = 0;
        int end = Math.min(last, getItemCount() - 1);
        for(int position = Math.max(0, first); position <= end && warmed < limit; position++) {
            if(warmupResumeAt(position))
                warmed++;
        }
    }

    private boolean warmupResumeAt(int position) {
        return warmupResumeAt(position, true);
    }

    private boolean warmupResumeAt(int position, boolean visibleResume) {
        if(!isValidPosition(position) || mainContext == null)
            return false;
        Title title = mDataFiltered.get(position);
        if(title == null || title.getPath() != null && title.getPath().length() > 0)
            return false;
        if("ntk".equals(sourceSiteForTitle(title)))
            return false;
        int bookmark = resolveResumeBookmark(title);
        if(bookmark <= 0 || title.getId() <= 0)
            return false;
        Manga manga = new Manga(bookmark, "", "", title.getBaseMode());
        manga.setTitle(title);
        manga.setTitleId(title.getId());
        int page = p.getViewerBookmark(manga);
        if(page < 0)
            page = 0;
        String key = sourceKey(title) + ":" + title.getBaseMode() + ":" + title.getId() + ":" + bookmark + ":" + page;
        if(visibleResume) {
            synchronized (resumeWarmupKeys) {
                if(resumeWarmupKeys.containsKey(key))
                    return false;
                resumeWarmupKeys.put(key, Boolean.TRUE);
                while(resumeWarmupKeys.size() > 128) {
                    Iterator<String> iterator = resumeWarmupKeys.keySet().iterator();
                    if(!iterator.hasNext())
                        break;
                    iterator.next();
                    iterator.remove();
                }
            }
            ContinueReadinessCoordinator.primeVisible(mainContext, manga, title);
        } else {
            ContinueReadinessCoordinator.primeImmediate(mainContext, manga, title);
        }
        return true;
    }

    public void setData(List<?> t){
        ArrayList<Title> next = normalizeTitles(t);
        mData = next;
        searching = false;
        tagTextCache.clear();
        bindMetaCache.clear();
        dispatchFilteredList(statusFilter.length() == 0 ? next : filteredByStatus());
    }

    public void setDataImmediate(List<?> t){
        ArrayList<Title> next = normalizeTitles(t);
        ArrayList<Title> nextFiltered = filteredByStatus(next);
        if(listContentSignature(mDataFiltered).equals(listContentSignature(nextFiltered))) {
            mData = next;
            mDataFiltered = nextFiltered;
            searching = false;
            return;
        }
        int oldSize = getItemCount();
        mData = next;
        mDataFiltered = nextFiltered;
        searching = false;
        diffGeneration++;
        tagTextCache.clear();
        bindMetaCache.clear();
        notifyListReplaced(oldSize, getItemCount());
    }

    private void dispatchFilteredList(ArrayList<Title> next) {
        final ArrayList<Title> old = new ArrayList<>(mDataFiltered);
        final ArrayList<Title> target = new ArrayList<>(next);
        if(listContentSignature(old).equals(listContentSignature(target))) {
            mDataFiltered = target;
            return;
        }
        final int generation = ++diffGeneration;
        diffExecutor.execute(() -> {
            DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override
                public int getOldListSize() {
                    return old.size();
                }

                @Override
                public int getNewListSize() {
                    return target.size();
                }

                @Override
                public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                    return sameTitle(old.get(oldItemPosition), target.get(newItemPosition));
                }

                @Override
                public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                    return titleContentKey(old.get(oldItemPosition)).equals(titleContentKey(target.get(newItemPosition)));
                }
            }, false);
            AppDispatchers.runOnMain(() -> {
                if(generation != diffGeneration)
                    return;
                mDataFiltered = target;
                diff.dispatchUpdatesTo(this);
            });
        });
    }

    private String listContentSignature(List<Title> titles) {
        if(titles == null || titles.size() == 0)
            return "";
        StringBuilder builder = new StringBuilder(titles.size() * 32);
        for(Title title : titles)
            builder.append(titleContentKey(title)).append('\n');
        return builder.toString();
    }

    private ArrayList<Title> normalizeTitles(List<?> source) {
        ArrayList<Title> titles = new ArrayList<>();
        if(source == null)
            return titles;
        for(Object d : source) {
            Title title = null;
            if(d instanceof Title)
                title = (Title)d;
            else if(d instanceof MTitle)
                title = new Title((MTitle)d);
            if(title == null)
                continue;
            if(!deferThumbnails)
                applyStoredBookmark(title);
            titles.add(title);
        }
        return titles;
    }

    public void setNtkStatusFilter(String statusFilter) {
        this.statusFilter = statusFilter == null ? "" : statusFilter;
        searching = this.statusFilter.length() > 0;
        dispatchFilteredList(this.statusFilter.length() == 0 ? new ArrayList<>(mData) : filteredByStatus());
    }

    public int getUnfilteredItemCount() {
        return mData == null ? 0 : mData.size();
    }

    public int getNtkStatusCount(String status) {
        if(mData == null || status == null)
            return 0;
        int count = 0;
        for(Title title : mData)
            if(title != null && status.equals(title.getNtkStatusLabel()))
                count++;
        return count;
    }

    private ArrayList<Title> filteredByStatus() {
        return filteredByStatus(mData);
    }

    private ArrayList<Title> filteredByStatus(List<Title> source) {
        ArrayList<Title> filtered = new ArrayList<>();
        if(source == null)
            return filtered;
        for(Title title : source) {
            if(title == null)
                continue;
            if(statusFilter.length() == 0 || statusFilter.equals(title.getNtkStatusLabel()))
                filtered.add(title);
        }
        return filtered;
    }

    private boolean sameTitle(Title a, Title b) {
        return a != null && b != null
                && a.getId() == b.getId()
                && a.getBaseMode() == b.getBaseMode()
                && sourceKey(a).equals(sourceKey(b));
    }

    private String titleContentKey(Title title) {
        return titleContentKeyForTest(title);
    }

    static String titleContentKeyForTest(Title title) {
        if(title == null)
            return "";
        return title.getName() + "|" + title.getThumb() + "|" + title.getAuthor() + "|"
                + title.getRelease() + "|" + title.getBookmark() + "|" + title.getTags()
                + "|" + title.getSourceSite() + "|" + title.getNtkStatusLabel()
                + "|" + title.getBookmarkEpisodeId()
                + "|" + title.getBookmarkEpisodeIndex()
                + "|" + title.getEpisodeCount()
                + "|" + title.getEpsCount()
                + "|" + title.getResumeNtkEpisodePath();
    }

    public void clearData(){
        int oldSize = getItemCount();
        mData.clear();
        mDataFiltered.clear();
        tagTextCache.clear();
        bindMetaCache.clear();
        if(oldSize > 0)
            notifyItemRangeRemoved(0, oldSize);
    }


    public void moveItemToTop(int from){
        if(!isValidPosition(from))
            return;
        if(!searching) {
            if(mData == null || from >= mData.size())
                return;
            mData.add(0, mData.get(from));
            mData.remove(from + 1);
            for (int i = from; i > 0; i--) {
                notifyItemMoved(i, i - 1);
            }
        }else{
            Title t = mDataFiltered.get(from);
            int index = mData.indexOf(t);
            if(index < 0 || index >= mData.size())
                return;
            mData.add(0, mData.get(index));
            mData.remove(index + 1);
        }
    }

    public void remove(int pos){
        if(!isValidPosition(pos))
            return;
        if(!searching) {
            if(mData == null || pos >= mData.size())
                return;
            mData.remove(pos);
            notifyItemRemoved(pos);
        }else{
            Title t = mDataFiltered.get(pos);
            int index = mData.indexOf(t);
            if(index < 0 || index >= mData.size())
                return;
            mData.remove(index);
            mDataFiltered.remove(pos);
            notifyItemRemoved(pos);
        }
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        if(!isValidPosition(position))
            return;
        Title data = mDataFiltered.get(position);
        BindMeta bindMeta = bindMeta(data);
        String title = data.getName();
        String author = data.getAuthor();
        int bookmark = bindMeta.bookmark;
        holder.baseModeStr.setText(data.getBaseModeStr());
        String statusLabel = bindMeta.statusLabel;
        if(statusLabel.length() > 0) {
            holder.thumbStatusBadge.setVisibility(View.VISIBLE);
            if("연재".equals(statusLabel)) {
                holder.thumbStatusBadge.setBackgroundResource(R.drawable.app_thumb_badge_ongoing_bg);
                holder.thumbStatusBadge.setText("연재");
            } else {
                holder.thumbStatusBadge.setBackgroundResource(R.drawable.app_thumb_badge_completed_bg);
                holder.thumbStatusBadge.setText("완결");
            }
        } else {
            holder.thumbStatusBadge.setVisibility(View.GONE);
        }
        holder.tags.setText(bindMeta.tags);
        holder.tagContainer.setVisibility(View.VISIBLE);

        holder.name.setText(title);
        holder.name.setContentDescription(title);
        String meta = data.getRelease();
        if(meta == null || meta.length() == 0)
            meta = author;
        String progressLabel = bindMeta.progressLabel;
        if(progressLabel.length() > 0)
            meta = progressLabel;
        holder.author.setText(meta);
        int progressPercent = bindMeta.progressPercent;
        if(holder.progress != null) {
            holder.progress.setVisibility(progressPercent > 0 ? View.VISIBLE : View.GONE);
            holder.progress.setProgress(progressPercent);
        }
        if(holder.progressText != null) {
            holder.progressText.setVisibility(progressPercent > 0 ? View.VISIBLE : View.GONE);
            holder.progressText.setText(progressPercent > 0 ? progressPercent + "%" : "");
        }

        if(data.hasCounter()){
            holder.counterContainer.setVisibility(View.VISIBLE);
            holder.recommend_c.setText(String.valueOf(data.getRecommend_c()));
        }else{
            //no counter
            holder.counterContainer.setVisibility(View.GONE);
        }

        holder.thumb.setVisibility(View.VISIBLE);
        bindThumbnail(holder, data);
        if(bookmark>0 && resume) {
            holder.resume.setVisibility(View.VISIBLE);
            holder.resumeSiteIcon.setVisibility(View.VISIBLE);
            bindResumeSiteIcon(holder.resumeSiteIcon, bindMeta.sourceSite);
        }
        else {
            holder.resume.setVisibility(View.GONE);
            holder.resumeSiteIcon.setVisibility(View.GONE);
        }

    }

    public void releaseDeferredThumbnails(RecyclerView recyclerView) {
        if(!deferThumbnails)
            return;
        deferThumbnails = false;
        bindVisibleThumbnails(recyclerView);
    }

    private void bindVisibleThumbnails(RecyclerView recyclerView) {
        if(recyclerView == null) {
            notifyAllItemsChanged();
            return;
        }
        int childCount = recyclerView.getChildCount();
        if(childCount <= 0) {
            notifyAllItemsChanged();
            return;
        }
        for(int i = 0; i < childCount; i++) {
            View child = recyclerView.getChildAt(i);
            RecyclerView.ViewHolder baseHolder = recyclerView.getChildViewHolder(child);
            if(!(baseHolder instanceof ViewHolder))
                continue;
            int position = baseHolder.getAdapterPosition();
            if(!isValidPosition(position))
                position = recyclerView.getChildAdapterPosition(child);
            if(!isValidPosition(position))
                continue;
            bindThumbnail((ViewHolder) baseHolder, mDataFiltered.get(position));
        }
    }

    private void notifyAllItemsChanged() {
        int count = getItemCount();
        if(count > 0)
            notifyItemRangeChanged(0, count);
    }

    private void notifyListReplaced(int oldSize, int newSize) {
        int common = Math.min(oldSize, newSize);
        if(common > 0)
            notifyItemRangeChanged(0, common);
        if(newSize > oldSize)
            notifyItemRangeInserted(oldSize, newSize - oldSize);
        else if(oldSize > newSize)
            notifyItemRangeRemoved(newSize, oldSize - newSize);
    }

    private void bindThumbnail(ViewHolder holder, Title data) {
        String thumb = data == null ? "" : data.getThumb();
        if(thumb == null)
            thumb = "";
        if(!deferThumbnails && thumb.length()>1 && (!save || forceThumbnail)) {
            Object source = isLocalMediaPath(thumb) ? thumb : getGlideUrl(thumb, data.getBaseMode());
            String thumbKey = String.valueOf(source);
            if(!thumbKey.equals(holder.thumb.getTag())) {
                holder.thumb.setTag(thumbKey);
                holder.thumb.setImageResource(R.drawable.app_cover_placeholder);
                try {
                    Glide.with(holder.thumb)
                            .load(source)
                            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                            .override(dp(126), dp(170))
                            .thumbnail(0.25f)
                            .dontAnimate()
                            .placeholder(R.drawable.app_cover_placeholder)
                            .into(holder.thumb);
                } catch (RuntimeException e) {
                    ml.melun.mangaview.report.CrashReporter.record(e);
                    holder.thumb.setImageResource(R.drawable.app_cover_placeholder);
                }
            }
        }
        else {
            if(!ThumbnailBindPolicy.TAG_PLACEHOLDER.equals(holder.thumb.getTag())) {
                safeGlideClear(holder.thumb);
                holder.thumb.setTag(ThumbnailBindPolicy.TAG_PLACEHOLDER);
                holder.thumb.setImageResource(R.drawable.app_cover_placeholder);
            }
        }
    }

    private BindMeta bindMeta(Title title) {
        if(title == null)
            return BindMeta.EMPTY;
        refreshClassificationTags(title);
        applyStoredBookmark(title);
        if(deferThumbnails)
            return fastInitialBindMeta(title);
        String key = titleContentKey(title)
                + "|" + title.getBookmark()
                + "|" + title.getBookmarkEpisodeId()
                + "|" + title.getBookmarkEpisodeIndex()
                + "|" + title.getEpisodeCount()
                + "|" + title.getEpsCount()
                + "|" + p.getLocalDataVersion();
        BindMeta cached = bindMetaCache.get(key);
        if(cached != null)
            return cached;
        int progressPercent = readingProgressPercent(title);
        BindMeta meta = new BindMeta(title.getBookmark(), displayTags(title), progressLabel(title), progressPercent, sourceSiteForTitle(title), title.getNtkStatusLabel());
        if(bindMetaCache.size() > 512)
            bindMetaCache.clear();
        bindMetaCache.put(key, meta);
        return meta;
    }

    private BindMeta fastInitialBindMeta(Title title) {
        refreshClassificationTags(title);
        int progressPercent = readingProgressPercent(title);
        String source = title.getSourceSite();
        if(source == null || source.length() == 0)
            source = "wfwf";
        return new BindMeta(title.getBookmark(), displayTags(title), progressLabel(title), progressPercent, source, title.getNtkStatusLabel());
    }

    private void bindResumeSiteIcon(ImageView view, String sourceSite) {
        boolean ntk = "ntk".equals(sourceSite);
        view.setImageResource(ntk ? R.drawable.ic_site_ntk : R.drawable.ic_site_wfwf);
        view.setContentDescription(ntk ? "NTK" : "WFWF");
    }

    private String sourceSiteForTitle(Title title) {
        if(title == null || p == null)
            return "wfwf";
        String source = title.getSourceSite();
        if(source == null || source.length() == 0)
            source = p.resolveSourceSite(title);
        return "ntk".equals(source) ? "ntk" : "wfwf";
    }

    private String sourceKey(MTitle title) {
        if(title == null || p == null)
            return "";
        String source = title.getSourceSite();
        if(source == null || source.length() == 0)
            source = p.resolveKnownSourceSite(title);
        return source == null ? "" : source;
    }

    private String displayTags(Title title) {
        if(title == null)
            return "";
        refreshClassificationTags(title);
        String key = titleContentKey(title) + "|tags";
        String cached = tagTextCache.get(key);
        if(cached != null)
            return cached;
        String value = displayTagsText(title);
        if(tagTextCache.size() > 512)
            tagTextCache.clear();
        tagTextCache.put(key, value);
        return value;
    }

    private static void refreshClassificationTags(Title title) {
        MainPageWebtoon.applyInferredSearchTags(title);
    }

    private static String displayTagsText(Title title) {
        StringBuilder tags = new StringBuilder();
        for(String s : title.getTags()) {
            if(s == null || s.length() == 0)
                continue;
            if(tags.length() > 0)
                tags.append(" / ");
            tags.append(s);
        }
        return tags.toString();
    }

    public static String displayTagsForTest(Title title) {
        if(title == null)
            return "";
        refreshClassificationTags(title);
        return displayTagsText(title);
    }

    int dp(int value) {
        return (int) (value * mainContext.getResources().getDisplayMetrics().density + 0.5f);
    }

    private void applyStoredBookmark(Title title) {
        if(title == null)
            return;
        p.applyStoredProgress(title);
        int bookmark = p.getBookmark(title);
        if(bookmark <= 0)
            bookmark = title.getBookmarkEpisodeId();
        if(bookmark <= 0)
            bookmark = p.getStoredProgressBookmark(title);
        if(bookmark > 0)
            title.setBookmark(bookmark);
    }

    private int resolveResumeBookmark(Title title) {
        if(title == null)
            return -1;
        int bookmark = p.getBookmark(title);
        if(bookmark <= 0)
            bookmark = title.getBookmark();
        if(bookmark <= 0)
            bookmark = title.getBookmarkEpisodeId();
        if(bookmark <= 0)
            bookmark = p.getStoredProgressBookmark(title);
        if(bookmark > 0)
            title.setBookmark(bookmark);
        return bookmark;
    }

    private int readingProgressPercent(Title title) {
        int watchedCount = watchedEpisodeCount(title);
        int episodeCount = totalEpisodeCount(title);
        if(watchedCount > 0 && episodeCount > 0) {
            if(watchedCount >= episodeCount)
                return 100;
            return Math.max(1, Math.min(99, (int) Math.floor(watchedCount * 100f / episodeCount)));
        }
        return 0;
    }

    private String progressLabel(Title title) {
        int watchedCount = watchedEpisodeCount(title);
        int episodeCount = totalEpisodeCount(title);
        if(watchedCount > 0 && episodeCount > 0)
            return watchedCount + "/" + episodeCount + "화까지 봄";
        return "";
    }

    private int watchedEpisodeCount(Title title) {
        if(title == null)
            return 0;
        int episodeIndex = title.getBookmarkEpisodeIndex();
        int episodeCount = totalEpisodeCount(title);
        if(episodeIndex <= 0)
            episodeIndex = title.getBookmarkIndex();
        int fallbackWatched = watchedEpisodeCountFromBookmark(title, episodeCount);
        if(episodeIndex <= 0 && fallbackWatched > 0)
            return fallbackWatched;
        if(episodeIndex <= 0 || episodeCount <= 0)
            return 0;
        return Math.max(1, Math.min(episodeCount, episodeCount - episodeIndex + 1));
    }

    private int totalEpisodeCount(Title title) {
        if(title == null)
            return 0;
        return title.getDisplayEpisodeCount(title.getEpsCount());
    }

    static int watchedEpisodeCountForTest(Title title) {
        if(title == null)
            return 0;
        int episodeIndex = title.getBookmarkEpisodeIndex();
        int episodeCount = title.getDisplayEpisodeCount(title.getEpsCount());
        if(episodeIndex <= 0)
            episodeIndex = title.getBookmarkIndex();
        int fallbackWatched = watchedEpisodeCountFromBookmark(title, episodeCount);
        if(episodeIndex <= 0 && fallbackWatched > 0)
            return fallbackWatched;
        if(episodeIndex <= 0 || episodeCount <= 0)
            return 0;
        return Math.max(1, Math.min(episodeCount, episodeCount - episodeIndex + 1));
    }

    private static int watchedEpisodeCountFromBookmark(Title title, int episodeCount) {
        if(title == null || episodeCount <= 0)
            return 0;
        if(!"ntk".equals(title.getSourceSite()) || title.getBaseMode() != MTitle.base_webtoon)
            return 0;
        int bookmark = title.getBookmarkEpisodeId();
        if(bookmark <= 0)
            bookmark = title.getBookmark();
        if(bookmark <= 0 || bookmark > episodeCount)
            return 0;
        return bookmark;
    }

    static int readingProgressPercentForTest(Title title) {
        int watchedCount = watchedEpisodeCountForTest(title);
        int episodeCount = title == null ? 0 : title.getDisplayEpisodeCount(title.getEpsCount());
        if(watchedCount > 0 && episodeCount > 0) {
            if(watchedCount >= episodeCount)
                return 100;
            return Math.max(1, Math.min(99, (int) Math.floor(watchedCount * 100f / episodeCount)));
        }
        return 0;
    }

    private static final class BindMeta {
        static final BindMeta EMPTY = new BindMeta(0, "", "", 0, "wfwf", "");
        final int bookmark;
        final String tags;
        final String progressLabel;
        final int progressPercent;
        final String sourceSite;
        final String statusLabel;

        BindMeta(int bookmark, String tags, String progressLabel, int progressPercent, String sourceSite, String statusLabel) {
            this.bookmark = bookmark;
            this.tags = tags == null ? "" : tags;
            this.progressLabel = progressLabel == null ? "" : progressLabel;
            this.progressPercent = progressPercent;
            this.sourceSite = sourceSite == null ? "wfwf" : sourceSite;
            this.statusLabel = statusLabel == null ? "" : statusLabel;
        }
    }

    public boolean performItemClick(int position) {
        if(!isValidPosition(position) || mClickListener == null)
            return false;
        mClickListener.onItemClick(position);
        return true;
    }

    public boolean performResumeClick(int position) {
        if(!isValidPosition(position) || mClickListener == null)
            return false;
        long now = android.os.SystemClock.uptimeMillis();
        if(position == lastResumeOpenPosition && now - lastResumeOpenAt < 700)
            return false;
        Title title = mDataFiltered.get(position);
        int bookmark = resolveResumeBookmark(title);
        if(bookmark <= 0)
            return false;
        lastResumeOpenPosition = position;
        lastResumeOpenAt = now;
        warmupResumeAt(position, false);
        mClickListener.onResumeClick(position, bookmark);
        return true;
    }

    public boolean performItemLongClick(View anchorView, int position) {
        if(!longClickEnabled || !isValidPosition(position) || mClickListener == null)
            return false;
        mClickListener.onLongClick(anchorView, position);
        return true;
    }

    @Override
    public int getItemCount() {
        if(mDataFiltered != null)
            return mDataFiltered.size();
        return 0;
    }

    class ViewHolder extends RecyclerView.ViewHolder{
        TextView name;
        ImageView thumb, fav;
        ImageView resumeSiteIcon;
        TextView author;
        TextView tags;
        TextView thumbStatusBadge;
        TextView recommend_c, battery_c, bookmark_c;
        TextView baseModeStr;
        TextView progressText;
        ProgressBar progress;
        ImageButton resume;
        CardView card;
        View content;
        View thumbCard;

        View tagContainer;
        View counterContainer;

        ViewHolder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.Title);
            thumb = itemView.findViewById(R.id.Thumb);
            author =itemView.findViewById(R.id.TitleAuthor);
            tags = itemView.findViewById(R.id.TitleTag);
            thumbStatusBadge = itemView.findViewById(R.id.ThumbStatusBadge);
            card = itemView.findViewById(R.id.titleCard);
            content = itemView.findViewById(R.id.titleContent);
            thumbCard = thumb;
            resume = itemView.findViewById(R.id.epsButton);
            resumeSiteIcon = itemView.findViewById(R.id.TitleResumeSiteIcon);
            recommend_c = itemView.findViewById(R.id.TitleRecommend_c);
            battery_c = itemView.findViewById(R.id.TitleBattery_c);
            bookmark_c = itemView.findViewById(R.id.TitleBookmark_c);
            baseModeStr = itemView.findViewById(R.id.TitleBaseMode);
            progress = itemView.findViewById(R.id.TitleProgress);
            progressText = itemView.findViewById(R.id.TitleProgressText);

            tagContainer = itemView.findViewById(R.id.TitleTagContainer);
            counterContainer = itemView.findViewById(R.id.TitleCounterContainer);


            if(dark){
                card.setCardBackgroundColor(ContextCompat.getColor(mainContext, R.color.colorDarkSurface));
                if(card instanceof MaterialCardView)
                    ((MaterialCardView) card).setStrokeColor(ContextCompat.getColor(mainContext, R.color.colorDarkDivider));
                name.setTextColor(ContextCompat.getColor(mainContext, R.color.colorDarkText));
                author.setTextColor(ContextCompat.getColor(mainContext, R.color.colorDarkTextSecondary));
                tags.setTextColor(ContextCompat.getColor(mainContext, R.color.colorDarkTextSecondary));
                progressText.setTextColor(ContextCompat.getColor(mainContext, R.color.colorDarkTextSecondary));
                baseModeStr.setTextColor(ContextCompat.getColor(mainContext, R.color.appAccent));
                resume.setBackgroundColor(ContextCompat.getColor(mainContext, R.color.resumeDark));
            }
            disableTouchTarget(itemView);
            disableTouchTarget(card);
            disableTouchTarget(content);
            disableTouchTarget(thumbCard);
            disableTouchTarget(name);
            disableTouchTarget(thumb);
            disableTouchTarget(author);
            disableTouchTarget(tags);
            disableTouchTarget(thumbStatusBadge);
            disableTouchTarget(baseModeStr);
            disableTouchTarget(progress);
            disableTouchTarget(progressText);
            disableTouchTarget(tagContainer);
            disableTouchTarget(resume);
        }
    }

    private void disableTouchTarget(View view) {
        if(view == null)
            return;
        view.setOnClickListener(null);
        view.setOnLongClickListener(null);
        view.setOnTouchListener(null);
        view.setClickable(false);
        view.setLongClickable(false);
        view.setFocusable(false);
    }

    public void setResume(boolean resume){
        this.resume = resume;
    }

    public void setLongClickEnabled(boolean longClickEnabled) {
        this.longClickEnabled = longClickEnabled;
    }
    public Title getItem(int index) {
        if(!isValidPosition(index))
            return null;
        return mDataFiltered.get(index);
    }

    private boolean isValidPosition(int position) {
        return isValidTitlePosition(mDataFiltered, position);
    }

    private static boolean isValidTitlePosition(List<?> data, int position) {
        return position != RecyclerView.NO_POSITION
                && data != null
                && position >= 0
                && position < data.size();
    }

    static boolean isValidTitlePositionForTest(List<?> data, int position) {
        return isValidTitlePosition(data, position);
    }

    public void setClickListener(ItemClickListener itemClickListener) {
        this.mClickListener = itemClickListener;
    }

    public interface ItemClickListener {
        void onItemClick(int position);
        void onLongClick(View view, int position);
        void onResumeClick(int position, int id);
    }



    // filter

    @Override
    public Filter getFilter() {
        return filter;
    }
}
