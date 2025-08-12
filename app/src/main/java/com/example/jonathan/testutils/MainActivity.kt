package com.example.jonathan.testutils

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Xml
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.database.getStringOrNull
import androidx.core.graphics.createBitmap
import androidx.vectordrawable.graphics.drawable.VectorDrawableCompat
import org.xmlpull.v1.XmlPullParser

private const val TAG = "TUTILS: MainActivity"

class MainActivity : ComponentActivity() {
    @RequiresApi(Build.VERSION_CODES.Q)
    private val openXml =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) {
                Log.e(TAG, "No file chosen.")
                finish()
                return@registerForActivityResult
            }
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            try {
                val drawable = loadVectorFromXml(uri)  // Parse VectorDrawable XML
                val (w, h) = chooseSize(drawable, desiredDp = 128)
                val bmp = renderDrawable(drawable, w, h)
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
        Log.d(TAG, "onCreate")

        super.onCreate(savedInstanceState)

        openXml.launch(arrayOf("application/xml", "text/xml", "*/*"))
    }

    // --- Core logic ---

    private fun loadVectorFromXml(uri: Uri): Drawable {
        Log.d(TAG, "loadVectorFromXml: uri=[$uri]")

        contentResolver.openInputStream(uri).use { stream ->
            requireNotNull(stream) { "Cannot open input stream." }

            val parser: XmlPullParser = Xml.newPullParser().apply { setInput(stream, null) }

            // Move to the first START_TAG and ensure it's <vector>
            var event = parser.eventType
            while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) {
                event = parser.next()
            }
            if (event != XmlPullParser.START_TAG) error("Invalid XML: no start tag.")
            val root = parser.name
            require(root == "vector") { "Not a VectorDrawable: root=<$root>." }

            // Use compat inflater (works consistently from raw streams, API 21+)
            val attrs = Xml.asAttributeSet(parser)
            return VectorDrawableCompat.createFromXmlInner(resources, parser, attrs, theme)
                ?: error("Compat failed to inflate VectorDrawable.")
        }
    }

    private fun chooseSize(d: Drawable, desiredDp: Int): Pair<Int, Int> {
        Log.d(TAG, "chooseSize: w=[${d.intrinsicWidth}] h=[${d.intrinsicHeight}]")

        val density = resources.displayMetrics.density
        val fallbackPx = (desiredDp * density + 0.5f).toInt().coerceAtLeast(1)
        val w = if (d.intrinsicWidth > 0) d.intrinsicWidth else fallbackPx
        val h = if (d.intrinsicHeight > 0) d.intrinsicHeight else fallbackPx
        return w to h
    }

    private fun renderDrawable(d: Drawable, w: Int, h: Int): Bitmap {
        Log.d(TAG, "renderDrawable: w=[$w] h=[$h]")

        val bmp = createBitmap(w, h)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, w, h)
        d.draw(canvas)
        return bmp
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun savePngToDownloads(bmp: Bitmap, displayName: String): Uri {
        Log.d(TAG, "savePngToDownloads: name=[$displayName]")

        // Delete existing with same name (optional)
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
        Log.d(TAG, "deleteIfExistsInDownloads: name=[$name]")

        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)
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
                    val itemUri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                    contentResolver.delete(itemUri, null, null)
                }
            }
        }
    }

    companion object {
        private const val OUTPUT_FILE_NAME = "ic_my_vector.png"
    }
}
