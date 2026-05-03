package ml.melun.mangaview.adapter;
import android.content.Context;
import androidx.core.content.ContextCompat;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ml.melun.mangaview.Preference;
import ml.melun.mangaview.R;
import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Title;

import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.getGlideUrl;

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
        p = new Preference(context);
        dark = p.getDarkTheme();
        save = p.getDataSave();
        this.mInflater = LayoutInflater.from(context);
        mainContext = context;
        this.mData = new ArrayList<>();
        this.mDataFiltered = new ArrayList<>();
        filter = new Filter() {
            @Override
            protected FilterResults performFiltering(CharSequence charSequence) {
                String query = charSequence == null ? "" : charSequence.toString();
                FilterPayload payload;
                if(query.isEmpty() || query.length() == 0){
                    payload = new FilterPayload(new ArrayList<>(mData), false);
                }else{
                    String lowerQuery = query.toLowerCase(Locale.ROOT);
                    ArrayList<Title> filtered = new ArrayList<>();
                    for(Title t : mData){
                        if(t == null)
                            continue;
                        String name = t.getName() == null ? "" : t.getName();
                        String author = t.getAuthor() == null ? "" : t.getAuthor();
                        if(name.toLowerCase(Locale.ROOT).contains(lowerQuery) || author.toLowerCase(Locale.ROOT).contains(lowerQuery))
                            filtered.add(t);
                    }
                    payload = new FilterPayload(filtered, true);
                }
                FilterResults res = new FilterResults();
                res.values = payload;
                res.count = payload.data.size();
                return res;
            }

            @Override
            protected void publishResults(CharSequence charSequence, FilterResults filterResults) {
                if(!(filterResults.values instanceof FilterPayload))
                    return;
                FilterPayload payload = (FilterPayload) filterResults.values;
                searching = payload.searching;
                updateFilteredData(searching ? payload.data : mData);
            }
        };
    }


    @Override
    public long getItemId(int position) {
        return position;
    }

    public void removeAll(){
        int originSize = getItemCount();
        mData.clear();
        if(mDataFiltered != mData)
            mDataFiltered.clear();
        mDataFiltered = mData;
        searching = false;
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
        int visibleSize = getItemCount();
        boolean showingAll = !searching && mDataFiltered == mData;
        int inserted = 0;
        for(Object d:t){
            if(d instanceof Title){
                Title title = (Title)d;
                if(containsTitle(title))
                    continue;
                title.setBookmark(p.getBookmark(title));
                mData.add(title);
                inserted++;
            } else if(d instanceof MTitle){
                Title d2 = new Title((MTitle)d);
                if(containsTitle(d2))
                    continue;
                d2.setBookmark(p.getBookmark((MTitle) d));
                mData.add(d2);
                inserted++;
            }
        }
        if(inserted > 0) {
            if(showingAll && visibleSize == oSize) {
                mDataFiltered = mData;
                searching = false;
                notifyItemRangeInserted(oSize, inserted);
            } else {
                searching = false;
                updateFilteredData(mData);
            }
        } else {
            mDataFiltered = mData;
            searching = false;
        }
    }

    private boolean containsTitle(Title title) {
        if(title == null)
            return false;
        for(Title current : mData) {
            if(current != null
                    && current.getId() == title.getId()
                    && current.getBaseMode() == title.getBaseMode())
                return true;
        }
        return false;
    }

    public void setData(List<?> t){
        clearData();
        addData(t);
    }

    public void clearData(){
        int originSize = getItemCount();
        mData.clear();
        if(mDataFiltered != mData)
            mDataFiltered.clear();
        mDataFiltered = mData;
        searching = false;
        if(originSize > 0)
            notifyItemRangeRemoved(0, originSize);
    }


    public void moveItemToTop(int from){
        if(!isValidPosition(from))
            return;
        if(!searching) {
            mData.add(0, mData.get(from));
            mData.remove(from + 1);
            for (int i = from; i > 0; i--) {
                notifyItemMoved(i, i - 1);
            }
        }else{
            Title t = mDataFiltered.get(from);
            int index = mData.indexOf(t);
            if(index < 0)
                return;
            mData.add(0, mData.get(index));
            mData.remove(index + 1);
        }
    }

    public void remove(int pos){
        if(!isValidPosition(pos))
            return;
        if(!searching) {
            mData.remove(pos);
            notifyItemRemoved(pos);
        }else{
            Title t = mDataFiltered.get(pos);
            int index = mData.indexOf(t);
            if(index < 0)
                return;
            mData.remove(index);
            mDataFiltered.remove(pos);
            notifyItemRemoved(pos);
        }
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        Title data = mDataFiltered.get(position);
        String title = data.getName();
        String thumb = data.getThumb();
        if(thumb == null)
            thumb = "";
        String author = data.getAuthor();
        StringBuilder tags = new StringBuilder();
        int bookmark = data.getBookmark();
        holder.tagContainer.setVisibility(View.VISIBLE);
        holder.baseModeStr.setText(data.getBaseModeStr());
        for (String s : data.getTags()) {
            tags.append(s).append(" ");
        }
        holder.tags.setText(tags.toString());

        holder.name.setText(title);
        holder.author.setText(author);

        if(data.hasCounter()){
            holder.counterContainer.setVisibility(View.VISIBLE);
            holder.recommend_c.setText(String.valueOf(data.getRecommend_c()));
        }else{
            //no counter
            holder.counterContainer.setVisibility(View.GONE);
        }

        Glide.with(holder.thumb).clear(holder.thumb);
        if(thumb.length()>1 && (!save || forceThumbnail)) Glide.with(holder.thumb).load(getGlideUrl(thumb, data.getBaseMode())).into(holder.thumb);
        else holder.thumb.setImageBitmap(null);
        if(save && !forceThumbnail) holder.thumb.setVisibility(View.GONE);
        if(bookmark>0 && resume) holder.resume.setVisibility(View.VISIBLE);
        else holder.resume.setVisibility(View.GONE);

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

            tagContainer = itemView.findViewById(R.id.TitleTagContainer);
            counterContainer = itemView.findViewById(R.id.TitleCounterContainer);


            if(dark){
                card.setBackgroundColor(ContextCompat.getColor(mainContext, R.color.colorDarkBackground));
                resume.setBackgroundColor(ContextCompat.getColor(mainContext, R.color.resumeDark));
            }
            card.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if(!isValidClickPosition(position) || mClickListener == null)
                    return;
                mClickListener.onItemClick(position);
            });
            card.setOnLongClickListener(v -> {
                int position = getAdapterPosition();
                if(!isValidClickPosition(position) || mClickListener == null)
                    return false;
                mClickListener.onLongClick(v, position);
                return true;
            });
            resume.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if(!isValidClickPosition(position) || mClickListener == null)
                    return;
                mClickListener.onResumeClick(position, p.getBookmark(mDataFiltered.get(position)));
            });


        }
    }

    public void setResume(boolean resume){
        this.resume = resume;
    }
    public Title getItem(int index) {
        if(!isValidPosition(index))
            return null;
        return mDataFiltered.get(index);
    }

    public boolean isValidPosition(int index) {
        return mDataFiltered != null && index >= 0 && index < mDataFiltered.size();
    }

    private boolean isValidClickPosition(int index) {
        return index != RecyclerView.NO_POSITION && isValidPosition(index);
    }

    private void updateFilteredData(List<Title> nextData) {
        final List<Title> oldItems = mDataFiltered == null ? new ArrayList<>() : new ArrayList<>(mDataFiltered);
        final ArrayList<Title> nextItems = nextData == mData ? mData : new ArrayList<>(nextData);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return oldItems.size();
            }

            @Override
            public int getNewListSize() {
                return nextItems.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return sameTitle(oldItems.get(oldItemPosition), nextItems.get(newItemPosition));
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                return titleContentKey(oldItems.get(oldItemPosition)).equals(titleContentKey(nextItems.get(newItemPosition)));
            }
        }, false);
        mDataFiltered = nextItems;
        diff.dispatchUpdatesTo(this);
    }

    private boolean sameTitle(Title a, Title b) {
        return a != null && b != null
                && a.getBaseMode() == b.getBaseMode()
                && a.getId() == b.getId();
    }

    private String titleContentKey(Title title) {
        if(title == null)
            return "";
        return title.getBaseMode()
                + ":" + title.getId()
                + ":" + title.getName()
                + ":" + title.getAuthor()
                + ":" + title.getThumb()
                + ":" + title.getRelease()
                + ":" + title.getTags()
                + ":" + title.getBookmark()
                + ":" + title.hasCounter()
                + ":" + title.getRecommend_c();
    }

    private static class FilterPayload {
        final ArrayList<Title> data;
        final boolean searching;

        FilterPayload(ArrayList<Title> data, boolean searching) {
            this.data = data;
            this.searching = searching;
        }
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
