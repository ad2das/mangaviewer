package ml.melun.mangaview.adapter;
import android.content.Context;
import androidx.core.content.ContextCompat;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.MotionEvent;
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

import ml.melun.mangaview.R;
import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;
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
        filter = new Filter() {
            @Override
            protected FilterResults performFiltering(CharSequence charSequence) {
                String query = charSequence.toString();
                if(query.isEmpty() || query.length() == 0){
                    mDataFiltered = mData;
                    searching = false;
                }else{
                    searching = true;
                    ArrayList<Title> filtered = new ArrayList<>();
                    for(Title t : mData){
                        if(t.getName().toLowerCase().contains(query.toLowerCase()) || t.getAuthor().toLowerCase().contains(query.toLowerCase()))
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
                notifyDataSetChanged();
            }
        };
    }


    @Override
    public long getItemId(int position) {
        return position;
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

    public void setData(List<?> t){
        clearData();
        addData(t);
    }

    public void clearData(){
        mData.clear();
        mDataFiltered.clear();
        notifyDataSetChanged();
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
        if(isOfflineTitle(title)) {
            int offlineBookmark = p.applyOfflineProgress(title) ? title.getBookmark() : resolveOfflineBookmark(title);
            if(offlineBookmark > 0) {
                title.setBookmark(offlineBookmark);
                applyOfflineReadingProgress(title, offlineBookmark);
            } else {
                title.setBookmark(0);
                title.setReadingProgress(-1, -1, totalEpisodeCount(title));
            }
            return;
        }
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
        if(isOfflineTitle(title)) {
            int bookmark = p.applyOfflineProgress(title) ? title.getBookmark() : resolveOfflineBookmark(title);
            if(bookmark > 0) {
                title.setBookmark(bookmark);
                applyOfflineReadingProgress(title, bookmark);
            }
            return bookmark;
        }
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

    private boolean isOfflineTitle(Title title) {
        return title != null && title.getPath() != null && title.getPath().length() > 0;
    }

    private int resolveOfflineBookmark(Title title) {
        if(title == null || title.getEps() == null)
            return -1;
        for(Manga episode : title.getEps()) {
            if(episode == null || episode.getId() <= 0)
                continue;
            episode.setTitle(title);
            episode.setTitleId(title.getId());
            if(p.getViewerBookmark(episode) > 0 || p.getViewerBookmarkOffset(episode) != 0)
                return episode.getId();
        }
        return -1;
    }

    private void applyOfflineReadingProgress(Title title, int bookmark) {
        if(title == null || title.getEps() == null || bookmark <= 0)
            return;
        int episodeIndex = -1;
        for(int i = 0; i < title.getEps().size(); i++) {
            Manga episode = title.getEps().get(i);
            if(episode != null && episode.getId() == bookmark) {
                episodeIndex = i + 1;
                break;
            }
        }
        title.setReadingProgress(bookmark, episodeIndex, title.getEpsCount());
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
        TextView recommend_c, battery_c, comment_c, bookmark_c;
        TextView baseModeStr;
        TextView progressText;
        ProgressBar progress;
        ImageButton resume;
        CardView card;

        View tagContainer;
        View counterContainer;

        ViewHolder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.Title);
            thumb = itemView.findViewById(R.id.Thumb);
            author =itemView.findViewById(R.id.TitleAuthor);
            tags = itemView.findViewById(R.id.TitleTag);
            card = itemView.findViewById(R.id.titleCard);
            resume = itemView.findViewById(R.id.epsButton);
            recommend_c = itemView.findViewById(R.id.TitleRecommend_c);
            battery_c = itemView.findViewById(R.id.TitleBattery_c);
            comment_c = itemView.findViewById(R.id.TitleComment_c);
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
            card.setOnClickListener(v -> {
                openItem();
            });
            card.setOnLongClickListener(v -> {
                int position = getAdapterPosition();
                if(position == RecyclerView.NO_POSITION || mClickListener == null)
                    return false;
                mClickListener.onLongClick(v, position);
                return true;
            });
            resume.setOnClickListener(v -> {
                openResume();
            });
            resume.setOnTouchListener((v, event) -> {
                if(event.getAction() == MotionEvent.ACTION_DOWN) {
                    v.setPressed(true);
                    return true;
                }
                if(event.getAction() == MotionEvent.ACTION_UP) {
                    v.setPressed(false);
                    v.performClick();
                    return true;
                }
                if(event.getAction() == MotionEvent.ACTION_CANCEL) {
                    v.setPressed(false);
                    return true;
                }
                return true;
            });

        }

        private void openItem() {
            int position = getAdapterPosition();
            if(position == RecyclerView.NO_POSITION || mClickListener == null)
                return;
            mClickListener.onItemClick(position);
        }

        private void openResume() {
            int position = getAdapterPosition();
            if(position == RecyclerView.NO_POSITION || mClickListener == null)
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
    public Title getItem(int index) {
        return mDataFiltered.get(index);
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
