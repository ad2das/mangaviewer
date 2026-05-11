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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;

import ml.melun.mangaview.R;
import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.runtime.AppDispatchers;

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
    boolean longClickEnabled = true;
    String path = "";
    Filter filter;
    boolean searching = false;
    private final Executor diffExecutor = AppDispatchers.uiDiff();
    private int diffGeneration = 0;
    private long lastResumeOpenAt = 0L;
    private int lastResumeOpenPosition = RecyclerView.NO_POSITION;
    private final Map<String, String> tagTextCache = new HashMap<>();
    private final Map<String, BindMeta> bindMetaCache = new HashMap<>();

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
                applyStoredBookmark(title);
                mData.add(title);
                inserted++;
            } else if(d instanceof MTitle){
                Title d2 = new Title((MTitle)d);
                applyStoredBookmark(d2);
                mData.add(d2);
                inserted++;
            }
        }
        mDataFiltered = mData;
        bindMetaCache.clear();
        if(inserted > 0)
            notifyItemRangeInserted(oSize, inserted);
    }

    public void preloadThumbnails(int startPosition, int count) {
        if(mDataFiltered == null || count <= 0 || (save && !forceThumbnail))
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

    public void setData(List<?> t){
        ArrayList<Title> next = normalizeTitles(t);
        mData = next;
        searching = false;
        tagTextCache.clear();
        bindMetaCache.clear();
        dispatchFilteredList(next);
    }

    public void setDataImmediate(List<?> t){
        ArrayList<Title> next = normalizeTitles(t);
        mData = next;
        mDataFiltered = new ArrayList<>(next);
        searching = false;
        diffGeneration++;
        tagTextCache.clear();
        bindMetaCache.clear();
        notifyDataSetChanged();
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
            applyStoredBookmark(title);
            titles.add(title);
        }
        return titles;
    }

    private boolean sameTitle(Title a, Title b) {
        return a != null && b != null
                && a.getId() == b.getId()
                && a.getBaseMode() == b.getBaseMode()
                && sourceKey(a).equals(sourceKey(b));
    }

    private String titleContentKey(Title title) {
        if(title == null)
            return "";
        return title.getName() + "|" + title.getThumb() + "|" + title.getAuthor() + "|"
                + title.getRelease() + "|" + title.getBookmark() + "|" + title.getTags()
                + "|" + title.getSourceSite();
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
        if(!searching) {
            mData.add(0, mData.get(from));
            mData.remove(from + 1);
            for (int i = from; i > 0; i--) {
                notifyItemMoved(i, i - 1);
            }
        }else{
            Title t = mDataFiltered.get(from);
            int index = mData.indexOf(t);
            mData.add(0, mData.get(index));
            mData.remove(index + 1);
        }
    }

    public void remove(int pos){
        if(!searching) {
            mData.remove(pos);
            notifyItemRemoved(pos);
        }else{
            Title t = mDataFiltered.get(pos);
            int index = mData.indexOf(t);
            mData.remove(index);
            mDataFiltered.remove(pos);
            notifyItemRemoved(pos);
        }
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        Title data = mDataFiltered.get(position);
        BindMeta bindMeta = bindMeta(data);
        String title = data.getName();
        String thumb = data.getThumb();
        if(thumb == null)
            thumb = "";
        String author = data.getAuthor();
        int bookmark = bindMeta.bookmark;
        holder.baseModeStr.setText(data.getBaseModeStr());
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
        if(thumb.length()>1 && (!save || forceThumbnail)) {
            Object source = isLocalMediaPath(thumb) ? thumb : getGlideUrl(thumb, data.getBaseMode());
            String thumbKey = String.valueOf(source);
            if(!thumbKey.equals(holder.thumb.getTag())) {
                safeGlideClear(holder.thumb);
                holder.thumb.setTag(thumbKey);
                try {
                    Glide.with(holder.thumb)
                            .load(source)
                            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                            .override(dp(126), dp(170))
                            .thumbnail(0.25f)
                            .dontAnimate()
                            .into(holder.thumb);
                } catch (RuntimeException e) {
                    ml.melun.mangaview.report.CrashReporter.record(e);
                    holder.thumb.setImageResource(R.drawable.app_cover_placeholder);
                }
            }
        }
        else {
            if(!"placeholder".equals(holder.thumb.getTag())) {
                safeGlideClear(holder.thumb);
                holder.thumb.setTag("placeholder");
                holder.thumb.setImageResource(R.drawable.app_cover_placeholder);
            }
        }
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

    private BindMeta bindMeta(Title title) {
        if(title == null)
            return BindMeta.EMPTY;
        String key = titleContentKey(title) + "|" + title.getBookmark() + "|" + p.getLocalDataVersion();
        BindMeta cached = bindMetaCache.get(key);
        if(cached != null)
            return cached;
        applyStoredBookmark(title);
        int progressPercent = readingProgressPercent(title);
        BindMeta meta = new BindMeta(title.getBookmark(), displayTags(title), progressLabel(title), progressPercent, sourceSiteForTitle(title));
        if(bindMetaCache.size() > 512)
            bindMetaCache.clear();
        bindMetaCache.put(key, meta);
        return meta;
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
        String key = titleContentKey(title) + "|tags";
        String cached = tagTextCache.get(key);
        if(cached != null)
            return cached;
        StringBuilder tags = new StringBuilder();
        for(String s : title.getTags()) {
            if(s == null || s.length() == 0)
                continue;
            if(tags.length() > 0)
                tags.append(" / ");
            tags.append(s);
        }
        String value = tags.toString();
        if(tagTextCache.size() > 512)
            tagTextCache.clear();
        tagTextCache.put(key, value);
        return value;
    }

    int dp(int value) {
        return (int) (value * mainContext.getResources().getDisplayMetrics().density + 0.5f);
    }

    private void applyStoredBookmark(Title title) {
        if(title == null)
            return;
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
        if(episodeIndex <= 0 || episodeCount <= 0)
            return 0;
        return Math.max(1, Math.min(episodeCount, episodeCount - episodeIndex + 1));
    }

    private int totalEpisodeCount(Title title) {
        if(title == null)
            return 0;
        return title.getDisplayEpisodeCount(title.getEpsCount());
    }

    private static final class BindMeta {
        static final BindMeta EMPTY = new BindMeta(0, "", "", 0, "wfwf");
        final int bookmark;
        final String tags;
        final String progressLabel;
        final int progressPercent;
        final String sourceSite;

        BindMeta(int bookmark, String tags, String progressLabel, int progressPercent, String sourceSite) {
            this.bookmark = bookmark;
            this.tags = tags == null ? "" : tags;
            this.progressLabel = progressLabel == null ? "" : progressLabel;
            this.progressPercent = progressPercent;
            this.sourceSite = sourceSite == null ? "wfwf" : sourceSite;
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
            card = itemView.findViewById(R.id.titleCard);
            content = itemView.findViewById(R.id.titleContent);
            thumbCard = itemView.findViewById(R.id.Thumb);
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
                card.setBackgroundColor(ContextCompat.getColor(mainContext, R.color.colorDarkBackground));
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
        return position != RecyclerView.NO_POSITION
                && mDataFiltered != null
                && position >= 0
                && position < mDataFiltered.size();
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
