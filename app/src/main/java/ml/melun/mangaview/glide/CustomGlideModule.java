package ml.melun.mangaview.glide;

import android.content.Context;
import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader;
import com.bumptech.glide.load.engine.executor.GlideExecutor;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.module.AppGlideModule;
import static ml.melun.mangaview.MainApplication.getHttpClient;

import java.io.InputStream;

@GlideModule
public class CustomGlideModule extends AppGlideModule {
    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        int cores = Math.max(2, Runtime.getRuntime().availableProcessors());
        int sourceThreads = Math.min(8, Math.max(4, cores));
        builder.setSourceExecutor(GlideExecutor.newSourceExecutor(
                sourceThreads,
                "manga-source",
                GlideExecutor.UncaughtThrowableStrategy.LOG));
    }

    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide, @NonNull Registry registry) {
        registry.replace(GlideUrl.class, InputStream.class, new OkHttpUrlLoader.Factory(getHttpClient().imageClient));
    }

    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }
}
