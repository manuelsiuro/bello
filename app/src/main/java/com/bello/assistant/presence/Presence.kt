package com.bello.assistant.presence

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.media.FaceDetector
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.bello.assistant.core.FileLog
import com.bello.assistant.core.Prefs

/**
 * Somebody in front of the tablet (FR-PRES-*).
 *
 * The front camera runs at its smallest preview size and a frame is looked at every couple of
 * seconds — SP-05 measured the cheap path at ≈7.5 % CPU, against 18 % for anything involving JPEG.
 * A frame becomes a grayscale bitmap straight from the Y plane and goes to `android.media`'s face
 * detector; **no image is written anywhere, sent anywhere, or kept** (FR-PRES-04). Only "a face was
 * there, or not" leaves this class.
 */
@Suppress("DEPRECATION")
class Presence(
    private val context: Context,
    private val prefs: Prefs,
    private val listener: Listener,
) {
    interface Listener {
        fun onArrived(afterLongAbsence: Boolean)
        fun onLeft()
    }

    enum class State { OFF, WATCHING, UNAVAILABLE }

    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var worker: Handler? = null

    private var camera: Camera? = null
    private var texture: SurfaceTexture? = null
    private var previewWidth = 0
    private var previewHeight = 0
    private var lastLookedAt = 0L
    private var frames = 0L
    private var faces = 0L

    @Volatile private var state = State.OFF
    @Volatile private var diagnoseFrames = 0
    private var rule = PresenceRule()

    val isWatching get() = state == State.WATCHING

    /**
     * One thread for the life of the object: opening and closing the camera have to happen in
     * order. Restarting on a fresh thread each time raced with the old one — the camera was still
     * held when the new thread asked for it, and presence stayed off until the app restarted.
     */
    private fun worker(): Handler {
        worker?.let { return it }
        val loop = HandlerThread("presence").also { it.start() }
        thread = loop
        return Handler(loop.looper).also { worker = it }
    }

    fun start() {
        if (!prefs.presenceEnabled || state == State.WATCHING) return
        rule = PresenceRule(
            absentAfterMs = ABSENT_AFTER_MS,
            greetAfterMs = prefs.greetAfterMinutes * 60_000L,
            startedAt = SystemClock.elapsedRealtime(),
        )
        // A camera that was busy a moment ago may be free now, so asking again is allowed.
        if (state == State.UNAVAILABLE) state = State.OFF
        worker().post(::open)
    }

    fun stop(reason: String) {
        if (state == State.OFF) return
        state = State.OFF
        worker().post(::close)
        FileLog.i(TAG, "PRESENCE_OFF reason=$reason frames=$frames faces=$faces")
    }

    /** The activity is going away for good. */
    fun release() {
        stop("release")
        worker().post { thread?.quitSafely() }
        thread = null
        worker = null
    }

    /**
     * Report what the camera and the detector make of the next few frames — how bright the picture
     * is, and how sure the detector is — so an empty room can be told apart from a broken one.
     * Still nothing but numbers: no frame is kept (FR-PRES-04).
     */
    fun diagnose(frames: Int = 5) {
        diagnoseFrames = frames
        FileLog.i(TAG, "PRESENCE_CHECK looking at the next $frames frames")
    }

    /** FR-PRES-05: the camera is the first thing to go when the tablet gets hot or busy. */
    fun stopForHealth(detail: String) {
        if (state != State.WATCHING) return
        FileLog.w(TAG, "PRESENCE_DISABLED $detail")
        prefs.presenceEnabled = false
        stop("health")
    }

    fun status(): String = "presence=${state.name.lowercase()}" +
        (if (prefs.presenceEnabled) "" else " (off)") +
        " present=${rule.isPresent} lastSeen=${rule.secondsSinceSeen(SystemClock.elapsedRealtime())}s" +
        " frames=$frames faces=$faces"

    // --- Camera ------------------------------------------------------------------------------

    private fun open() {
        val info = Camera.CameraInfo()
        val front = (0 until Camera.getNumberOfCameras()).firstOrNull {
            Camera.getCameraInfo(it, info)
            info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
        }
        if (front == null) {
            state = State.UNAVAILABLE
            FileLog.w(TAG, "PRESENCE_UNAVAILABLE no front camera")
            return
        }
        val opened = runCatching { Camera.open(front) }.getOrNull()
        if (opened == null) {
            state = State.UNAVAILABLE
            FileLog.w(TAG, "PRESENCE_UNAVAILABLE the camera is in use by something else")
            return
        }
        camera = opened
        runCatching {
            val params = opened.parameters
            val size = params.supportedPreviewSizes
                .filter { it.width >= MIN_PREVIEW_WIDTH }
                .minByOrNull { it.width * it.height } ?: params.supportedPreviewSizes.first()
            params.setPreviewSize(size.width, size.height)
            opened.parameters = params
            previewWidth = opened.parameters.previewSize.width
            previewHeight = opened.parameters.previewSize.height
            // An off-screen texture: the preview has to go somewhere, but nobody has to see it.
            texture = SurfaceTexture(TEXTURE_NAME).also { opened.setPreviewTexture(it) }
            opened.setPreviewCallback { data, _ -> onFrame(data) }
            opened.startPreview()
            state = State.WATCHING
            FileLog.i(TAG, "PRESENCE_WATCHING preview=${previewWidth}x$previewHeight " +
                "every ${prefs.presenceIntervalSec}s, greeting after ${prefs.greetAfterMinutes} min")
        }.onFailure {
            state = State.UNAVAILABLE
            FileLog.w(TAG, "PRESENCE_UNAVAILABLE cannot start the preview", it)
            close()
        }
    }

    private fun close() {
        camera?.let { cam ->
            runCatching { cam.setPreviewCallback(null) }
            runCatching { cam.stopPreview() }
            runCatching { cam.release() }
        }
        camera = null
        texture?.release()
        texture = null
    }

    private fun onFrame(data: ByteArray?) {
        val now = SystemClock.elapsedRealtime()
        if (data == null || now - lastLookedAt < prefs.presenceIntervalSec * 1000L) return
        lastLookedAt = now
        frames++
        val seen = runCatching { hasFace(data) }.getOrElse {
            FileLog.w(TAG, "frame not readable", it)
            return
        }
        if (diagnoseFrames > 0) {
            diagnoseFrames--
            FileLog.i(TAG, "PRESENCE_FRAME brightness=${brightness(data)}/255 " +
                "face=$seen confidence=${"%.2f".format(lastConfidence)}")
        }
        if (seen) faces++
        val change = rule.update(seen, now) ?: return
        FileLog.i(TAG, "PRESENCE_$change after ${rule.secondsSinceSeen(now)}s")
        main.post {
            when (change) {
                PresenceRule.Change.ARRIVED -> listener.onArrived(afterLongAbsence = false)
                PresenceRule.Change.ARRIVED_AND_MISSED -> listener.onArrived(afterLongAbsence = true)
                PresenceRule.Change.LEFT -> listener.onLeft()
            }
        }
    }

    /**
     * A grayscale bitmap built straight from the NV21 luminance plane, at half size: the detector
     * needs RGB_565 and an even width, and this skips the JPEG round trip entirely (SP-05).
     */
    private fun hasFace(data: ByteArray): Boolean {
        val width = (previewWidth / 2) and 1.inv()
        val height = previewHeight / 2
        if (width <= 0 || height <= 0 || data.size < previewWidth * previewHeight) return false
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val row = (y * 2) * previewWidth
            for (x in 0 until width) {
                val luma = data[row + x * 2].toInt() and 0xff
                pixels[y * width + x] = -0x1000000 or (luma shl 16) or (luma shl 8) or luma
            }
        }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.RGB_565)
        val found = arrayOfNulls<FaceDetector.Face>(1)
        val count = FaceDetector(width, height, 1).findFaces(bitmap, found)
        bitmap.recycle()
        val confidence = found[0]?.confidence() ?: 0f
        lastConfidence = confidence
        return count > 0 && confidence >= MIN_CONFIDENCE
    }

    @Volatile private var lastConfidence = 0f

    /** Mean luminance: a black frame means the camera is not really giving us the room. */
    private fun brightness(data: ByteArray): Int {
        var total = 0L
        var i = 0
        val pixels = previewWidth * previewHeight
        while (i < pixels) {
            total += data[i].toInt() and 0xff
            i += 16
        }
        return (total / (pixels / 16).coerceAtLeast(1)).toInt()
    }

    private companion object {
        const val TAG = "presence"
        const val MIN_PREVIEW_WIDTH = 320
        const val TEXTURE_NAME = 11
        const val ABSENT_AFTER_MS = 60_000L
        /** The detector reports weak "faces" in noise; SP-05 saw real ones well above this. */
        const val MIN_CONFIDENCE = 0.3f
    }
}
