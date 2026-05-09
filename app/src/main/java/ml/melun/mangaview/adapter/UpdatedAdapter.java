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
import java.util.List;

import ml.melun.mangaview.R;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.mangaview.UpdatedManga;

import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.canUseContextForUi;
import static ml.melun.mangaview.Utils.getGlideUrl;
import static ml.melun.mangaview.Utils.safeGlideClear;

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
        setHasStableIds(true);
    }

    @Override
    public long getItemId(int position) {
        if(mData == null || position < 0 || position >= mData.size())
            return RecyclerView.NO_ID;
        UpdatedManga item = mData.get(position);
        if(item == null)
            return RecyclerView.NO_ID;
        Title title = item.getTitle();
        if(title != null && title.getId() > 0)
            return (((long) title.getBaseMode()) << 32) ^ (title.getId() & 0xffffffffL);
        return (((long) item.getBaseMode()) << 32) ^ (item.getName() == null ? position : item.getName().hashCode());
    }

    public void addData(ArrayList<UpdatedManga> data){
        int oSize = mData.size();
        mData.addAll(data);
        notifyItemRangeInserted(oSize,data.size());
    }

    public void preloadThumbnails(int startPosition, int count) {
        if(mData == null || count <= 0 || save || !canUseContextForUi(context))
            return;
        int start = Math.max(0, startPosition);
        int end = Math.min(mData.size(), start + count);
        for(int i = start; i < end; i++) {
            UpdatedManga manga = mData.get(i);
            if(manga == null)
                continue;
            String thumb = manga.getThumb();
            if(thumb == null || thumb.length() <= 1)
                continue;
            try {
                Glide.with(context)
                        .load(getGlideUrl(thumb, manga.getBaseMode()))
                        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                        .override(dp(72), dp(96))
                        .dontAnimate()
                        .preload();
            } catch (RuntimeException e) {
                ml.melun.mangaview.report.CrashReporter.record(e);
                return;
            }
        }
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
        UpdatedManga m = mData.get(position);
        h.text.setText(m.getName() == null ? "" : m.getName());
        h.date.setText(m.getDate() == null ? "" : m.getDate());
        String thumb = m.getThumb();
        if(thumb != null && thumb.length()>1 && !save) {
            Object source = getGlideUrl(thumb, m.getBaseMode());
            String key = String.valueOf(source);
            if(!key.equals(h.thumb.getTag())) {
                safeGlideClear(h.thumb);
                h.thumb.setTag(key);
                try {
                    Glide.with(h.thumb)
                            .load(source)
                            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                            .override(dp(72), dp(96))
                            .thumbnail(0.25f)
                            .dontAnimate()
                            .placeholder(R.drawable.app_cover_placeholder)
                            .into(h.thumb);
                } catch (RuntimeException e) {
                    ml.melun.mangaview.report.CrashReporter.record(e);
                    h.thumb.setImageResource(R.drawable.app_cover_placeholder);
                }
            }
        } else {
            if(!"empty".equals(h.thumb.getTag())) {
                safeGlideClear(h.thumb);
                h.thumb.setTag("empty");
                h.thumb.setImageBitmap(null);
            }
        }
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
        List<String> tagList = m.getTag();
        if(tagList != null) {
            for (String s : tagList) {
                tags.append(s).append(" ");
            }
        }
        h.tags.setText(tags.toString());
        h.author.setText(m.getAuthor() == null ? "" : m.getAuthor());
    }

    int dp(int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public int getItemCount() {
        return mData.size();
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
                if(position == RecyclerView.NO_POSITION || olisten == null)
                    return;
                olisten.onClick(mData.get(position));
            });
            viewEps.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if(position == RecyclerView.NO_POSITION || olisten == null)
                    return;
                olisten.onEpsClick(mData.get(position).getTitle());
            });
        }
    }

    public interface onclickListener {
        void onClick(Manga m);
        void onEpsClick(Title t);
    }
}
