package com.bello.assistant.images

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.bello.assistant.core.FileLog
import com.bello.assistant.net.HttpClients
import java.io.ByteArrayOutputStream

/**
 * The picture of a details page (FR-PAGE-07), built from the `images` block of `config.json`. Owned
 * by the Router, so a config reload picks up new keys. The picture is kept in memory with its page
 * and served by the tablet: the phone never talks to the service, and the key never leaves here.
 */
class PageImages(context: Context, config: ImageConfig) {

    class Illustration(val bytes: ByteArray, val contentType: String, val source: String, val ms: Long)

    private val gateway: ImageGateway

    init {
        val client = HttpClients.base(context.applicationContext)
        val providers = config.providers.filter { it.enabled }.map { cfg ->
            when (cfg.preset) {
                ImageConfig.CLOUDFLARE -> CloudflareImageProvider(cfg, client)
                else -> PollinationsImageProvider(cfg, client)
            }
        }
        config.problems.forEach { FileLog.w(TAG, it) }
        gateway = ImageGateway(providers, config)
        if (providers.isNotEmpty()) FileLog.i(TAG, "images with ${providers.joinToString { it.label }}")
    }

    val isEmpty: Boolean get() = gateway.isEmpty

    /** Not for the main thread. Null when there is no service or none answered in time. */
    fun illustrate(prompt: String): Illustration? {
        val started = System.currentTimeMillis()
        val picture = gateway.generate(prompt) ?: return null
        val bytes = runCatching { shrink(picture.bytes) }.getOrElse {
            FileLog.w(TAG, "IMAGE_SHRINK_FAILED ${it.javaClass.simpleName}: ${it.message}")
            picture.bytes
        }
        val type = if (bytes === picture.bytes) picture.contentType else "image/jpeg"
        return Illustration(bytes, type, picture.source, System.currentTimeMillis() - started)
    }

    fun statusLines(): List<String> = gateway.statusLines()

    /**
     * Up to ten pages stay in memory for two hours: a picture is brought down to about the width of
     * a phone screen at twice its density, which is all the page shows.
     */
    private fun shrink(bytes: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return bytes
        if (bounds.outWidth <= MAX_WIDTH && bytes.size <= MAX_BYTES) return bytes
        var sample = 1
        // Decode at half size only while that still leaves more than enough; the scaling does the rest.
        while (bounds.outWidth / (sample * 2) >= MAX_WIDTH) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return bytes
        val scaled = if (decoded.width <= MAX_WIDTH) decoded else {
            val height = decoded.height * MAX_WIDTH / decoded.width
            Bitmap.createScaledBitmap(decoded, MAX_WIDTH, height, true).also { decoded.recycle() }
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        scaled.recycle()
        val smaller = out.toByteArray()
        FileLog.i(TAG, "IMAGE_SHRUNK ${bounds.outWidth}x${bounds.outHeight} ${bytes.size} -> ${smaller.size} bytes")
        return if (smaller.size < bytes.size) smaller else bytes
    }

    private companion object {
        const val TAG = "images"
        const val MAX_WIDTH = 900
        const val MAX_BYTES = 200 * 1024
        const val QUALITY = 82
    }
}
