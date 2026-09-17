package com.bello.spikes

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.Camera
import android.media.FaceDetector
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.File

/** SP-05: Front camera presence detection (Camera1 hardware face detection + android.media.FaceDetector). */
@Suppress("DEPRECATION")
class CameraSpike(private val act: MainActivity) : Spike {
    private val id = "sp05"
    private var camera: Camera? = null
    private var texture: SurfaceTexture? = null
    private val metrics = Metrics(act, id)
    private var lastHwLog = 0L
    private var lastFrameAt = 0L
    private var frameIntervalMs = 1000L
    private var snapshots = 0
    private var fast = false
    private var useSurface = false
    private var surfaceView: android.view.SurfaceView? = null
    private lateinit var previewSize: Camera.Size

    override fun command(action: String, intent: Intent) {
        when (action) {
            "start" -> {
                fast = intent.getBooleanExtra("fast", false)
                useSurface = intent.getBooleanExtra("surface", false)
                start(intent.getBooleanExtra("hw", true), intent.getIntExtra("intervalMs", 1000))
            }
            "snapshot" -> snapshots = 1
            "stop" -> stop()
        }
    }

    private fun start(useHw: Boolean, intervalMs: Int) {
        stop()
        frameIntervalMs = intervalMs.toLong()
        val info = Camera.CameraInfo()
        val front = (0 until Camera.getNumberOfCameras()).firstOrNull {
            Camera.getCameraInfo(it, info); info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
        } ?: return Report.log(id, "NO_FRONT_CAMERA")
        val cam = Camera.open(front)
        camera = cam
        val params = cam.parameters
        val size = params.supportedPreviewSizes.filter { it.width >= 320 }.minByOrNull { it.width * it.height }!!
        params.setPreviewSize(size.width, size.height)
        cam.parameters = params
        previewSize = cam.parameters.previewSize
        Report.log(id, "OPENED front=$front orientation=${info.orientation} preview=${size.width}x${size.height} maxHwFaces=${params.maxNumDetectedFaces}")
        if (useSurface) {
            // Some Samsung HALs only run hardware face detection with a real display surface.
            val sv = android.view.SurfaceView(act)
            surfaceView = sv
            act.container.addView(sv, android.widget.FrameLayout.LayoutParams(1, 1))
            sv.holder.addCallback(object : android.view.SurfaceHolder.Callback {
                override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                    cam.setPreviewDisplay(holder)
                    beginPreview(cam, useHw, params.maxNumDetectedFaces)
                }
                override fun surfaceChanged(holder: android.view.SurfaceHolder, f: Int, w: Int, h: Int) {}
                override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {}
            })
            metrics.start()
            return
        }
        texture = SurfaceTexture(10)
        cam.setPreviewTexture(texture)

        beginPreview(cam, useHw, params.maxNumDetectedFaces)
        metrics.start()
    }

    private fun beginPreview(cam: Camera, useHw: Boolean, maxFaces: Int) {
        cam.setFaceDetectionListener { faces, _ ->
            val now = SystemClock.elapsedRealtime()
            if (faces.isNotEmpty() && now - lastHwLog > 1000) {
                lastHwLog = now
                Report.log(id, "HW_FACES n=${faces.size} score=${faces[0].score} rect=${faces[0].rect}")
            }
        }
        cam.setPreviewCallback { data, c -> onFrame(data, c) }
        cam.startPreview()
        if (useHw && maxFaces > 0) {
            cam.startFaceDetection()
            Report.log(id, "HW_DETECTION_STARTED surface=$useSurface")
        }
    }

    private fun onFrame(data: ByteArray, cam: Camera) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFrameAt < frameIntervalMs) return
        lastFrameAt = now
        val size = previewSize
        val t0 = SystemClock.elapsedRealtime()
        if (fast && snapshots == 0) return detectFast(data, size.width, size.height, t0)
        val jpeg = ByteArrayOutputStream().also {
            YuvImage(data, ImageFormat.NV21, size.width, size.height, null)
                .compressToJpeg(Rect(0, 0, size.width, size.height), 80, it)
        }.toByteArray()
        if (snapshots > 0) {
            snapshots = 0
            File(act.getExternalFilesDir(null), "results/sp05-frame.jpg").writeBytes(jpeg)
            Report.log(id, "SNAPSHOT saved")
        }
        // Software detector: needs RGB_565 bitmap with even width.
        val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        val w = src.width and 1.inv()
        val bmp = src.copy(Bitmap.Config.RGB_565, false).let {
            if (it.width != w) Bitmap.createBitmap(it, 0, 0, w, it.height) else it
        }
        val found = arrayOfNulls<FaceDetector.Face>(3)
        val n = FaceDetector(bmp.width, bmp.height, 3).findFaces(bmp, found)
        val ms = SystemClock.elapsedRealtime() - t0
        val detail = found.firstOrNull()?.let { "conf=%.2f eyesDist=%.1f".format(it.confidence(), it.eyesDistance()) } ?: ""
        Report.log(id, "SW_FACES n=$n ms=$ms $detail")
        src.recycle(); bmp.recycle()
    }

    /** Grayscale RGB_565 bitmap straight from the NV21 Y plane, downscaled 2x. */
    private fun detectFast(data: ByteArray, w: Int, h: Int, t0: Long) {
        val bw = (w / 2) and 1.inv()
        val bh = h / 2
        val px = IntArray(bw * bh)
        for (y in 0 until bh) {
            val row = (y * 2) * w
            for (x in 0 until bw) {
                val l = data[row + x * 2].toInt() and 0xff
                px[y * bw + x] = -0x1000000 or (l shl 16) or (l shl 8) or l
            }
        }
        val bmp = Bitmap.createBitmap(px, bw, bh, Bitmap.Config.RGB_565)
        val found = arrayOfNulls<FaceDetector.Face>(1)
        val n = FaceDetector(bw, bh, 1).findFaces(bmp, found)
        bmp.recycle()
        val detail = found[0]?.let { "conf=%.2f eyesDist=%.1f".format(it.confidence(), it.eyesDistance()) } ?: ""
        Report.log(id, "SW_FACES n=$n ms=${SystemClock.elapsedRealtime() - t0} fast=true $detail")
    }

    private fun stop() {
        camera?.let {
            runCatching { it.stopFaceDetection() }
            it.setPreviewCallback(null)
            it.stopPreview()
            it.release()
            Report.log(id, "STOPPED")
        }
        camera = null
        texture?.release(); texture = null
        surfaceView?.let { act.container.removeView(it) }; surfaceView = null
        metrics.stop()
    }
}
