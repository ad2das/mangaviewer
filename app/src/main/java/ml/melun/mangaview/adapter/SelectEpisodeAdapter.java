package ml.melun.mangaview.adapter;

import android.content.Context;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.cardview.widget.CardView;

import org.json.JSONArray;


import java.util.Arrays;
import java.util.Collections;
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
        this.data = list == null ? Collections.emptyList() : list;
        outValue = new TypedValue();
        selected = new boolean[data.size()];
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
            if(!isValidSelectionPosition(data, selected, position)) {
                h.episode.setText("");
                h.date.setText("");
                setRowBackground(h, dark ? R.color.colorDarkSurface : R.color.appCard);
                return;
            }
            Manga m = data.get(position);
            h.episode.setText(m == null ? "" : m.getName());
            h.date.setText(m == null ? "" : m.getDate());
            if (selected[position]) {
                setRowBackground(h, dark ? R.color.selectedDark : R.color.appAccentLight);
            } else {
                setRowBackground(h, dark ? R.color.colorDarkSurface : R.color.appCard);
            }

            if(position == rs || position == re){
                setRowBackground(h, R.color.appAccent);
            }
        }catch (Exception e){
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    // total number of rows
    @Override
    public int getItemCount() {
        return data == null ? 0 : data.size();
    }

    private void setRowBackground(ViewHolder holder, int colorRes) {
        int color = ContextCompat.getColor(mainContext, colorRes);
        if(holder.card != null)
            holder.card.setCardBackgroundColor(color);
        else
            holder.itemView.setBackgroundColor(color);
        if(holder.cardContent != null)
            holder.cardContent.setBackgroundColor(color);
    }

    public void select(int position){
        if(!isValidSelectionPosition(data, selected, position))
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
                rs = -1;
                re = -1;
            }
            //range end
            else if(rs != -1 && re == -1){
                re = position;
                while(rs != re){
                    if(!isValidSelectionPosition(data, selected, rs))
                        break;
                    selected[rs] = !selected[rs];
                    notifyItemChanged(rs);

                    if(rs>re) rs--;
                    else rs++;
                }
                if(isValidSelectionPosition(data, selected, rs)) {
                    selected[rs] = !selected[rs];
                    notifyItemChanged(rs);
                }

                rs = -1;
                re = -1;
            }
            notifySelectionChanged(rs);
            notifySelectionChanged(re);
            notifyItemChanged(position);
        }
    }

    public void setSelectionMode(boolean single){
        this.single = single;
        int tmps = rs;
        int tmpe = re;
        rs = -1;
        re = -1;
        notifySelectionChanged(tmps);
        notifySelectionChanged(tmpe);
    }

    // stores and recycles views as they are scrolled off screen
    public class ViewHolder extends RecyclerView.ViewHolder{
        TextView episode, date;
        CardView card, thumbCard;
        View action, cardContent, newBadge;
        ViewHolder(View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.episodeCard);
            cardContent = itemView.findViewById(R.id.episodeCardContent);
            episode = itemView.findViewById(R.id.episode);
            date = itemView.findViewById(R.id.date);
            thumbCard = itemView.findViewById(R.id.episodeThumbCard);
            action = itemView.findViewById(R.id.episodeAction);
            newBadge = itemView.findViewById(R.id.episodeNew);
            thumbCard.setVisibility(View.GONE);
            action.setVisibility(View.GONE);
            newBadge.setVisibility(View.GONE);
            if(dark){
                itemView.setBackgroundColor(ContextCompat.getColor(mainContext, R.color.colorDarkWindowBackground));
                if(cardContent != null)
                    cardContent.setBackgroundColor(ContextCompat.getColor(mainContext, R.color.colorDarkSurface));
                date.setTextColor(ContextCompat.getColor(mainContext, R.color.colorDarkTextSecondary));
                episode.setTextColor(ContextCompat.getColor(mainContext, R.color.colorDarkText));
            }
            else{
                date.setTextColor(ContextCompat.getColor(mainContext, R.color.appTextSecondary));
                episode.setTextColor(ContextCompat.getColor(mainContext, R.color.appText));
            }
            itemView.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if(position == RecyclerView.NO_POSITION || mClickListener == null || !isValidSelectionPosition(data, selected, position))
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

    private void notifySelectionChanged(int position) {
        if(isValidSelectionPosition(data, selected, position))
            notifyItemChanged(position);
    }

    private static boolean isValidSelectionPosition(List<?> data, boolean[] selected, int position) {
        return data != null
                && selected != null
                && position >= 0
                && position < data.size()
                && position < selected.length;
    }

    static boolean isValidSelectionPositionForTest(List<?> data, boolean[] selected, int position) {
        return isValidSelectionPosition(data, selected, position);
    }

    public interface ItemClickListener {
        void onItemClick(View view, int position);
    }
}
