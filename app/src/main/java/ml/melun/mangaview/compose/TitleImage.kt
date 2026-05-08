package ml.melun.mangaview.compose

import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import ml.melun.mangaview.Utils

@Composable
fun TitleImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                this.contentDescription = contentDescription
            }
        },
        update = { imageView ->
            imageView.contentDescription = contentDescription
            val target = url.orEmpty()
            if (target.isBlank()) {
                Glide.with(imageView).clear(imageView)
            } else {
                Glide.with(imageView)
                    .load(Utils.getGlideUrl(target))
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .into(imageView)
            }
        },
        onRelease = { imageView -> Glide.with(imageView).clear(imageView) },
    )
}
