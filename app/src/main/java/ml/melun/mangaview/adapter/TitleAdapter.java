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
import java.util.List;
import java.util.Locale;

import ml.melun.mangaview.R;
import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Title;

import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.getGlideUrl;
import static ml.melun.mangaview.Utils.isLocalMediaPath;

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
                if(query.isEmpty() || query.length() == 0){
                    mDataFiltered = mData;
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
                    mDataFiltered = filtered;
                }
                FilterResults res = new FilterResults();
                res.values = mDataFiltered;
                return res;
            }

            @Override
            protected void publishResults(CharSequence charSequence, FilterResults filterResults) {
                mDataFiltered = (ArrayList<Title>) filterResults.values;
                notifyItemRangeChanged(0, getItemCount());
            }
        };
    }


    @Override
    public long getItemId(int position) {
        if(!isValidPosition(position))
            return RecyclerView.NO_ID;
        Title title = mDataFiltered.get(position);
        return (title.getBaseMode() + ":" + title.getId()).hashCode();
    }

    public void removeAll(){
        int originSize = mData.size();
        mData.clear();
        mDataFiltered.clear();
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
        if(inserted > 0)
            notifyItemRangeInserted(oSize, inserted);
    }

    public void preloadThumbnails(int startPosition, int count) {
        if(mDataFiltered == null || count <= 0 || (save && !forceThumbnail))
            return;
        int start = Math.max(0, startPosition);
        int end = Math.min(mDataFiltered.size(), start + count);
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
                    .override(dp(96), dp(124))
                    .dontAnimate()
                    .preload();
        }
    }

    public void setData(List<?> t){
        ArrayList<Title> next = normalizeTitles(t);
        final ArrayList<Title> old = new ArrayList<>(mDataFiltered);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return old.size();
            }

            @Override
            public int getNewListSize() {
                return next.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return sameTitle(old.get(oldItemPosition), next.get(newItemPosition));
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                return titleContentKey(old.get(oldItemPosition)).equals(titleContentKey(next.get(newItemPosition)));
            }
        }, false);
        mData = next;
        mDataFiltered = next;
        searching = false;
        diff.dispatchUpdatesTo(this);
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
        return a != null && b != null && a.getId() == b.getId() && a.getBaseMode() == b.getBaseMode();
    }

    private String titleContentKey(Title title) {
        if(title == null)
            return "";
        return title.getName() + "|" + title.getThumb() + "|" + title.getAuthor() + "|"
                + title.getRelease() + "|" + title.getBookmark() + "|" + title.getTags();
    }

    public void clearData(){
        mData.clear();
        mDataFiltered.clear();
        notifyItemRangeChanged(0, getItemCount());
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
        applyStoredBookmark(data);
        String title = data.getName();
        String thumb = data.getThumb();
        if(thumb == null)
            thumb = "";
        String author = data.getAuthor();
        StringBuilder tags = new StringBuilder();
        int bookmark = data.getBookmark();
        holder.baseModeStr.setText(data.getBaseModeStr());
        for (String s : data.getTags()) {
            if(s == null || s.length() == 0)
                continue;
            if(tags.length() > 0)
                tags.append(" / ");
            tags.append(s);
        }
        holder.tags.setText(tags.toString());
        holder.tagContainer.setVisibility(View.VISIBLE);

        holder.name.setText(title);
        String meta = data.getRelease();
        if(meta == null || meta.length() == 0)
            meta = author;
        String progressLabel = progressLabel(data);
        if(progressLabel.length() > 0)
            meta = progressLabel;
        holder.author.setText(meta);
        int progressPercent = readingProgressPercent(data);
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

        Glide.with(holder.thumb).clear(holder.thumb);
        holder.thumb.setVisibility(View.VISIBLE);
        if(thumb.length()>1 && (!save || forceThumbnail)) {
            Object source = isLocalMediaPath(thumb) ? thumb : getGlideUrl(thumb, data.getBaseMode());
            Glide.with(holder.thumb)
                    .load(source)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .override(dp(96), dp(124))
                    .thumbnail(0.25f)
                    .dontAnimate()
                    .into(holder.thumb);
        }
        else holder.thumb.setImageResource(R.drawable.app_cover_placeholder);
        if(bookmark>0 && resume) holder.resume.setVisibility(View.VISIBLE);
        else holder.resume.setVisibility(View.GONE);

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
            bookmark = findStoredProgressBookmark(title);
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
            bookmark = findStoredProgressBookmark(title);
        if(bookmark > 0)
            title.setBookmark(bookmark);
        return bookmark;
    }

    private int findStoredProgressBookmark(Title title) {
        int bookmark = findStoredProgressBookmark(title, p.getRecent());
        if(bookmark > 0)
            return bookmark;
        return findStoredProgressBookmark(title, p.getFavorite());
    }

    private int findStoredProgressBookmark(Title title, List<MTitle> source) {
        if(title == null || source == null)
            return -1;
        for(MTitle stored : source) {
            if(stored == null)
                continue;
            if(stored.getId() == title.getId()
                    && stored.getBaseMode() == title.getBaseMode()
                    && stored.getBookmarkEpisodeId() > 0)
                return stored.getBookmarkEpisodeId();
        }
        return -1;
    }

    private int readingProgressPercent(Title title) {
        int watchedCount = watchedEpisodeCount(title);
        int episodeCount = totalEpisodeCount(title);
        if(watchedCount > 0 && episodeCount > 0)
            return Math.max(1, Math.min(100, Math.round(watchedCount * 100f / episodeCount)));
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
        int episodeCount = title.getEpisodeCount();
        if(episodeCount <= 0)
            episodeCount = title.getEpsCount();
        return episodeCount;
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
            thumbCard = itemView.findViewById(R.id.thumbCard);
            resume = itemView.findViewById(R.id.epsButton);
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
            View.OnLongClickListener longClickListener = v -> {
                if(!longClickEnabled)
                    return false;
                int position = getAdapterPosition();
                if(!isValidPosition(position) || mClickListener == null)
                    return false;
                mClickListener.onLongClick(v, position);
                return true;
            };
            itemView.setOnLongClickListener(longClickListener);
            card.setOnLongClickListener(longClickListener);
            content.setOnLongClickListener(longClickListener);
            thumbCard.setOnLongClickListener(longClickListener);
            name.setOnLongClickListener(longClickListener);
            thumb.setOnLongClickListener(longClickListener);
            author.setOnLongClickListener(longClickListener);
            tags.setOnLongClickListener(longClickListener);
            baseModeStr.setOnLongClickListener(longClickListener);
            progress.setOnLongClickListener(longClickListener);
            progressText.setOnLongClickListener(longClickListener);
            tagContainer.setOnLongClickListener(longClickListener);
            View.OnClickListener clickListener = v -> openItem();
            itemView.setOnClickListener(clickListener);
            card.setOnClickListener(clickListener);
            content.setOnClickListener(clickListener);
            thumbCard.setOnClickListener(clickListener);
            name.setOnClickListener(clickListener);
            thumb.setOnClickListener(clickListener);
            author.setOnClickListener(clickListener);
            tags.setOnClickListener(clickListener);
            baseModeStr.setOnClickListener(clickListener);
            progress.setOnClickListener(clickListener);
            progressText.setOnClickListener(clickListener);
            tagContainer.setOnClickListener(clickListener);
            resume.setOnClickListener(v -> openResume());
            resume.setOnLongClickListener(longClickListener);

        }

        private void openItem() {
            int position = getAdapterPosition();
            if(!isValidPosition(position) || mClickListener == null)
                return;
            mClickListener.onItemClick(position);
        }

        private void openResume() {
            int position = getAdapterPosition();
            if(!isValidPosition(position) || mClickListener == null)
                return;
            Title title = mDataFiltered.get(position);
            int bookmark = resolveResumeBookmark(title);
            if(bookmark <= 0)
                return;
            mClickListener.onResumeClick(position, bookmark);
        }
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
