package ml.melun.mangaview.adapter;

import android.content.Context;

import ml.melun.mangaview.activity.InfiniteScrollCallback;
import ml.melun.mangaview.mangaview.Title;

final class StripAdapterRuntime {
    StripViewHolderFactory viewHolderFactory;
    Context mainContext;
    StripAdapter.ItemClickListener clickListener;
    boolean autoCut;
    boolean reverse;
    int width;
    InfiniteScrollCallback callback;
    Title title;

    StripDecodedPreloadDelegate decodedPreloadDelegate;
    StripReusablePageStateTrimmer reusablePageStateTrimmer;
    StripRebindScheduler rebindScheduler;
    StripImageBinder imageBinder;
    StripItemListMutator itemListMutator;
    StripPreloadScheduler preloadScheduler;
    StripPreloadRequester preloadRequester;
    StripScrollPreloadController scrollPreloadController;
    StripAttachmentController attachmentController;
    StripImageRenderController imageRenderController;
    StripPreloadWindowRunner preloadWindowRunner;
    StripReleaseController releaseController;
    StripBindController bindController;
    StripAdapterConfig config;
}
