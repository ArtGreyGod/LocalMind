package com.example.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import com.example.domain.engine.PreparedImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Offloads image decoding, HARDWARE->ARGB_8888 conversion, aspect-preserving resize,
 * and JPEG / packed-RGB buffer preparation onto background threads for LiteRT-LM and llama.cpp mtmd.
 */
class ImagePreprocessor(private val context: Context) {

    suspend fun prepareImage(
        uri: Uri,
        maxDimension: Int = 768
    ): Result<PreparedImage> = withContext(Dispatchers.Default) {
        runCatching {
            val decoded = decodeBitmap(uri)
            val softwareBitmap = if (decoded.config == Bitmap.Config.HARDWARE ||
                decoded.config != Bitmap.Config.ARGB_8888
            ) {
                decoded.copy(Bitmap.Config.ARGB_8888, false).also {
                    if (it !== decoded) decoded.recycle()
                }
            } else {
                decoded
            }

            val scaled = scalePreservingAspectRatio(softwareBitmap, maxDimension)
            if (scaled !== softwareBitmap) {
                softwareBitmap.recycle()
            }

            val width = scaled.width
            val height = scaled.height

            val jpegStream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 90, jpegStream)
            val jpegBytes = jpegStream.toByteArray()

            val rgbBytes = extractPackedRgbBytes(scaled, width, height)
            scaled.recycle()

            PreparedImage(
                uriString = uri.toString(),
                width = width,
                height = height,
                jpegBytes = jpegBytes,
                rgbBytes = rgbBytes
            )
        }
    }

    /**
     * Converts a Bitmap into normalized NHWC RGB float array for raw LiteRT CompiledModel inputs.
     */
    fun toNormalizedFloatArray(
        bitmap: Bitmap,
        targetSize: Int = 224,
        mean: Float = 127.5f,
        std: Float = 127.5f
    ): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)
            .copy(Bitmap.Config.ARGB_8888, false)
        val pixels = IntArray(targetSize * targetSize)
        scaled.getPixels(pixels, 0, targetSize, 0, 0, targetSize, targetSize)
        scaled.recycle()

        val out = FloatArray(targetSize * targetSize * 3)
        for (i in pixels.indices) {
            val p = pixels[i]
            out[i * 3] = ((p shr 16 and 0xFF) - mean) / std
            out[i * 3 + 1] = ((p shr 8 and 0xFF) - mean) / std
            out[i * 3 + 2] = ((p and 0xFF) - mean) / std
        }
        return out
    }

    private fun decodeBitmap(uri: Uri): Bitmap {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: error("Unable to open image stream for $uri")
        }
    }

    private fun scalePreservingAspectRatio(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val longest = max(w, h)
        if (longest <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / longest.toFloat()
        val newW = max(1, (w * scale).roundToInt())
        val newH = max(1, (h * scale).roundToInt())
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }

    private fun extractPackedRgbBytes(bitmap: Bitmap, width: Int, height: Int): ByteArray {
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val rgb = ByteArray(width * height * 3)
        for (i in pixels.indices) {
            val p = pixels[i]
            val offset = i * 3
            rgb[offset] = ((p shr 16) and 0xFF).toByte()
            rgb[offset + 1] = ((p shr 8) and 0xFF).toByte()
            rgb[offset + 2] = (p and 0xFF).toByte()
        }
        return rgb
    }
}
