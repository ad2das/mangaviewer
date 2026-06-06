package ml.melun.mangaview.adapter;
import android.content.Context;

import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import android.widget.ImageView;

import android.widget.TextView;


import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;


import java.util.List;

import ml.melun.mangaview.ui.NpaLinearLayoutManager;
import ml.melun.mangaview.R;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;

import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.getGlideUrl;
import static ml.melun.mangaview.Utils.isLocalMediaPath;
import static ml.melun.mangaview.Utils.safeGlideClear;


public class EpisodeAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private final List<Manga> mData;
    private final LayoutInflater mInflater;
    private ItemClickListener mClickListener;
    private final Context mainContext;
    boolean favorite = false;
    boolean bookmarked = false;
    TypedValue outValue;
    private int bookmark = -1;
    private static final Object PAYLOAD_SELECTION = "selection";
    private static final long HEADER_THUMBNAIL_DELAY_MS = 0L;
    private static final long TAG_BIND_DELAY_MS = 220L;
    //title is in index 0
    Title title;
    TagAdapter ta;
    NpaLinearLayoutManager lm;
    boolean dark;
    boolean save;
    int mode = 0;
    // data is passed into the constructor
    public EpisodeAdapter(Context context, List<Manga> data, Title title, int mode) {
        this.mInflater = LayoutInflater.from(context);
        mainContext = context;
        this.mData = data;
        this.title = title;
        this.mode = mode;
        outValue = new TypedValue();
        dark = p.getDarkTheme();
        save = p.getDataSave();
        bookmarked = title.getBookmarked();
        mainContext.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        if(title.getTags()!=null) {
            ta = new TagAdapter(context, title.getTags());
            lm = new NpaLinearLayoutManager(context);
            lm.setOrientation(LinearLayoutManager.HORIZONTAL);
        }
        setHasStableIds(true);
        if(mode != 0) save = false;
    }

    @Override
    public long getItemId(int position) {
        if(position == 0)
            return Long.MIN_VALUE;
        if(mData == null || position < 1 || position > mData.size())
            return RecyclerView.NO_ID;
        Manga manga = mData.get(position - 1);
        if(manga == null)
            return RecyclerView.NO_ID;
        if(manga.getId() >= 0)
            return fastEpisodeStableId(manga);
        String key = manga.getOfflinePath();
        if(key == null || key.length() == 0)
            key = manga.getName();
        return (((long) manga.getBaseMode()) << 32) ^ (key == null ? position : key.hashCode());
    }

    private long fastEpisodeStableId(Manga manga) {
        long titleId = manga.getTitleId() > 0 ? manga.getTitleId() : (title == null ? 0 : title.getId());
        long stable = (((long) manga.getBaseMode() & 0xffffL) << 48)
                ^ ((titleId & 0xffffL) << 32)
                ^ (manga.getId() & 0xffffffffL);
        return stable == RecyclerView.NO_ID ? Long.MIN_VALUE : stable;
    }

    @Override
    public int getItemViewType(int position) {
        if(position==0) return 0;
        else return 1;
    }

    // inflates the row layout from xml when needed
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = null;
        if(viewType==0) {
            view = mInflater.inflate(R.layout.item_header, parent, false);
            return new HeaderHolder(view);
        }else {
            view = mInflater.inflate(R.layout.item_episode, parent, false);
            return new ViewHolder(view);
        }
    }

    // binds the data to the TextView in each row
    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        onBindViewHolder(holder, position, java.util.Collections.emptyList());
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position, List<Object> payloads) {
        if(payloads != null && payloads.contains(PAYLOAD_SELECTION) && holder instanceof ViewHolder) {
            bindSelection((ViewHolder) holder, position);
            return;
        }
        if(position==0){
            HeaderHolder h = (HeaderHolder) holder;
            String titles = this.title.getName();
            String thumb = this.title.getThumb();
            if(thumb == null)
                thumb = "";
            String release = displayRelease();
            h.h_title.setText(titles);
            h.h_author.setText(this.title.getAuthor());
            if(release != null && release.length()>0) h.h_release.setText(release);
            else h.h_release.setText("");
            h.h_overview.setText(overviewText(release));
            h.h_info.setText(infoText());
            h.selectTab(HeaderHolder.TAB_INTRO);
            bindHeaderPrimaryAction(h);
            if(favorite) h.h_star_icon.setImageResource(R.drawable.ic_favorite);
            else h.h_star_icon.setImageResource(R.drawable.ic_favorite_border);
            h.h_bookmark.setVisibility(View.GONE);
            if(!save && thumb.length() > 0) {
                Object source = isLocalMediaPath(thumb) ? thumb : getGlideUrl(thumb, title.getBaseMode());
                bindThumbnailDeferred(h.h_thumb, source, dp(144), dp(192), false, HEADER_THUMBNAIL_DELAY_MS);
            }
            else bindEmptyThumbnail(h.h_thumb, false);
            if(mode == 0 || mode == 3)
                h.h_star.setVisibility(View.VISIBLE);
            else
                h.h_star.setVisibility(View.GONE);

            if(mode == 0){
                //set ext-info text
                h.h_recommend_c.setText(String.valueOf(title.getRecommend_c()));

            }else{
                //offline manga
                h.h_bookmark.setVisibility(View.GONE);
                h.h_recommend.setVisibility(View.GONE);
                h.h_recommend_c.setVisibility(View.GONE);
            }

        }else {
            ViewHolder h = (ViewHolder) holder;
            if(isConfirmedEmptyEpisodePlaceholder(position)) {
                bindConfirmedEmptyEpisodeRow(h, position);
                return;
            }
            if(!isValidEpisodePosition(mData, position)) {
                clearEpisodeRow(h, position);
                return;
            }
            bindNormalEpisodeRowStyle(h);
            int Dposition = position-1;
            Manga episode = mData.get(Dposition);
            if(episode == null) {
                clearEpisodeRow(h, position);
                return;
            }
            String rowKey = episode.getId() + ":" + episode.getName() + ":" + episode.getDate() + ":" + mode + ":" + Dposition;
            if(!rowKey.equals(h.boundKey)) {
                setTextIfChanged(h.episode, episode.getName());
                setTextIfChanged(h.date, episode.getDate());
                setVisibilityIfChanged(h.newBadge, Dposition == 0 ? View.VISIBLE : View.GONE);
                setVisibilityIfChanged(h.action, mode == 0 || mode == 1 || mode == 3 || mode == 4 ? View.VISIBLE : View.GONE);
                h.action.setImageResource(mode == 0 ? R.drawable.download : R.drawable.ic_baseline_close_24);
                h.action.setColorFilter(ContextCompat.getColor(mainContext,
                        mode == 0 ? R.color.appAccent : (dark ? R.color.colorDarkTextSecondary : R.color.appTextSecondary)));
                h.boundKey = rowKey;
            }
            bindEmptyThumbnail(h.thumb, true);
            bindSelection(h, position);
        }
    }

    private void bindThumbnailDeferred(ImageView view, Object source, int width, int height, boolean placeholderWhenEmpty, long delayMs) {
        bindThumbnailDeferred(view, source, width, height, placeholderWhenEmpty, delayMs, true);
    }

    private void bindThumbnailDeferred(ImageView view, Object source, int width, int height, boolean placeholderWhenEmpty,
                                       long delayMs, boolean clearImmediately) {
        String key = String.valueOf(source);
        if(ThumbnailBindPolicy.shouldSkipDeferredBind(view.getTag(), key))
            return;
        if(delayMs <= 0L) {
            bindThumbnail(view, source, width, height, placeholderWhenEmpty);
            return;
        }
        String pendingKey = ThumbnailBindPolicy.pendingKey(key);
        if(ThumbnailBindPolicy.shouldClearBeforeDeferredBind(view.getTag(), clearImmediately))
            safeGlideClear(view);
        view.setTag(pendingKey);
        view.setImageResource(R.drawable.app_cover_placeholder);
        view.postDelayed(() -> {
            if(pendingKey.equals(view.getTag()))
                bindThumbnail(view, source, width, height, placeholderWhenEmpty);
        }, Math.max(0L, delayMs));
    }

    private void bindThumbnail(ImageView view, Object source, int width, int height, boolean placeholderWhenEmpty) {
        String key = String.valueOf(source);
        if(key.equals(view.getTag()))
            return;
        view.setTag(key);
        try {
            Glide.with(view)
                    .load(source)
                    .apply(new RequestOptions().dontTransform())
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .override(width, height)
                    .thumbnail(0.25f)
                    .dontAnimate()
                    .placeholder(R.drawable.app_cover_placeholder)
                    .into(view);
        } catch (RuntimeException e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            view.setImageResource(R.drawable.app_cover_placeholder);
        }
    }

    private void bindEmptyThumbnail(ImageView view, boolean placeholder) {
        String key = placeholder ? ThumbnailBindPolicy.TAG_PLACEHOLDER : ThumbnailBindPolicy.TAG_EMPTY;
        if(key.equals(view.getTag()))
            return;
        safeGlideClear(view);
        view.setTag(key);
        if(placeholder)
            view.setImageResource(R.drawable.app_cover_placeholder);
        else
            view.setImageBitmap(null);
    }

    static boolean shouldClearThumbnailBeforeDeferredBindForTest(Object currentTag, boolean clearImmediately) {
        return ThumbnailBindPolicy.shouldClearBeforeDeferredBind(currentTag, clearImmediately);
    }

    private void bindSelection(ViewHolder holder, int position) {
        int color = position == bookmark
                ? ContextCompat.getColor(mainContext, dark ? R.color.selectedDark : R.color.appAccentLight)
                : ContextCompat.getColor(mainContext, dark ? R.color.colorDarkSurface : R.color.appCard);
        Object tag = holder.itemView.getTag(R.id.episode);
        if(!(tag instanceof Integer) || ((Integer) tag) != color) {
            if(holder.card != null)
                holder.card.setCardBackgroundColor(color);
            else
                holder.itemView.setBackgroundColor(color);
            holder.itemView.setTag(R.id.episode, color);
        }
    }

    private void clearEpisodeRow(ViewHolder holder, int position) {
        setTextIfChanged(holder.episode, "");
        setTextIfChanged(holder.date, "");
        setVisibilityIfChanged(holder.newBadge, View.GONE);
        setVisibilityIfChanged(holder.action, View.GONE);
        holder.boundKey = "null:" + position;
        bindEmptyThumbnail(holder.thumb, true);
        holder.itemView.setEnabled(false);
        bindSelection(holder, position);
    }

    private void bindNormalEpisodeRowStyle(ViewHolder holder) {
        holder.itemView.setEnabled(true);
        if(holder.cardContent != null)
            holder.cardContent.setBackgroundColor(ContextCompat.getColor(mainContext,
                    dark ? R.color.colorDarkSurface : R.color.appCard));
    }

    private void bindConfirmedEmptyEpisodeRow(ViewHolder holder, int position) {
        setTextIfChanged(holder.episode, "\uD45C\uC2DC\uD560 \uD68C\uCC28\uAC00 \uC5C6\uC2B5\uB2C8\uB2E4");
        setTextIfChanged(holder.date, "\uC6D0\uBCF8 \uC0AC\uC774\uD2B8\uC5D0\uC11C \uD68C\uCC28\uAC00 \uC218\uC9D1\uB418\uBA74 \uC790\uB3D9\uC73C\uB85C \uD45C\uC2DC\uB429\uB2C8\uB2E4.");
        setVisibilityIfChanged(holder.newBadge, View.GONE);
        setVisibilityIfChanged(holder.action, View.GONE);
        holder.boundKey = "ntk-empty:" + position;
        bindEmptyThumbnail(holder.thumb, true);
        if(holder.card != null)
            holder.card.setCardBackgroundColor(ContextCompat.getColor(mainContext,
                    dark ? R.color.colorDarkSurfaceElevated : R.color.appMutedSurface));
        if(holder.cardContent != null)
            holder.cardContent.setBackgroundColor(ContextCompat.getColor(mainContext,
                    dark ? R.color.colorDarkSurfaceElevated : R.color.appMutedSurface));
        holder.itemView.setEnabled(false);
    }

    private void bindHeaderPrimaryAction(HeaderHolder holder) {
        if(holder == null || holder.h_first == null)
            return;
        boolean empty = shouldShowConfirmedEmptyEpisodeRow();
        holder.h_first.setEnabled(!empty);
        holder.h_first.setAlpha(empty ? 0.55f : 1f);
        holder.h_first.setText(empty
                ? "\uD68C\uCC28 \uC900\uBE44 \uC911"
                : "\uBC14\uB85C \uC77D\uAE30");
    }

    private void setTextIfChanged(TextView view, CharSequence text) {
        CharSequence next = text == null ? "" : text;
        if(!android.text.TextUtils.equals(view.getText(), next))
            view.setText(next);
    }

    private void setVisibilityIfChanged(View view, int visibility) {
        if(view.getVisibility() != visibility)
            view.setVisibility(visibility);
    }

    int dp(int value) {
        return (int) (value * mainContext.getResources().getDisplayMetrics().density + 0.5f);
    }

    // total number of rows
    @Override
    public int getItemCount() {
        return (mData == null ? 0 : mData.size()) + 1 + (shouldShowConfirmedEmptyEpisodeRow() ? 1 : 0);
    }

    private boolean shouldShowConfirmedEmptyEpisodeRow() {
        return title != null
                && title.isNtkEpisodeListConfirmedEmpty()
                && (mData == null || mData.size() == 0);
    }

    private boolean isConfirmedEmptyEpisodePlaceholder(int position) {
        return shouldShowConfirmedEmptyEpisodeRow() && position == 1;
    }

    public class ViewHolder extends RecyclerView.ViewHolder{
        TextView episode,date;
        TextView newBadge;
        ImageView thumb;
        ImageView action;
        CardView card;
        View cardContent;
        String boundKey;
        ViewHolder(View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.episodeCard);
            cardContent = itemView.findViewById(R.id.episodeCardContent);
            episode = itemView.findViewById(R.id.episode);
            date = itemView.findViewById(R.id.date);
            newBadge = itemView.findViewById(R.id.episodeNew);
            thumb = itemView.findViewById(R.id.episodeThumb);
            action = itemView.findViewById(R.id.episodeAction);
            if(dark) {
                itemView.setBackgroundColor(ContextCompat.getColor(mainContext, R.color.colorDarkWindowBackground));
                if(cardContent != null)
                    cardContent.setBackgroundColor(ContextCompat.getColor(mainContext, R.color.colorDarkSurface));
                episode.setTextColor(ContextCompat.getColor(mainContext, R.color.colorDarkText));
                date.setTextColor(ContextCompat.getColor(mainContext, R.color.colorDarkTextSecondary));
                action.setBackground(roundedBackground(R.color.colorDarkSurfaceElevated, R.color.colorDarkDivider, 12));
            }
            itemView.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if(position == RecyclerView.NO_POSITION || mClickListener == null || !isValidEpisodePosition(mData, position))
                    return;
                Manga m = mData.get(position - 1);
                if(m == null)
                    return;
                if(m.getId()>-1) {
                    if (bookmark != -1) {
                        int pre = bookmark;
                        notifyItemChanged(pre, PAYLOAD_SELECTION);
                    }
                    bookmark = position;
                    notifyItemChanged(bookmark, PAYLOAD_SELECTION);
                }
                mClickListener.onItemClick(position - 1, m);
            });
            itemView.setOnTouchListener((v, event) -> {
                if(event != null && event.getActionMasked() == MotionEvent.ACTION_DOWN
                        && mClickListener != null) {
                    int position = getAdapterPosition();
                    if(position != RecyclerView.NO_POSITION && isValidEpisodePosition(mData, position)) {
                        Manga m = mData.get(position - 1);
                        if(m != null)
                            mClickListener.onItemPress(position - 1, m);
                    }
                }
                return false;
            });
            action.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if(position == RecyclerView.NO_POSITION || mClickListener == null || !isValidEpisodePosition(mData, position))
                    return;
                Manga m = mData.get(position - 1);
                if(m != null)
                    mClickListener.onDownloadClick(position - 1, m);
            });
        }
    }
    public class HeaderHolder extends RecyclerView.ViewHolder{
        TextView h_title, h_author, h_release, h_overview;
        TextView h_intro_tab, h_episode_tab, h_info_tab, h_info;
        TextView h_top_episodes;
        ImageView h_thumb;
        ImageView h_star_icon;
        ImageView h_bookmark_icon;

        Button h_first;
        RecyclerView h_tags;
        View h_bookmark, h_star, h_recommend, h_indicator, h_tabs, h_button_container, h_detail_content;

        TextView h_recommend_c;
            HeaderHolder(View itemView) {
            super(itemView);
            h_title = itemView.findViewById(R.id.HeaderTitle);
            h_thumb = itemView.findViewById(R.id.HeaderThumb);
            h_star_icon = itemView.findViewById(R.id.favoriteIcon);
            h_first = itemView.findViewById(R.id.HeaderFirst);
            h_tags = itemView.findViewById(R.id.tagsContainer);
            h_author = itemView.findViewById(R.id.headerAuthor);
            h_release = itemView.findViewById(R.id.HeaderRelease);
            h_overview = itemView.findViewById(R.id.detailOverview);
            h_info = itemView.findViewById(R.id.detailInfo);
            h_intro_tab = itemView.findViewById(R.id.detailIntroTab);
            h_episode_tab = itemView.findViewById(R.id.detailEpisodeTab);
            h_info_tab = itemView.findViewById(R.id.detailInfoTab);
            h_indicator = itemView.findViewById(R.id.detailTabIndicator);
            h_tabs = itemView.findViewById(R.id.detailTabs);
            h_button_container = itemView.findViewById(R.id.HeaderBtnContainer);
            h_detail_content = itemView.findViewById(R.id.detailContent);
            h_top_episodes = itemView.findViewById(R.id.topEpisodesLabel);
            h_bookmark_icon = itemView.findViewById(R.id.bookmarkIcon);

            h_star = itemView.findViewById(R.id.HeaderFavorite);
            h_bookmark = itemView.findViewById(R.id.HeaderBookmark);
            h_recommend = itemView.findViewById(R.id.recommendIcon);

            h_recommend_c = itemView.findViewById(R.id.recommendText);

            if(dark)
                applyDarkHeaderStyle(itemView);

            h_star.setOnClickListener(v -> {
                if(mClickListener != null) mClickListener.onStarClick();
            });
            h_first.setOnClickListener(v -> {
                if(mClickListener != null) mClickListener.onFirstClick();
            });
            h_author.setOnClickListener(v -> {
                if(mClickListener != null) mClickListener.onAuthorClick();
            });
            h_intro_tab.setOnClickListener(v -> selectTab(TAB_INTRO));
            h_episode_tab.setOnClickListener(v -> {
                selectTab(TAB_EPISODES);
                if(mClickListener != null) mClickListener.onEpisodeTabClick();
            });
            h_info_tab.setOnClickListener(v -> selectTab(TAB_INFO));
            if(ta!=null) {
                h_tags.setLayoutManager(lm);
                h_tags.setItemAnimator(null);
                h_tags.setNestedScrollingEnabled(false);
                h_tags.postDelayed(() -> {
                    if(getAdapterPosition() == 0 && h_tags.getAdapter() == null)
                        h_tags.setAdapter(ta);
                }, TAG_BIND_DELAY_MS);
            }
        }

        static final int TAB_INTRO = 0;
        static final int TAB_EPISODES = 1;
        static final int TAB_INFO = 2;

        void selectTab(int selected) {
            styleTab(h_intro_tab, selected == TAB_INTRO);
            styleTab(h_episode_tab, selected == TAB_EPISODES);
            styleTab(h_info_tab, selected == TAB_INFO);
            h_overview.setVisibility(selected == TAB_INTRO ? View.VISIBLE : View.GONE);
            h_info.setVisibility(selected == TAB_INFO ? View.VISIBLE : View.GONE);
            h_tabs.post(() -> {
                int tabWidth = h_tabs.getWidth() / 3;
                ViewGroup.LayoutParams params = h_indicator.getLayoutParams();
                params.width = tabWidth;
                h_indicator.setLayoutParams(params);
                h_indicator.setTranslationX(tabWidth * selected);
            });
        }

        void styleTab(TextView tab, boolean selected) {
            int selectedColor = dark ? R.color.colorDarkText : R.color.appText;
            int normalColor = dark ? R.color.colorDarkTextSecondary : R.color.appTextSecondary;
            tab.setTextColor(ContextCompat.getColor(mainContext, selected ? selectedColor : normalColor));
            tab.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private void applyDarkHeaderStyle(View itemView) {
        itemView.setBackgroundColor(ContextCompat.getColor(mainContext, R.color.colorDarkWindowBackground));
        TextView[] primary = { itemView.findViewById(R.id.HeaderTitle) };
        for(TextView view : primary)
            if(view != null)
                view.setTextColor(ContextCompat.getColor(mainContext, R.color.colorDarkText));
        TextView[] secondary = {
                itemView.findViewById(R.id.headerAuthor),
                itemView.findViewById(R.id.HeaderRelease),
                itemView.findViewById(R.id.detailOverview),
                itemView.findViewById(R.id.detailInfo),
                itemView.findViewById(R.id.recommendText)
        };
        for(TextView view : secondary)
            if(view != null)
                view.setTextColor(ContextCompat.getColor(mainContext, R.color.colorDarkTextSecondary));
        View[] panels = {
                itemView.findViewById(R.id.HeaderBtnContainer),
                itemView.findViewById(R.id.detailTabs),
                itemView.findViewById(R.id.detailContent),
                itemView.findViewById(R.id.HeaderFavorite),
                itemView.findViewById(R.id.HeaderBookmark)
        };
        for(View view : panels)
            if(view != null)
                view.setBackground(roundedBackground(R.color.colorDarkSurface, R.color.colorDarkDivider, 8));
        TextView label = itemView.findViewById(R.id.topEpisodesLabel);
        if(label != null) {
            label.setTextColor(ContextCompat.getColor(mainContext, R.color.appAccent));
            label.setBackground(roundedBackground(R.color.colorDarkSurfaceElevated, R.color.colorDarkDivider, 8));
        }
        ImageView bookmarkIcon = itemView.findViewById(R.id.bookmarkIcon);
        if(bookmarkIcon != null)
            bookmarkIcon.setColorFilter(ContextCompat.getColor(mainContext, R.color.colorDarkTextSecondary));
    }

    private GradientDrawable roundedBackground(int fillColorRes, int strokeColorRes, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(ContextCompat.getColor(mainContext, fillColorRes));
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), ContextCompat.getColor(mainContext, strokeColorRes));
        return drawable;
    }

    private String overviewText(String release) {
        if(release != null && release.trim().length() > 0)
            return release.trim();
        return "등록된 소개가 없습니다.";
    }

    private String infoText() {
        StringBuilder builder = new StringBuilder();
        appendInfo(builder, "작품", title.getName());
        appendInfo(builder, "구분", title.getBaseModeStr());
        appendInfo(builder, "작가", title.getAuthor());
        if(title.getTags().size() > 0)
            appendInfo(builder, "분류", android.text.TextUtils.join(" / ", title.getTags()));
        appendInfo(builder, "회차", mData == null ? "0개" : mData.size() + "개");
        String release = displayRelease();
        if(release != null && release.trim().length() > 0)
            appendInfo(builder, "소개", release.trim());
        return builder.toString();
    }

    private String displayRelease() {
        String release = title == null ? "" : title.getRelease();
        return displayReleaseForNtk(title == null ? "" : title.getSourceSite(), release, mData);
    }

    static String displayReleaseForNtkForTest(String sourceSite, String release, List<Manga> episodes) {
        return displayReleaseForNtk(sourceSite, release, episodes);
    }

    private static String displayReleaseForNtk(String sourceSite, String release, List<Manga> episodes) {
        if(!"ntk".equals(sourceSite) || episodes == null || episodes.size() == 0)
            return release;
        String releaseNumber = Manga.visibleEpisodeNumberKey(release);
        if(releaseNumber.length() == 0)
            return release;
        Manga latest = episodes.get(0);
        String latestNumber = latest == null ? "" : Manga.visibleEpisodeNumberKey(latest.getName());
        if(latestNumber.length() == 0 || latestNumber.equals(releaseNumber))
            return release;
        return latestNumber + "화";
    }

    private void appendInfo(StringBuilder builder, String label, String value) {
        if(value == null || value.trim().length() == 0)
            return;
        if(builder.length() > 0)
            builder.append('\n');
        builder.append(label).append(": ").append(value.trim());
    }

    public void setFavorite(boolean b){
        if(favorite!=b) {
            favorite = b;
            notifyItemChanged(0);
        }
    }

    public void setBookmark(int i){
        //THIS SHOULD BE SET TO INDEX, NOT ID! : because of notifyitemChanged
        //i is real index in recyclerview
        if(i!=bookmark){
            int tmp = bookmark;
            bookmark = i;
            if(tmp>0) notifyItemChanged(tmp, PAYLOAD_SELECTION);
            if(bookmark>0) notifyItemChanged(bookmark, PAYLOAD_SELECTION);
        }
    }

    public void removeEpisode(int position) {
        if(mData == null || position < 0 || position >= mData.size())
            return;
        mData.remove(position);
        notifyItemRemoved(position + 1);
        notifyItemChanged(0);
    }

    // allows clicks events to be caught
    public void setClickListener(ItemClickListener itemClickListener) {
        this.mClickListener = itemClickListener;
    }

    private static boolean isValidEpisodePosition(List<?> data, int adapterPosition) {
        return data != null && adapterPosition > 0 && adapterPosition <= data.size();
    }

    static boolean isValidEpisodePositionForTest(List<?> data, int adapterPosition) {
        return isValidEpisodePosition(data, adapterPosition);
    }

    public void setTagClickListener(TagAdapter.tagOnclick t){
        if(ta != null)
            ta.setClickListener(t);
    }

    // parent activity will implement this method to respond to click events
    public interface ItemClickListener {
        void onItemClick(int position, Manga m);
        void onItemPress(int position, Manga m);
        void onStarClick();
        void onFirstClick();
        void onAuthorClick();
        void onEpisodeTabClick();
        void onDownloadClick(int position, Manga m);
    }
}
