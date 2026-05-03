package ml.melun.mangaview.adapter;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.util.ArrayList;

import ml.melun.mangaview.R;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.mangaview.UpdatedManga;

import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.getGlideUrl;

public class UpdatedAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    Context context;
    ArrayList<UpdatedManga> mData;
    onclickListener olisten;
    boolean save;
    boolean dark;
    private final LayoutInflater mInflater;

    public UpdatedAdapter(Context main) {
        super();
        context = main;
        mData = new ArrayList<>();
        save = p.getDataSave();
        dark = p.getDarkTheme();
        this.mInflater = LayoutInflater.from(main);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    public void addData(ArrayList<UpdatedManga> data){
        if(data == null || data.size() == 0)
            return;
        int oSize = mData.size();
        mData.addAll(data);
        notifyItemRangeInserted(oSize,data.size());
    }

    public void setOnClickListener(onclickListener click){
        olisten = click;
    }
    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = mInflater.inflate(R.layout.item_updated_list, parent, false);
        return new viewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        viewHolder h = (viewHolder) holder;
        if(mData == null || position < 0 || position >= mData.size())
            return;
        UpdatedManga m = mData.get(position);
        h.text.setText(m.getName());
        h.date.setText(m.getDate());
        String thumb = m.getThumb();
        if(thumb == null)
            thumb = "";
        Glide.with(h.thumb).clear(h.thumb);
        if(thumb.length()>1 && !save) Glide.with(h.thumb)
                .load(getGlideUrl(thumb, m.getBaseMode()))
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .override(dp(110), dp(150))
                .dontAnimate()
                .into(h.thumb);
        else h.thumb.setImageBitmap(null);
        if(save) h.thumb.setVisibility(View.GONE);
        if(p.getBookmark(m.getTitle())>0)
            h.seen.setVisibility(View.VISIBLE);
        else
            h.seen.setVisibility(View.GONE);
        if(p.findFavorite(m.getTitle())>-1)
            h.fav.setVisibility(View.VISIBLE);
        else
            h.fav.setVisibility(View.GONE);

        StringBuilder tags = new StringBuilder();
        if(m.getTag() != null) {
            for (String s :m.getTag()) {
                tags.append(s).append(" ");
            }
        }
        h.tags.setText(tags.toString());
        h.author.setText(m.getAuthor());
    }

    private int dp(int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public int getItemCount() {
        return mData == null ? 0 : mData.size();
    }

    class viewHolder extends RecyclerView.ViewHolder{
        TextView text, date;
        ImageView thumb;
        CardView card;
        ImageView seen, fav;
        ImageButton viewEps;
        TextView author;
        TextView tags;
        View tagContainer;

        public viewHolder(View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.Title);
            date = itemView.findViewById(R.id.date);
            card = itemView.findViewById(R.id.updatedCard);
            thumb = itemView.findViewById(R.id.Thumb);
            viewEps = itemView.findViewById(R.id.epsButton);
            seen = itemView.findViewById(R.id.seenIcon);
            fav = itemView.findViewById(R.id.favIcon);
            author =itemView.findViewById(R.id.TitleAuthor);
            tags = itemView.findViewById(R.id.TitleTag);
            tagContainer = itemView.findViewById(R.id.TitleTagContainer);
            if(dark){
                card.setBackgroundColor(ContextCompat.getColor(context, R.color.colorDarkBackground));
                viewEps.setBackgroundColor(ContextCompat.getColor(context, R.color.resumeDark));
            }
            card.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if(position == RecyclerView.NO_POSITION || olisten == null || mData == null || position >= mData.size())
                    return;
                olisten.onClick(mData.get(position));
            });
            viewEps.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if(position == RecyclerView.NO_POSITION || olisten == null || mData == null || position >= mData.size())
                    return;
                Title title = mData.get(position).getTitle();
                if(title != null)
                    olisten.onEpsClick(title);
            });
        }
    }

    public interface onclickListener {
        void onClick(Manga m);
        void onEpsClick(Title t);
    }
}
