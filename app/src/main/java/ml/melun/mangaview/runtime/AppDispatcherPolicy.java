package ml.melun.mangaview.runtime;

final class AppDispatcherPolicy {
    static final int IMAGE_WARMUP_CORE_THREADS = 3;
    static final int IMAGE_WARMUP_MAX_THREADS = 6;
    static final int IMAGE_WARMUP_QUEUE_SIZE = 160;

    private AppDispatcherPolicy() {
    }
}
