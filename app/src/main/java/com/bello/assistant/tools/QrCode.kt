package com.bello.assistant.tools

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * The QR code of a URL as rows of '0' and '1' (FR-PAGE-05); the face draws them. ZXing core is
 * pure Java, so this runs and is tested on the JVM. No quiet zone here — the card adds it.
 */
object QrCode {

    fun modules(text: String): List<String> {
        val matrix = Encoder.encode(text, ErrorCorrectionLevel.M).matrix
        return (0 until matrix.height).map { y ->
            buildString(matrix.width) {
                for (x in 0 until matrix.width) append(if (matrix.get(x, y).toInt() == 1) '1' else '0')
            }
        }
    }
}
