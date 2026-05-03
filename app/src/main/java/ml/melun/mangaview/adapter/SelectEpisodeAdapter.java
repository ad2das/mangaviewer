package ml.melun.mangaview.adapter;

import android.content.Context;
import android.graphics.Color;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.json.JSONArray;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import ml.melun.mangaview.R;
import ml.melun.mangaview.mangaview.Manga;

import static ml.melun.mangaview.MainApplication.p;

public class SelectEpisodeAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private final List<Manga> data;
    private final LayoutInflater mInflater;
    private final Context mainContext;
    boolean favorite = false;
    TypedValue outValue;
    boolean[] selected;
    ItemClickListener mClickListener;
    boolean dark;
    boolean single = true;
    int rs = -1, re = -1;

    // data is passed into the constructor
    public SelectEpisodeAdapter(Context context, List<Manga> list) {
        this.mInflater = LayoutInflater.from(context);
        mainContext = context;
        this.data = list == null ? new ArrayList<>() : list;
        outValue = new TypedValue();
        selected = new boolean[this.data.size()];
        Arrays.fill(selected,Boolean.FALSE);
        dark = p.getDarkTheme();
        mainContext.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        setHasStableIds(true);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    // inflates the row layout from xml when needed
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = mInflater.inflate(R.layout.item_episode, parent, false);
        return new ViewHolder(view);
    }

    // binds the data to the TextView in each row
    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        ViewHolder h = (ViewHolder) holder;
        try {
            Manga m = data.get(position);
            h.episode.setText(m.getName());
            h.date.setText(m.getDate());
            if (selected[position]) {
                if(dark) h.itemView.setBackgroundColor(ContextCompat.getColor(mainContext, R.color.selectedDark));
                else h.itemView.setBackgroundColor(ContextCompat.getColor(mainContext, R.color.selected));
            } else {
                h.itemView.setBackgroundColor(Color.TRANSPARENT);
            }

            if(position == rs || position == re){
                h.itemView.setBackgroundColor(ContextCompat.getColor(mainContext, R.color.rangeSelected));
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    // total number of rows
    @Override
    public int getItemCount() {
        return data == null ? 0 : data.size();
    }

    public void select(int position){
        if(!isValidPosition(position))
            return;
        if(single) {
            selected[position] = !selected[position];
            notifyItemChanged(position);
        }else{
            //range start
            if(rs == -1 && re == -1){
                rs = position;
            }
            //selected pos = range start
            else if(position == rs){
                int oldStart = rs;
                int oldEnd = re;
                rs = -1;
                re = -1;
                notifyIfValid(oldStart);
                notifyIfValid(oldEnd);
            }
            //range end
            else if(rs != -1 && re == -1){
                re = position;
                while(rs != re){
                    selected[rs] = !selected[rs];
                    notifyItemChanged(rs);

                    if(rs>re) rs--;
                    else rs++;
                }
                selected[rs] = !selected[rs];
                notifyItemChanged(rs);

                rs = -1;
                re = -1;
            }
            notifyIfValid(rs);
            notifyIfValid(re);
            notifyIfValid(position);
        }
    }

    public void setSelectionMode(boolean single){
        this.single = single;
        int tmps = rs;
        int tmpe = re;
        rs = -1;
        re = -1;
        notifyIfValid(tmps);
        notifyIfValid(tmpe);
    }

    // stores and recycles views as they are scrolled off screen
    public class ViewHolder extends RecyclerView.ViewHolder{
        TextView episode, date;
        ViewHolder(View itemView) {
            super(itemView);
            episode = itemView.findViewById(R.id.episode);
            date = itemView.findViewById(R.id.date);
            if(dark){
                date.setTextColor(Color.WHITE);
                episode.setTextColor(Color.WHITE);
            }
            else{
                date.setTextColor(Color.BLACK);
                episode.setTextColor(Color.BLACK);
            }
            itemView.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if(position == RecyclerView.NO_POSITION || mClickListener == null || !isValidPosition(position))
                    return;
                mClickListener.onItemClick(v, position);
            });
        }
    }
    public void setClickListener(ItemClickListener itemClickListener) {
        this.mClickListener = itemClickListener;
    }
    public JSONArray getSelected(boolean all){
        JSONArray tmp = new JSONArray();
        for(int i=0; i<selected.length;i++){
            if(selected[i]) tmp.put(i);
            else if(all) tmp.put(i);
        }
        return tmp;
    }

    public interface ItemClickListener {
        void onItemClick(View view, int position);
    }

    private boolean isValidPosition(int position) {
        return data != null && selected != null && position >= 0 && position < data.size() && position < selected.length;
    }

    private void notifyIfValid(int position) {
        if(isValidPosition(position))
            notifyItemChanged(position);
    }
}
