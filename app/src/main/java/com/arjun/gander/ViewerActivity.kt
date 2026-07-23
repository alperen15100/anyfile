package com.arjun.gander

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
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
        private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        private const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"
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
        setUpSearch(toolbar)
        setUpActions(toolbar, uri, ext, mime)
    }

    /** Share and "show in file manager" toolbar actions. */
    private fun setUpActions(toolbar: MaterialToolbar, uri: Uri, ext: String, mime: String?) {
        toolbar.menu.findItem(R.id.action_share).setOnMenuItemClickListener {
            shareFile(uri, ext, mime)
            true
        }
        val folder = containingFolder(uri)
        toolbar.menu.findItem(R.id.action_open_folder).apply {
            isVisible = folder != null
            setOnMenuItemClickListener {
                openFolder(folder ?: return@setOnMenuItemClickListener true)
                true
            }
        }
    }

    private fun shareFile(uri: Uri, ext: String, mime: String?) {
        // Received content:// URIs go out with a read grant passed along; our own
        // file:// URIs (the shared-text temp file) go through the FileProvider
        val shareUri =
            if (uri.scheme == "content") uri
            else runCatching {
                FileProvider.getUriForFile(this, "$packageName.fileprovider", File(uri.path!!))
            }.getOrNull()
        if (shareUri == null) {
            Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*")
            .putExtra(Intent.EXTRA_STREAM, shareUri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Some Android versions refuse to delegate a tree-derived grant and
        // throw here rather than at read time
        runCatching { startActivity(Intent.createChooser(send, getString(R.string.share_file))) }
            .onFailure { Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show() }
    }

    /**
     * Best-effort document URI of the folder holding [uri], for the Files app.
     * Null when the source provider does not expose a real filesystem location,
     * which hides the menu item.
     */
    private fun containingFolder(uri: Uri): Uri? = runCatching {
        when {
            uri.scheme == "file" ->
                File(uri.path!!).parent?.let { folderDocUri(it) }
            uri.authority == EXTERNAL_STORAGE_AUTHORITY -> {
                // Document id is "volume:relative/path"; drop the file segment
                val docId = DocumentsContract.getDocumentId(uri)
                val volume = docId.substringBefore(':', "")
                val path = docId.substringAfter(':', "")
                if (volume.isEmpty() || path.isEmpty()) null
                else DocumentsContract.buildDocumentUri(
                    EXTERNAL_STORAGE_AUTHORITY,
                    "$volume:${path.substringBeforeLast('/', "")}"
                )
            }
            uri.authority == DOWNLOADS_AUTHORITY -> {
                val docId = DocumentsContract.getDocumentId(uri)
                if (docId.startsWith("raw:")) {
                    File(docId.removePrefix("raw:")).parent?.let { folderDocUri(it) }
                } else {
                    // Opaque ids (msf:42) at least live under Download
                    DocumentsContract.buildDocumentUri(
                        EXTERNAL_STORAGE_AUTHORITY, "primary:Download"
                    )
                }
            }
            uri.authority == "media" ->
                // Our read grant lets us ask MediaStore for the backing path
                contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { c ->
                    if (!c.moveToFirst()) null
                    else c.getString(0)?.let { File(it).parent }?.let { folderDocUri(it) }
                }
            else -> null
        }
    }.getOrNull()

    /** Maps an absolute folder path to an ExternalStorageProvider document URI. */
    private fun folderDocUri(path: String): Uri? {
        val primary = Environment.getExternalStorageDirectory().absolutePath
        val docId = when {
            path.startsWith(primary) ->
                "primary:" + path.removePrefix(primary).trimStart('/')
            path.startsWith("/storage/") -> {
                val rest = path.removePrefix("/storage/")
                rest.substringBefore('/') + ":" + rest.substringAfter('/', "")
            }
            else -> return null
        }
        return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, docId)
    }

    private fun openFolder(folder: Uri) {
        // The system Files app (and most file managers) handle VIEW on a
        // directory document; no grant needed, they have provider access
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(folder, DocumentsContract.Document.MIME_TYPE_DIR)
        runCatching { startActivity(view) }.onFailure {
            Toast.makeText(this, R.string.no_file_manager, Toast.LENGTH_SHORT).show()
        }
    }

    /** In-document search for WebView-rendered formats via findAllAsync. */
    private fun setUpSearch(toolbar: MaterialToolbar) {
        val bar = findViewById<LinearLayout>(R.id.searchBar)
        val input = findViewById<EditText>(R.id.searchInput)
        val count = findViewById<TextView>(R.id.searchCount)
        val searchItem = toolbar.menu.findItem(R.id.action_search)

        val web = webView
        if (web == null) {
            searchItem.isVisible = false
            return
        }
        searchItem.isVisible = true

        web.setFindListener { active, total, done ->
            if (done) {
                count.text =
                    if (total == 0 && input.text.isNotEmpty()) getString(R.string.match_count, 0, 0)
                    else if (total > 0) getString(R.string.match_count, active + 1, total)
                    else ""
            }
        }

        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        searchItem.setOnMenuItemClickListener {
            bar.visibility = LinearLayout.VISIBLE
            input.requestFocus()
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            true
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString().orEmpty()
                if (q.isEmpty()) {
                    web.clearMatches()
                    count.text = ""
                } else {
                    web.findAllAsync(q)
                }
            }
        })
        input.setOnEditorActionListener { _, _, _ ->
            web.findNext(true)
            true
        }
        findViewById<ImageButton>(R.id.searchPrev).setOnClickListener { web.findNext(false) }
        findViewById<ImageButton>(R.id.searchNext).setOnClickListener { web.findNext(true) }
        findViewById<ImageButton>(R.id.searchClose).setOnClickListener {
            imm.hideSoftInputFromWindow(input.windowToken, 0)
            input.text.clear()
            web.clearMatches()
            bar.visibility = LinearLayout.GONE
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
