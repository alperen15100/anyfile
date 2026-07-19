package com.arjun.gander

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.arjun.gander.FileKind.Companion.detect
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.github.barteksc.pdfviewer.PDFView
import com.google.android.material.appbar.MaterialToolbar
import java.io.ByteArrayInputStream
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "path"
        private const val ASSET_HOST = "appassets.androidplatform.net"
    }

    private var webView: WebView? = null
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)
        applySystemBarInsets(findViewById(R.id.root))

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        val container = findViewById<FrameLayout>(R.id.container)

        // Files arrive via VIEW (data), the share sheet (EXTRA_STREAM),
        // a plain path extra, or as shared text (EXTRA_TEXT).
        val uri = intent.data
            ?: IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?: intent.getStringExtra(EXTRA_PATH)?.let { Uri.fromFile(File(it)) }
            ?: sharedTextUri()
        if (uri == null) {
            finish()
            return
        }

        val name = resolveDisplayName(uri)
        toolbar.title = name
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = runCatching { contentResolver.getType(uri) }.getOrNull() ?: intent.type

        // Picker selections carry a persistable grant; keep those in Recents.
        // Open-with and folder-browsed URIs throw here and are simply skipped.
        if (uri.scheme == "content") {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                Recents.add(this, uri, name)
            }
        }

        when (val kind = detect(ext, mime)) {
            FileKind.IMAGE -> showImage(container, uri, name, ext)
            FileKind.PDF -> showPdf(container, uri, name, ext)
            FileKind.PLAYER -> showPlayer(container, uri, name, ext)
            else -> showWeb(container, uri, kind.page, name, ext)
        }
    }

    /**
     * targetSdk 35 draws edge to edge, so push the layout out of the status bar,
     * display cutout and navigation bar areas.
     */
    private fun applySystemBarInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    /** Shared plain text becomes a temp file shown in the text viewer. */
    private fun sharedTextUri(): Uri? {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        return runCatching {
            val f = File(cacheDir, "shared-text.txt")
            f.writeText(text)
            Uri.fromFile(f)
        }.getOrNull()
    }

    private fun resolveDisplayName(uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) {
                        cursor.getString(idx)?.let { return it }
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    private fun showImage(container: FrameLayout, uri: Uri, name: String, ext: String) {
        val imageView = SubsamplingScaleImageView(this)
        imageView.setBackgroundColor(Color.BLACK)
        imageView.setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE)
        imageView.maxScale = 12f
        imageView.orientation = Thumbs.exifRotation(contentResolver, uri)
        imageView.setOnImageEventListener(object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
            override fun onImageLoadError(e: Exception) {
                // Some formats decode fine in the WebView even when the region decoder gives up
                container.removeAllViews()
                showWeb(container, uri, FileKind.IMAGE_WEB.page, name, ext)
            }
        })
        container.addView(imageView, matchParent())
        imageView.setImage(ImageSource.uri(uri))
    }

    private fun showPlayer(container: FrameLayout, uri: Uri, name: String, ext: String) {
        val playerView = PlayerView(this)
        playerView.setBackgroundColor(Color.BLACK)
        playerView.keepScreenOn = true
        playerView.controllerShowTimeoutMs = 2500
        container.addView(playerView, matchParent())

        val exo = ExoPlayer.Builder(this).build()
        player = exo
        playerView.player = exo
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                exo.release()
                player = null
                container.removeAllViews()
                showWeb(container, uri, FileKind.UNSUPPORTED.page, name, ext)
            }
        })
        exo.setMediaItem(MediaItem.fromUri(uri))
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun showPdf(container: FrameLayout, uri: Uri, name: String, ext: String) {
        val pdfView = PDFView(this, null)
        pdfView.setBackgroundColor(Color.parseColor("#FF37474F"))
        container.addView(pdfView, matchParent())
        pdfView.fromUri(uri)
            .enableAnnotationRendering(true)
            .spacing(8)
            .onError {
                container.removeAllViews()
                showWeb(container, uri, FileKind.UNSUPPORTED.page, name, ext)
            }
            .load()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWeb(container: FrameLayout, uri: Uri, page: String, name: String, ext: String) {
        val web = WebView(this)
        webView = web
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = false
            allowContentAccess = false
        }

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/doc/", DocPathHandler(uri, ext))
            .build()

        web.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = request.url.host != ASSET_HOST
        }

        container.addView(web, matchParent())
        web.loadUrl(
            "https://$ASSET_HOST/assets/viewer/$page?name=${Uri.encode(name)}&ext=${Uri.encode(ext)}"
        )
    }

    /** Streams the opened document to the WebView at /doc/<anything>. */
    private inner class DocPathHandler(
        private val uri: Uri,
        private val ext: String
    ) : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse {
            val mime = when (ext) {
                "svg" -> "image/svg+xml"
                else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                    ?: "application/octet-stream"
            }
            return try {
                // A fresh stream per request: the page may fetch the document more than once
                WebResourceResponse(mime, null, contentResolver.openInputStream(uri))
            } catch (e: Exception) {
                WebResourceResponse(
                    "text/plain", "utf-8", 404, "Not Found",
                    null, ByteArrayInputStream(ByteArray(0))
                )
            }
        }
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )

    override fun onStop() {
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
