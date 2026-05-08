package ml.melun.mangaview.compose

import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.RequestOptions
import ml.melun.mangaview.Utils

@Composable
fun GlidePageImage(
    url: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            ImageView(context).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                this.contentDescription = contentDescription
            }
        },
        update = { imageView ->
            imageView.contentDescription = contentDescription
            Glide.with(imageView)
                .load(Utils.getGlideUrl(url))
                .apply(
                    RequestOptions()
                        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                        .downsample(DownsampleStrategy.AT_MOST)
                )
                .into(imageView)
        },
        onRelease = { imageView ->
            Glide.with(imageView).clear(imageView)
        },
    )
}
