package com.madhu.geotagcamera

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import com.madhu.geotagcamera.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var lm: LocationManager

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var overlay: OverlayEffect? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private val worker = Executors.newSingleThreadExecutor()
    private val overlayThread = HandlerThread("stamp-overlay").apply { start() }
    private val overlayHandler by lazy { Handler(overlayThread.looper) }

    @Volatile private var stampInfo = StampInfo(null, null, "Finding place…", "", null)
    @Volatile private var sensorRot = 90

    private var videoMode = false
    private var gpsLabel = "Finding location…"

    private var location: Location? = null
    private var geoTitle = ""
    private var geoAddr = ""
    private var geoKey = ""
    private var geoTime = 0L

    private var qrMode = 0
    private val modeNames = listOf("Maps link", "geo link", "Coordinates")
    private var lastUri: Uri? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (has(Manifest.permission.CAMERA)) startCamera() else toast("Camera permission is needed")
        startLocation()
    }

    private val locListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            onLoc(l)
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = getSharedPreferences("geotag", MODE_PRIVATE)
        lm = getSystemService(LOCATION_SERVICE) as LocationManager

        b.root.post {
            val avail = b.root.height - b.topBar.height - dp(130)
            val lp = b.previewFrame.layoutParams
            lp.height = minOf(b.root.width * 4 / 3, avail)
            b.previewFrame.layoutParams = lp
        }

        b.shutter.setOnClickListener {
            if (videoMode) toggleRecording() else takePhoto()
        }
        b.modeBtn.setOnClickListener {
            videoMode = !videoMode
            b.modeBtn.text = if (videoMode) "Mode: Video" else "Mode: Photo"
            setRecordingUi(false)
        }
        b.flip.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            startCamera()
        }
        b.qrBtn.setOnClickListener {
            qrMode = (qrMode + 1) % modeNames.size
            b.qrBtn.text = "QR: " + modeNames[qrMode]
            refreshStamp()
        }
        b.editBtn.setOnClickListener { showEdit() }
        b.thumb.setOnClickListener {
            val u = lastUri ?: return@setOnClickListener
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(u, contentResolver.getType(u) ?: "*/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            } catch (e: Exception) {
                toast("No gallery app found")
            }
        }

        refreshStamp()
        ensurePermissions()
    }

    override fun onResume() {
        super.onResume()
        startLocation()
    }

    override fun onPause() {
        recording?.stop()
        try { lm.removeUpdates(locListener) } catch (e: Exception) {}
        super.onPause()
    }

    override fun onDestroy() {
        overlay?.close()
        overlayThread.quitSafely()
        worker.shutdown()
        super.onDestroy()
    }

    // ---------- permissions ----------

    private fun has(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun ensurePermissions() {
        val need = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT <= 28) need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val missing = need.filter { !has(it) }
        if (missing.isEmpty()) {
            startCamera()
            startLocation()
        } else {
            permLauncher.launch(missing.toTypedArray())
        }
    }

    // ---------- camera ----------

    private fun startCamera() {
        if (!has(Manifest.permission.CAMERA)) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val ratio = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY

            val preview = Preview.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build()
                )
                .build()
                .also { it.setSurfaceProvider(b.previewView.surfaceProvider) }

            val ic = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(ratio)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(3264, 2448),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                            )
                        )
                        .build()
                )
                .build()
            imageCapture = ic

            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        Quality.FHD,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                    )
                )
                .build()
            val vc = VideoCapture.withOutput(recorder)

            // Draws the stamp onto every video frame.
            overlay?.close()
            val ov = OverlayEffect(CameraEffect.VIDEO_CAPTURE, 0, overlayHandler) { t ->
                Log.e("GeotagCamera", "Overlay error", t)
            }
            ov.setOnDrawListener { frame ->
                val canvas = frame.overlayCanvas
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                val bw = frame.size.width.toFloat()
                val bh = frame.size.height.toFloat()
                val info = stampInfo
                canvas.save()
                if (bw > bh) {
                    // Buffer is in sensor (landscape) orientation: rotate the stamp so
                    // it appears upright in the portrait video.
                    val m = Matrix()
                    if (sensorRot == 270) {
                        m.setValues(floatArrayOf(0f, -1f, bw, 1f, 0f, 0f, 0f, 0f, 1f))
                    } else {
                        m.setValues(floatArrayOf(0f, 1f, 0f, -1f, 0f, bh, 0f, 0f, 1f))
                    }
                    canvas.concat(m)
                    StampRenderer.draw(canvas, bh, bw, info, false)
                } else {
                    StampRenderer.draw(canvas, bw, bh, info, false)
                }
                canvas.restore()
                true
            }
            overlay = ov

            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            provider.unbindAll()
            try {
                val group = UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(ic)
                    .addUseCase(vc)
                    .addEffect(ov)
                    .build()
                val cam = provider.bindToLifecycle(this, selector, group)
                sensorRot = cam.cameraInfo.sensorRotationDegrees
                videoCapture = vc
            } catch (e: Exception) {
                videoCapture = null
                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(this, selector, preview, ic)
                    toast("Video isn’t available with this camera")
                } catch (e2: Exception) {
                    toast("Camera error: " + e2.message)
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---------- photo ----------

    private fun takePhoto() {
        val ic = imageCapture ?: return
        b.shutter.isEnabled = false
        val info = currentInfo()
        val loc = location
        ic.takePicture(worker, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val src = image.toBitmap()
                    val rot = image.imageInfo.rotationDegrees
                    image.close()
                    val stamped = stampBitmap(src, rot, info)
                    val uri = save(stamped, loc, info)
                    val thumb = Bitmap.createScaledBitmap(stamped, 150, 200, true)
                    runOnUiThread {
                        b.shutter.isEnabled = true
                        if (uri != null) {
                            lastUri = uri
                            b.thumb.setImageBitmap(thumb)
                            toast(if (loc != null) "Saved with GPS data to Pictures/GeotagCamera" else "Saved (no location fix yet)")
                        } else {
                            toast("Couldn’t save the photo")
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        b.shutter.isEnabled = true
                        toast("Capture failed: " + e.message)
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    b.shutter.isEnabled = true
                    toast("Capture failed: " + exception.message)
                }
            }
        })
    }

    private fun stampBitmap(src: Bitmap, rotation: Int, info: StampInfo): Bitmap {
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        val out = if (rotated.isMutable) rotated else rotated.copy(Bitmap.Config.ARGB_8888, true)
        StampRenderer.draw(Canvas(out), out.width.toFloat(), out.height.toFloat(), info)
        return out
    }

    private fun save(bmp: Bitmap, loc: Location?, info: StampInfo): Uri? {
        val now = Date()
        val name = "GEO_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GeotagCamera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }

        // Write the location (and date, place) into the photo's own metadata (EXIF).
        try {
            contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                if (loc != null) exif.setGpsInfo(loc)
                val stamp = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(now)
                exif.setAttribute(ExifInterface.TAG_DATETIME, stamp)
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, stamp)
                val desc = listOf(info.title, info.address).filter { it.isNotBlank() }.joinToString(" - ")
                if (desc.isNotBlank()) exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, desc)
                exif.saveAttributes()
            }
        } catch (e: Exception) {
        }

        if (Build.VERSION.SDK_INT >= 29) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        }
        return uri
    }

    // ---------- video ----------

    @SuppressLint("MissingPermission")
    private fun toggleRecording() {
        val active = recording
        if (active != null) {
            active.stop()
            return
        }
        val vc = videoCapture
        if (vc == null) {
            toast("Video isn’t available with this camera")
            return
        }
        val name = "GEO_VID_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/GeotagCamera")
            }
        }
        // The start location is also saved in the video file's own metadata.
        val opts = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .setLocation(location)
            .build()
        var pending = vc.output.prepareRecording(this, opts)
        if (has(Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled()
        recording = pending.start(ContextCompat.getMainExecutor(this)) { ev -> onRecordEvent(ev) }
        setRecordingUi(true)
    }

    private fun onRecordEvent(ev: VideoRecordEvent) {
        when (ev) {
            is VideoRecordEvent.Status -> {
                val s = (ev.recordingStats.recordedDurationNanos / 1_000_000_000L).toInt()
                b.gpsText.text = String.format(Locale.US, "● REC %02d:%02d", s / 60, s % 60)
            }
            is VideoRecordEvent.Finalize -> {
                recording = null
                setRecordingUi(false)
                if (ev.hasError()) {
                    toast("Recording failed (code " + ev.error + ")")
                } else {
                    val u = ev.outputResults.outputUri
                    lastUri = u
                    toast("Video saved to Movies/GeotagCamera")
                    videoThumb(u)
                }
            }
            else -> {}
        }
    }

    private fun videoThumb(uri: Uri) {
        worker.execute {
            try {
                val r = MediaMetadataRetriever()
                r.setDataSource(this, uri)
                val f = r.getFrameAtTime(0)
                r.release()
                if (f != null) {
                    val th = 200
                    val tw = maxOf(1, f.width * th / f.height)
                    val t = Bitmap.createScaledBitmap(f, tw, th, true)
                    runOnUiThread { b.thumb.setImageBitmap(t) }
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun setRecordingUi(rec: Boolean) {
        b.shutter.setBackgroundResource(
            when {
                rec -> R.drawable.shutter_rec
                videoMode -> R.drawable.shutter_video
                else -> R.drawable.shutter_bg
            }
        )
        b.stampView.showDateTime = !videoMode
        b.flip.isEnabled = !rec
        b.modeBtn.isEnabled = !rec
        if (!rec) b.gpsText.text = gpsLabel
    }

    // ---------- location ----------

    private fun setGps(text: String) {
        gpsLabel = text
        if (recording == null) b.gpsText.text = text
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION) && !has(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            setGps("Location blocked")
            return
        }
        try { lm.removeUpdates(locListener) } catch (e: Exception) {}
        var any = false
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (lm.isProviderEnabled(p)) {
                    any = true
                    lm.requestLocationUpdates(p, 2000L, 1f, locListener, Looper.getMainLooper())
                    lm.getLastKnownLocation(p)?.let { onLoc(it) }
                }
            } catch (e: Exception) {
            }
        }
        if (!any) setGps("Turn on location (GPS)")
    }

    private fun onLoc(l: Location) {
        val cur = location
        if (cur == null || l.provider == LocationManager.GPS_PROVIDER ||
            l.time - cur.time > 15000 || l.accuracy < cur.accuracy
        ) {
            location = l
        }
        val loc = location ?: return
        setGps("Location locked (±" + loc.accuracy.roundToInt() + " m)")
        refreshStamp()
        lookupAddress(loc)
    }

    private fun lookupAddress(l: Location) {
        val key = String.format(Locale.US, "%.4f,%.4f", l.latitude, l.longitude)
        val now = SystemClock.elapsedRealtime()
        if (key == geoKey || now - geoTime < 15000) return
        geoTime = now
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { reverse(l.latitude, l.longitude) }
            if (r != null) {
                geoTitle = r.first
                geoAddr = r.second
                geoKey = key
                refreshStamp()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun reverse(lat: Double, lon: Double): Pair<String, String>? {
        try {
            if (Geocoder.isPresent()) {
                val a = Geocoder(this, Locale.ENGLISH).getFromLocation(lat, lon, 1)?.firstOrNull()
                if (a != null) {
                    val place = a.subLocality ?: a.locality ?: a.subAdminArea ?: ""
                    val title = listOf(place, a.adminArea).filter { !it.isNullOrBlank() }.joinToString(", ")
                    val addr = a.getAddressLine(0) ?: ""
                    if (addr.isNotBlank()) return Pair(title, addr)
                }
            }
        } catch (e: Exception) {
        }
        try {
            val url = URL(
                "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&addressdetails=1" +
                    "&accept-language=en&lat=$lat&lon=$lon"
            )
            val c = url.openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", "GeotagCamera/1.0 (personal app)")
            c.connectTimeout = 8000
            c.readTimeout = 8000
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val ad = j.optJSONObject("address")
            var place = ""
            for (k in listOf("suburb", "neighbourhood", "village", "town", "city_district", "city", "county")) {
                val v = ad?.optString(k) ?: ""
                if (v.isNotBlank()) { place = v; break }
            }
            val state = ad?.optString("state") ?: ""
            val title = listOf(place, state).filter { it.isNotBlank() }.joinToString(", ")
            val addr = j.optString("display_name")
            if (addr.isNotBlank()) return Pair(title, addr)
        } catch (e: Exception) {
        }
        return null
    }

    // ---------- stamp text ----------

    private fun currentInfo(): StampInfo {
        val l = location
        val manualTitle = prefs.getString("title", "") ?: ""
        val manualAddr = prefs.getString("addr", "") ?: ""
        val title = manualTitle.ifBlank {
            geoTitle.ifBlank { if (l != null) "Location locked" else "Finding place…" }
        }
        val addr = manualAddr.ifBlank {
            geoAddr.ifBlank {
                if (l != null)
                    "Lat " + StampRenderer.f6(l.latitude) + ", Long " + StampRenderer.f6(l.longitude) +
                        " (±" + l.accuracy.roundToInt() + " m)"
                else ""
            }
        }
        val qr = l?.let {
            val la = StampRenderer.f6(it.latitude)
            val lo = StampRenderer.f6(it.longitude)
            when (qrMode) {
                0 -> "https://www.google.com/maps?q=$la,$lo"
                1 -> "geo:$la,$lo"
                else -> "$la, $lo"
            }
        }
        return StampInfo(l?.latitude, l?.longitude, title, addr, qr)
    }

    private fun refreshStamp() {
        val info = currentInfo()
        stampInfo = info
        b.stampView.info = info
    }

    private fun showEdit() {
        val pad = dp(16)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val t = EditText(this).apply {
            hint = geoTitle.ifBlank { "Place name" }
            setText(prefs.getString("title", ""))
            setSingleLine()
        }
        val a = EditText(this).apply {
            hint = geoAddr.ifBlank { "Address" }
            setText(prefs.getString("addr", ""))
            minLines = 2
        }
        col.addView(t)
        col.addView(a)
        AlertDialog.Builder(this)
            .setTitle("Edit stamp text")
            .setView(col)
            .setPositiveButton("Done") { _, _ ->
                prefs.edit()
                    .putString("title", t.text.toString().trim())
                    .putString("addr", a.text.toString().trim())
                    .apply()
                refreshStamp()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- helpers ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
