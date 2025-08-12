package com.example.jonathan.testutils

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.util.Xml
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.database.getStringOrNull
import androidx.core.graphics.PathParser
import org.xmlpull.v1.XmlPullParser

private const val TAG = "TUTILS: MainActivity"
private const val OUTPUT_FILE_NAME = "ic_my_vector.png"
private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

class MainActivity : ComponentActivity() {

    @RequiresApi(Build.VERSION_CODES.Q)
    private val openXml =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) {
                Log.e(TAG, "No file chosen.")
                finish()
                return@registerForActivityResult
            }
            // Persist read permission across process death (optional but handy)
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some providers don't grant persistable perms; safe to ignore.
            }

            try {
                // Render the picked vector XML to a bitmap (no VectorDrawable inflation).
                val bmp = renderVectorXmlToBitmap(
                    uri = uri,
                    // Change these if you want to force a specific output size:
                    desiredWidthDp = 128,
                    desiredHeightDp = 128
                )
                // Save PNG to public Downloads via MediaStore
                val outUri = savePngToDownloads(bmp, OUTPUT_FILE_NAME)
                Log.i(TAG, "PNG saved to: $outUri")
            } catch (t: Throwable) {
                Log.e(TAG, "Export failed", t)
            } finally {
                finish()
            }
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Launch a picker (we'll hint the system to start at Downloads if supported)
        val mimeTypes = arrayOf("application/xml", "text/xml", "*/*")
        val pickIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, MediaStore.Downloads.EXTERNAL_CONTENT_URI)
        }
        // ActivityResultContracts.OpenDocument ignores the custom intent; pass MIME types instead.
        openXml.launch(mimeTypes)
    }

    /**
     * Minimal VectorDrawable XML renderer:
     * - Supports <vector android:width/height, viewportWidth/Height>
     * - Supports multiple <path android:pathData, android:fillColor>
     * - Does NOT handle group transforms, stroke, gradients, clipPath (can be added if needed).
     */
    private fun renderVectorXmlToBitmap(
        uri: Uri,
        desiredWidthDp: Int = 128,
        desiredHeightDp: Int = 128
    ): Bitmap {
        contentResolver.openInputStream(uri).use { stream ->
            requireNotNull(stream) { "Cannot open input stream." }
            val parser: XmlPullParser = Xml.newPullParser().apply { setInput(stream, null) }

            // Defaults if width/height are missing or non-dp
            var outWidthPx = dp(desiredWidthDp)
            var outHeightPx = dp(desiredHeightDp)
            var viewportW = 0f
            var viewportH = 0f

            data class VPath(val color: Int, val pathData: String)
            val paths = mutableListOf<VPath>()

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "vector" -> {
                            // width/height like "65dp" (we ignore px/other units)
                            parser.getAttributeValue(ANDROID_NS, "width")?.let { wStr ->
                                parseDp(wStr)?.let { outWidthPx = dp(it) }
                            }
                            parser.getAttributeValue(ANDROID_NS, "height")?.let { hStr ->
                                parseDp(hStr)?.let { outHeightPx = dp(it) }
                            }
                            // viewport floats
                            parser.getAttributeValue(ANDROID_NS, "viewportWidth")
                                ?.toFloatOrNull()?.let { viewportW = it }
                            parser.getAttributeValue(ANDROID_NS, "viewportHeight")
                                ?.toFloatOrNull()?.let { viewportH = it }
                        }
                        "path" -> {
                            val data = parser.getAttributeValue(ANDROID_NS, "pathData")
                            val fill = parser.getAttributeValue(ANDROID_NS, "fillColor")
                            if (!data.isNullOrBlank() && !fill.isNullOrBlank()) {
                                val color = parseColor(fill)
                                paths += VPath(color, data)
                            }
                        }
                    }
                }
                event = parser.next()
            }

            require(viewportW > 0 && viewportH > 0) {
                "Invalid vector: viewportWidth/Height must be > 0 (got $viewportW x $viewportH)."
            }

            // Prepare canvas
            val bmp = Bitmap.createBitmap(outWidthPx, outHeightPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)

            // Scale from viewport to output pixels
            val sx = outWidthPx / viewportW
            val sy = outHeightPx / viewportH
            val scaleMatrix = Matrix().apply { setScale(sx, sy) }

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
            }

            // Draw each path
            for (vp in paths) {
                val p: Path = PathParser.createPathFromPathData(vp.pathData)
                p.transform(scaleMatrix)
                paint.color = vp.color
                canvas.drawPath(p, paint)
            }

            return bmp
        }
    }

    // ---- MediaStore (Downloads) helpers (Android 10+) ----

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun savePngToDownloads(bmp: Bitmap, displayName: String): Uri {
        // (Optional) delete any existing file with the same name to avoid duplicates
        deleteIfExistsInDownloads(displayName)

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "image/png")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Failed to create download entry.")

        contentResolver.openOutputStream(uri).use { out ->
            requireNotNull(out) { "No output stream for $uri" }
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        contentResolver.update(uri, done, null, null)
        return uri
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun deleteIfExistsInDownloads(name: String) {
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME
        )
        contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Downloads.DISPLAY_NAME}=?",
            arrayOf(name),
            null
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameIdx = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                val dn = c.getStringOrNull(nameIdx)
                if (dn == name) {
                    val itemUri = Uri.withAppendedPath(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        id.toString()
                    )
                    contentResolver.delete(itemUri, null, null)
                }
            }
        }
    }

    // ---- Small utils ----

    private fun dp(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density + 0.5f).toInt().coerceAtLeast(1)
    }

    private fun parseDp(dpString: String): Int? {
        // Accepts "65dp" or "65.0dp"
        val s = dpString.trim().lowercase()
        if (!s.endsWith("dp")) return null
        return s.removeSuffix("dp").toFloatOrNull()?.toInt()
    }

    private fun parseColor(raw: String): Int {
        // Accepts #RGB, #ARGB, #RRGGBB, #AARRGGBB
        return Color.parseColor(raw.trim())
    }
}
