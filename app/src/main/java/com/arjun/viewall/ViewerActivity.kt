package com.arjun.viewall

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.arjun.viewall.FileKind.Companion.detect
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.github.barteksc.pdfviewer.PDFView
import com.google.android.material.appbar.MaterialToolbar
import java.io.ByteArrayInputStream
import java.io.File

class ViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "path"
        private const val ASSET_HOST = "appassets.androidplatform.net"
    }

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        val container = findViewById<FrameLayout>(R.id.container)

        val uri = intent.data
            ?: intent.getStringExtra(EXTRA_PATH)?.let { Uri.fromFile(File(it)) }
        if (uri == null) {
            finish()
            return
        }

        val name = resolveDisplayName(uri)
        toolbar.title = name
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = runCatching { contentResolver.getType(uri) }.getOrNull() ?: intent.type

        when (val kind = detect(ext, mime)) {
            FileKind.IMAGE -> showImage(container, uri, name, ext)
            FileKind.PDF -> showPdf(container, uri, name, ext)
            else -> showWeb(container, uri, kind.page, name, ext)
        }
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

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
