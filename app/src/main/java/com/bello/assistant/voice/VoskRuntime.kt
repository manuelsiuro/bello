package com.bello.assistant.voice

import android.content.Context
import com.bello.assistant.core.FileLog
import org.vosk.LibVosk
import org.vosk.LogLevel
import java.io.File

/**
 * Vosk native runtime on API 21.
 *
 * Prebuilt libvosk.so references stdin/stdout/stderr, which Android 5 libc does not export.
 * The bundled libvosk.so is patched to DT_NEED libstdiofix.so, which provides them; it must be
 * loaded before Vosk (see docs/feasibility-results.md, SP-03).
 */
object VoskRuntime {
    const val MODEL_DIR_NAME = "vosk-model-small-fr-0.22"
    private const val TAG = "vosk"
    @Volatile private var loaded = false

    @Synchronized
    fun load() {
        if (loaded) return
        System.loadLibrary("stdiofix")
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        loaded = true
        FileLog.i(TAG, "native Vosk loaded")
    }

    /** Model location, pushed by scripts/push-model.sh. */
    fun modelDir(context: Context): File = File(context.getExternalFilesDir(null), MODEL_DIR_NAME)

    fun isModelPresent(context: Context): Boolean {
        val dir = modelDir(context)
        return File(dir, "am/final.mdl").isFile && File(dir, "graph/HCLr.fst").isFile
    }
}
