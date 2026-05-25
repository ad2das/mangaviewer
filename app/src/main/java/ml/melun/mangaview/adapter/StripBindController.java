package ml.melun.mangaview.adapter;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import ml.melun.mangaview.model.PageItem;

final class StripBindController {
    private final StripAdapterState state;
    private final StripAdapterRuntime runtime;
    private final StripAdapterComponent.Host host;

    StripBindController(StripAdapterState state, StripAdapterRuntime runtime, StripAdapterComponent.Host host) {
        this.state = state;
        this.runtime = runtime;
        this.host = host;
    }

    void bind(@NonNull RecyclerView.ViewHolder holder, int position) {
        List<Object> items = state.items;
        if(items == null || position < 0 || position >= items.size())
            return;
        int type = host.viewType(position);
        if(type == StripAdapter.IMG) {
            host.glideBind((StripImageViewHolder)holder, position);
        } else if(type == StripAdapter.INFO) {
            StripInfoRowBinder.bind((StripInfoViewHolder) holder, (InfoItem) items.get(position));
        }
    }

    boolean bindPayload(@NonNull RecyclerView.ViewHolder holder, int position,
                        @NonNull List<Object> payloads) {
        List<Object> items = state.items;
        if(payloads != null && payloads.contains(StripAdapter.PAYLOAD_HEIGHT)
                && holder instanceof StripImageViewHolder && items != null
                && position >= 0 && position < items.size()
                && items.get(position) instanceof PageItem) {
            StripImageViewHolder imageHolder = (StripImageViewHolder) holder;
            PageItem page = (PageItem) items.get(position);
            String pageKey = runtime.imageRenderController.pageBindKey(page);
            if(pageKey.equals(imageHolder.boundPageKey)) {
                runtime.imageRenderController.applyKnownHeight(imageHolder, page, pageKey);
                return true;
            }
        }
        return false;
    }
}
