package com.dearminder.yawts

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.util.LruCache
import android.util.Size
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.Semaphore

/**
 * Previews for the web page's file list: photos, and the cover art of songs (embedded, or an
 * album art image next to them). Kept small and cached, and only a couple are decoded at a time,
 * so a folder full of photos doesn't starve the phone or the transfers running alongside.
 */
class Thumbnails {
    // Failures are cached too (as NONE), so a song without a cover isn't opened again on every visit.
    private val cache = object : LruCache<String, Thumbnail>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Thumbnail) = maxOf(1, value.data.size)
    }
    private val permits = Semaphore(MAX_PARALLEL)

    fun get(file: File): Thumbnail? {
        val ext = file.extension.lowercase(Locale.ROOT)
        val isImage = ext in IMAGE_TYPES
        if (!isImage && ext !in AUDIO_TYPES) return null
        val key = "${file.path}:${file.lastModified()}:${file.length()}"
        cache.get(key)?.let { return it.takeIf { it !== NONE } }

        permits.acquire()
        val thumb = try {
            val bitmap = if (isImage) image(file) else audio(file)
            bitmap?.let { encode(shrink(it)) }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } finally {
            permits.release()
        }
        cache.put(key, thumb ?: NONE)
        return thumb
    }

    private fun image(file: File): Bitmap? {
        // Uses the small preview most cameras embed in the EXIF data, and handles HEIC and rotation.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return ThumbnailUtils.createImageThumbnail(file, Size(SIZE, SIZE), null)
        val bitmap = decodeSampled { opts -> BitmapFactory.decodeFile(file.path, opts) } ?: return null
        val degrees = try {
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) {
            0f
        }
        if (degrees == 0f) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
    }

    private fun audio(file: File): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Throws when the song has no cover - that's just "no thumbnail".
            return try { ThumbnailUtils.createAudioThumbnail(file, Size(SIZE, SIZE), null) } catch (e: Exception) { null }
        }
        val retriever = MediaMetadataRetriever()
        val art = try {
            retriever.setDataSource(file.path)
            retriever.embeddedPicture
        } finally {
            retriever.release()
        } ?: return null
        return decodeSampled { opts -> BitmapFactory.decodeByteArray(art, 0, art.size, opts) }
    }

    /** Decodes at the smallest power-of-two scale that still covers [SIZE] on the shorter side. */
    private fun decodeSampled(decode: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE) sample *= 2
        return decode(BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Scales down so the shorter side is [SIZE] - the page crops it to a square. */
    private fun shrink(bitmap: Bitmap): Bitmap {
        val short = minOf(bitmap.width, bitmap.height)
        if (short <= SIZE) return bitmap
        val scale = SIZE.toFloat() / short
        return Bitmap.createScaledBitmap(bitmap, maxOf(1, (bitmap.width * scale).toInt()), maxOf(1, (bitmap.height * scale).toInt()), true)
    }

    private fun encode(bitmap: Bitmap): Thumbnail {
        val out = ByteArrayOutputStream(16 * 1024)
        // PNG keeps transparency (icons, stickers); everything else is much smaller as JPEG.
        return if (bitmap.hasAlpha()) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            Thumbnail(out.toByteArray(), "image/png")
        } else {
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            Thumbnail(out.toByteArray(), "image/jpeg")
        }
    }

    private companion object {
        /** Pixels on the shorter side - sharp for the 36px preview on a 3x screen. */
        const val SIZE = 128
        const val JPEG_QUALITY = 80
        const val MAX_PARALLEL = 2
        const val CACHE_BYTES = 8 * 1024 * 1024
        val NONE = Thumbnail(ByteArray(0), "")

        // Keep in sync with THUMB_TYPES in app.js.
        val IMAGE_TYPES = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
        val AUDIO_TYPES = setOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav")
    }
}
