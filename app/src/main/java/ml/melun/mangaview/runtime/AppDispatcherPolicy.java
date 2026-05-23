package ml.melun.mangaview.runtime;

final class AppDispatcherPolicy {
    static final int IMAGE_WARMUP_CORE_THREADS = 1;
    static final int IMAGE_WARMUP_MAX_THREADS = 2;
    static final int IMAGE_WARMUP_QUEUE_SIZE = 64;

    private AppDispatcherPolicy() {
    }
}
