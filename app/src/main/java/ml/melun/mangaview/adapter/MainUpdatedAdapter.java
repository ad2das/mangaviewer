package ml.melun.mangaview.adapter;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.cardview.widget.CardView;
import androidx.core.content.res.ResourcesCompat;
import androidx.recyclerview.widget.RecyclerView;

import android.content.res.Resources;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.util.ArrayList;
import java.util.List;

import ml.melun.mangaview.R;
import ml.melun.mangaview.mangaview.Manga;

import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.getGlideUrl;
import static ml.melun.mangaview.mangaview.MTitle.base_auto;

public class MainUpdatedAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    List<Manga> mData;
    Context context;
    LayoutInflater mInflater;
    boolean loaded = false;
    OnClickCallback monclick;
    boolean dark, save;
    Resources res;

    public MainUpdatedAdapter(Context c) {
        context = c;
        this.mInflater = LayoutInflater.from(c);
        dark = p.getDarkTheme();
        save = p.getDataSave();
        this.res = context.getResources();

        //fetch data with async
        //data initialize
        setHasStableIds(true);
        //setNull();
    }

    public void setLoad(){
        setLoad("로드중...");
    }

    public void setLoad(String msg){
        if(mData != null){
            int size = mData.size();
            mData.clear();
            loaded = false;
            if(size > 0)
                notifyItemRangeRemoved(0,size);
        }
        else
            mData = new ArrayList<>();
        Manga loading = new Manga(0,msg,"", base_auto);
        loading.addThumb("");
        mData.add(loading);
        notifyItemInserted(0);
    }

    @Override
    public long getItemId(int position) {
        if(mData == null || position < 0 || position >= mData.size())
            return RecyclerView.NO_ID;
        Manga manga = mData.get(position);
        if(manga.getId() > 0)
            return (((long) manga.getBaseMode()) << 32) ^ manga.getId();
        return position;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = mInflater.inflate(R.layout.item_updated, parent, false);
        return new viewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        viewHolder h = (viewHolder) holder;
        Manga manga = mData.get(position);
        h.title.setText(manga.getName());
        String thumb = manga.getThumb();
        h.thumb.setColorFilter(null);
        Glide.with(h.thumb).clear(h.thumb);
        if(thumb != null && thumb.length()==0)
            h.thumb.setImageResource(android.R.color.transparent);
        else if(thumb != null && thumb.equals("reload")) {
            h.thumb.setImageDrawable(ResourcesCompat.getDrawable(res, R.drawable.ic_refresh, null));
            h.thumb.setColorFilter(dark ? Color.WHITE : Color.DKGRAY);
        }else if(save)
            h.thumb.setImageDrawable(ResourcesCompat.getDrawable(res, R.mipmap.ic_launcher, null));
        else
            Glide.with(h.thumb)
                    .load(getGlideUrl(thumb, manga.getBaseMode()))
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .override(dp(120), dp(160))
                    .dontAnimate()
                    .into(h.thumb);
    }

    private int dp(int value) {
        return (int) (value * res.getDisplayMetrics().density + 0.5f);
    }


    @Override
    public int getItemCount() {
        return mData == null ? 0 : mData.size();
    }
    public interface OnClickCallback {
        void onclick(Manga m);
        void refresh();
    }

    class viewHolder extends RecyclerView.ViewHolder{
        ImageView thumb;
        TextView title;
        CardView card;
        public viewHolder(View itemView) {
            super(itemView);
            thumb = itemView.findViewById(R.id.main_new_thumb);
            title = itemView.findViewById(R.id.main_new_name);
            title.setEllipsize(TextUtils.TruncateAt.MARQUEE);
            title.setMarqueeRepeatLimit(-1);
            title.setSingleLine(true);
            title.setSelected(true);
            card = itemView.findViewById(R.id.updatedCard);
            card.setOnClickListener(v -> {
                if(monclick == null)
                    return;
                if(loaded){
                    int position = getAdapterPosition();
                    if(position == RecyclerView.NO_POSITION || position >= mData.size())
                        return;
                    monclick.onclick(mData.get(position));
                }else
                    monclick.refresh();
            });
            if(dark){
                card.setBackgroundColor(ContextCompat.getColor(context, R.color.colorDarkBackground));
            }

        }
    }
    public void setClickListener(OnClickCallback o){
        this.monclick = o;
    }

    public void setData(List<Manga> data){
        List<Manga> replacement = new ArrayList<>();
        if(data != null)
            replacement.addAll(data);
        if(replacement.size()==0){
            Manga none = new Manga(0,"결과 없음","", base_auto);
            none.addThumb("reload");
            replacement.add(none);
            loaded = false;
        }else {
            loaded = true;
        }
        if(mData == null)
            mData = new ArrayList<>();
        int oldSize = mData.size();
        mData.clear();
        if(oldSize > 0)
            notifyItemRangeRemoved(0, oldSize);
        mData.addAll(replacement);
        if(mData.size() > 0)
            notifyItemRangeInserted(0, mData.size());

    }
}
