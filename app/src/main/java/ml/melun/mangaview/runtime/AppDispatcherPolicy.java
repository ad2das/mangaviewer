package ml.melun.mangaview.runtime;

final class AppDispatcherPolicy {
    static final int IMAGE_WARMUP_CORE_THREADS = 2;
    static final int IMAGE_WARMUP_MAX_THREADS = 4;
    static final int IMAGE_WARMUP_QUEUE_SIZE = 128;

    private AppDispatcherPolicy() {
    }
}
