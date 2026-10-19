package com.madhu.geotagcamera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class StampInfo(
    val lat: Double?,
    val lon: Double?,
    val title: String,
    val address: String,
    val qrText: String?
)

/**
 * Draws the stamp (QR, lat/long, GMT, date/time, place and address).
 * The same code draws the live preview and the saved photo, so they match.
 */
object StampRenderer {

    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(158, 24, 20, 20) }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    private val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    private val dateFmt = SimpleDateFormat("dd/MM/yyyy", Locale.US)
    private val timeFmt = SimpleDateFormat("hh:mm:ss a", Locale.US)
    private val gmtFmt = SimpleDateFormat("hh:mm:ss a", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private val qrCache = HashMap<String, Bitmap>()

    fun f6(v: Double): String = String.format(Locale.US, "%.6f", v)

    @Synchronized
    private fun qr(content: String, size: Int): Bitmap {
        val key = "$size|$content"
        qrCache[key]?.let { return it }
        val hints = mapOf(
            EncodeHintType.MARGIN to 2,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val px = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                px[y * size + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        val bmp = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
        if (qrCache.size > 8) qrCache.clear()
        qrCache[key] = bmp
        return bmp
    }

    private fun drawFit(
        c: Canvas, str: String, tf: Typeface, size: Float, maxW: Float,
        x: Float, cy: Float, align: Paint.Align
    ) {
        text.typeface = tf
        var s = size
        text.textSize = s
        while (text.measureText(str) > maxW && s > size * 0.55f) {
            s -= 1f
            text.textSize = s
        }
        text.textAlign = align
        val fm = text.fontMetrics
        c.drawText(str, x, cy - (fm.ascent + fm.descent) / 2f, text)
    }

    private fun wrap(str: String, maxW: Float, maxLines: Int): List<String> {
        val words = str.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val lines = ArrayList<String>()
        var cur = ""
        for (w in words) {
            val test = if (cur.isEmpty()) w else "$cur $w"
            if (text.measureText(test) > maxW && cur.isNotEmpty()) {
                lines.add(cur)
                cur = w
            } else {
                cur = test
            }
        }
        if (cur.isNotEmpty()) lines.add(cur)
        if (lines.size > maxLines) {
            val kept = ArrayList(lines.subList(0, maxLines))
            var last = kept[maxLines - 1]
            while (text.measureText("$last…") > maxW && last.length > 1) last = last.dropLast(1)
            kept[maxLines - 1] = "$last…"
            return kept
        }
        return lines
    }

    @Synchronized
    fun draw(c: Canvas, w: Float, h: Float, s: StampInfo, showDateTime: Boolean = true) {
        val now = Date()
        val u = minOf(w, h) / 100f
        val m = 2f * u
        val gap = 1.4f * u
        val r1h = 15f * u
        val r2h = 19f * u
        val y2 = h - m - r2h
        val y1 = y2 - gap - r1h
        val inner = w - 2f * m
        val rad = 2f * u

        // Row 1: QR, latitude, longitude, GMT
        c.drawRoundRect(RectF(m, y1, m + inner, y1 + r1h), rad, rad, panel)
        val qs = r1h - 2.4f * u
        val qx = m + 1.4f * u
        val qy = y1 + 1.2f * u
        val qrContent = s.qrText
        if (qrContent != null) c.drawBitmap(qr(qrContent, qs.toInt()), qx, qy, null)

        val cx = qx + qs + 2f * u
        val colCount = if (showDateTime) 3 else 2
        val colW = (m + inner - 1.5f * u - cx) / colCount
        val latStr = "Lat : " + (s.lat?.let { f6(it) } ?: "…")
        val lonStr = "Long : " + (s.lon?.let { f6(it) } ?: "…")
        val cols = ArrayList<Pair<String, String>>()
        cols.add("Latitude :" to latStr)
        cols.add("Longitude :" to lonStr)
        if (showDateTime) cols.add("GMT :" to gmtFmt.format(now))
        cols.forEachIndexed { i, pair ->
            val x = cx + i * colW + (if (i > 0) 1.8f * u else 0f)
            val mw = colW - (if (i > 0) 2.6f * u else 1f * u)
            drawFit(c, pair.first, medium, 3.6f * u, mw, x, y1 + r1h * 0.34f, Paint.Align.LEFT)
            drawFit(c, pair.second, bold, 3.9f * u, mw, x, y1 + r1h * 0.68f, Paint.Align.LEFT)
            if (i > 0) {
                val dx = cx + i * colW
                c.drawRect(dx - 0.2f * u, y1 + 1.4f * u, dx + 0.2f * u, y1 + r1h - 1.4f * u, white)
            }
        }

        // Row 2 left: date and time
        val tw = if (showDateTime) inner * 0.38f else 0f
        if (showDateTime) {
            c.drawRoundRect(RectF(m, y2, m + tw, y2 + r2h), rad, rad, panel)
            drawFit(c, dateFmt.format(now), medium, 4.6f * u, tw - 3f * u, m + tw / 2f, y2 + r2h * 0.34f, Paint.Align.CENTER)
            drawFit(c, timeFmt.format(now), bold, 6.6f * u, tw - 3f * u, m + tw / 2f, y2 + r2h * 0.70f, Paint.Align.CENTER)
        }

        // Row 2 right: place name and address
        val px = if (showDateTime) m + tw + gap else m
        val pw = if (showDateTime) inner - tw - gap else inner
        c.drawRoundRect(RectF(px, y2, px + pw, y2 + r2h), rad, rad, panel)
        val tx = px + 2.2f * u
        val tmw = pw - 4.4f * u
        drawFit(c, s.title, bold, 4.8f * u, tmw, tx, y2 + 3.6f * u, Paint.Align.LEFT)
        text.typeface = regular
        text.textSize = 3.5f * u
        text.textAlign = Paint.Align.LEFT
        val lines = wrap(s.address, tmw, 3)
        val fm = text.fontMetrics
        lines.forEachIndexed { i, ln ->
            val cy = y2 + 9.4f * u + i * 3.9f * u
            c.drawText(ln, tx, cy - (fm.ascent + fm.descent) / 2f, text)
        }
    }
}
