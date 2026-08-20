package com.ecrinlabs.anyfile

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.content.ContentValues
import android.provider.MediaStore
import android.os.Build
import android.provider.OpenableColumns
import com.google.android.material.button.MaterialButton
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import java.io.File

class MainActivity : AppCompatActivity() {

    private sealed interface Row {
        data class Header(val title: String) : Row
        data class Hint(val text: String) : Row
        data class Item(
            val badge: String,
            val color: Int,
            val title: String,
            val subtitle: String?,
            val onClick: () -> Unit,
            val onLongClick: (() -> Unit)? = null,
            val thumbUri: Uri? = null,
            val thumbExt: String = ""
        ) : Row
    }

    private data class Crumb(val treeUri: Uri, val docId: String, val label: String)

    private val stack = ArrayDeque<Crumb>()
    private val adapter = RowAdapter()
    private lateinit var toolbar: MaterialToolbar
    private lateinit var homeHero: View
    private lateinit var homeScreen: View
    private lateinit var toolsScreen: View
    private lateinit var bottomNav: BottomNavigationView

    private enum class ToolAction { IMAGE_TO_JPG, IMAGE_TO_PNG, IMAGE_TO_PDF, TEXT_TO_PDF, FILE_INFO, EXTRACT_ZIP, UNKNOWN_FILE }
    private var pendingTool: ToolAction? = null
    private var pendingZipInputs: List<Uri> = emptyList()
    private var pendingZipUri: Uri? = null

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            stack.removeLast()
            render()
        }
    }

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) openInViewer(uri)
        }

    private val openTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                render()
            }
        }


    private val pickToolFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) handleToolFile(uri)
        }

    private val createOutput =
        registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { outUri ->
            val input = pendingInputUri
            val action = pendingTool
            if (outUri != null && input != null && action != null) {
                val ok = when (action) {
                    ToolAction.IMAGE_TO_JPG -> convertImage(input, outUri, Bitmap.CompressFormat.JPEG)
                    ToolAction.IMAGE_TO_PNG -> convertImage(input, outUri, Bitmap.CompressFormat.PNG)
                    ToolAction.IMAGE_TO_PDF -> imageToPdf(input, outUri)
                    ToolAction.TEXT_TO_PDF -> textToPdf(input, outUri)
                    ToolAction.FILE_INFO -> false
                    ToolAction.EXTRACT_ZIP -> false
                    ToolAction.UNKNOWN_FILE -> false
                }
                Toast.makeText(this, if (ok) R.string.tool_done else R.string.tool_failed, Toast.LENGTH_SHORT).show()
            }
            pendingInputUri = null
            pendingTool = null
        }

    private var pendingInputUri: Uri? = null


    private val pickZipInputs =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                pendingZipInputs = uris
                createZipOutput.launch("ANYFILE-archive.zip")
            }
        }

    private val createZipOutput =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { outUri ->
            if (outUri != null && pendingZipInputs.isNotEmpty()) {
                val ok = createZip(pendingZipInputs, outUri)
                Toast.makeText(this, if (ok) R.string.tool_done else R.string.tool_failed, Toast.LENGTH_SHORT).show()
            }
            pendingZipInputs = emptyList()
        }

    private val pickZipToExtract =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                pendingZipUri = uri
                pickExtractFolder.launch(null)
            }
        }

    private val pickExtractFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            val zipUri = pendingZipUri
            if (treeUri != null && zipUri != null) {
                val ok = extractZip(zipUri, treeUri)
                Toast.makeText(this, if (ok) R.string.extract_done else R.string.tool_failed, Toast.LENGTH_SHORT).show()
            }
            pendingZipUri = null
        }

    private val pickImagesForPdf =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                pendingMultiImages = uris
                createMultiPdfOutput.launch("ANYFILE-images.pdf")
            }
        }

    private var pendingMultiImages: List<Uri> = emptyList()

    private val createMultiPdfOutput =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { outUri ->
            if (outUri != null && pendingMultiImages.isNotEmpty()) {
                val ok = imagesToPdf(pendingMultiImages, outUri)
                Toast.makeText(this, if (ok) R.string.tool_done else R.string.tool_failed, Toast.LENGTH_SHORT).show()
            }
            pendingMultiImages = emptyList()
        }


    private var pendingPdfInputs: List<Uri> = emptyList()
    private var pendingSinglePdf: Uri? = null
    private var pendingImageUri: Uri? = null
    private var pendingResizeWidth = 0
    private var pendingResizeHeight = 0

    private val pickPdfsForMerge =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                pendingPdfInputs = uris
                createMergedPdf.launch("ANYFILE-merged.pdf")
            }
        }

    private val createMergedPdf =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { outUri ->
            if (outUri != null && pendingPdfInputs.isNotEmpty()) {
                val ok = rasterMergePdfs(pendingPdfInputs, outUri)
                Toast.makeText(this, if (ok) R.string.pdf_merge_done else R.string.tool_failed, Toast.LENGTH_SHORT).show()
            }
            pendingPdfInputs = emptyList()
        }

    private val pickPdfForSplit =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                pendingSinglePdf = uri
                showSplitDialog(uri)
            }
        }

    private val createSplitPdf =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { outUri ->
            val input = pendingSinglePdf
            val range = pendingSplitRange
            if (outUri != null && input != null && range != null) {
                val ok = rasterPdfRange(input, outUri, range.first, range.last)
                Toast.makeText(this, if (ok) R.string.pdf_split_done else R.string.tool_failed, Toast.LENGTH_SHORT).show()
            }
            pendingSinglePdf = null
            pendingSplitRange = null
        }

    private var pendingSplitRange: IntRange? = null

    private val pickPdfForImages =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) exportPdfPagesToImages(uri)
        }

    private val pickImageForCompress =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                pendingImageUri = uri
                createCompressedImage.launch(defaultOutputName(uri, "jpg").substringBeforeLast(".jpg") + "-compressed.jpg")
            }
        }

    private val createCompressedImage =
        registerForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { outUri ->
            val input = pendingImageUri
            if (outUri != null && input != null) {
                val ok = compressImage(input, outUri)
                Toast.makeText(this, if (ok) R.string.image_compress_done else R.string.tool_failed, Toast.LENGTH_SHORT).show()
            }
            pendingImageUri = null
        }

    private val pickImageForResize =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) showResizeDialog(uri)
        }

    private val createResizedImage =
        registerForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { outUri ->
            val input = pendingImageUri
            if (outUri != null && input != null) {
                val ok = resizeImage(input, outUri, pendingResizeWidth, pendingResizeHeight)
                Toast.makeText(this, if (ok) R.string.image_resize_done else R.string.tool_failed, Toast.LENGTH_SHORT).show()
            }
            pendingImageUri = null
        }

    private val pickFileForManage =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) showManageFileDialog(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        toolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { backCallback.handleOnBackPressed() }
        // render() rewrites the title and navigation icon on every resume and on
        // every folder change, but never touches the menu, so inflating once here
        // survives all of it.
        toolbar.inflateMenu(R.menu.main_menu)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_about) {
                showAbout()
                true
            } else {
                false
            }
        }
        findViewById<RecyclerView>(R.id.list).let {
            it.layoutManager = LinearLayoutManager(this)
            it.adapter = adapter
        }
        homeHero = findViewById(R.id.homeHero)
        homeScreen = findViewById(R.id.homeScreen)
        toolsScreen = findViewById(R.id.toolsScreen)
        bottomNav = findViewById(R.id.bottomNav)

        findViewById<View>(R.id.openToolsButton).setOnClickListener {
            showToolsScreen()
        }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    showHomeScreen()
                    true
                }
                R.id.nav_tools -> {
                    showToolsScreen()
                    true
                }
                R.id.nav_about -> {
                    showAbout()
                    false
                }
                else -> false
            }
        }

        findViewById<View>(R.id.openHeroButton).setOnClickListener {
            openDocument.launch(arrayOf("*/*"))
        }

        findViewById<ExtendedFloatingActionButton>(R.id.openFab).setOnClickListener {
            openDocument.launch(arrayOf("*/*"))
        }

        // File type shortcuts
        findViewById<View>(R.id.typePdf).setOnClickListener { openDocument.launch(arrayOf("application/pdf")) }
        findViewById<View>(R.id.typeWord).setOnClickListener {
            openDocument.launch(arrayOf(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/msword"
            ))
        }
        findViewById<View>(R.id.typeExcel).setOnClickListener {
            openDocument.launch(arrayOf(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel",
                "text/csv"
            ))
        }
        findViewById<View>(R.id.typeSlides).setOnClickListener {
            openDocument.launch(arrayOf(
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "application/vnd.ms-powerpoint"
            ))
        }
        findViewById<View>(R.id.typeImages).setOnClickListener { openDocument.launch(arrayOf("image/*")) }
        findViewById<View>(R.id.typeVideo).setOnClickListener { openDocument.launch(arrayOf("video/*")) }
        findViewById<View>(R.id.typeAudio).setOnClickListener { openDocument.launch(arrayOf("audio/*")) }
        findViewById<View>(R.id.typeCode).setOnClickListener {
            openDocument.launch(arrayOf("text/*", "application/json", "application/xml"))
        }

        // V3 quick tools — all free
        findViewById<View>(R.id.toolJpg).setOnClickListener { launchTool(ToolAction.IMAGE_TO_JPG, arrayOf("image/*")) }
        findViewById<View>(R.id.toolPng).setOnClickListener { launchTool(ToolAction.IMAGE_TO_PNG, arrayOf("image/*")) }
        findViewById<View>(R.id.toolImagePdf).setOnClickListener { launchTool(ToolAction.IMAGE_TO_PDF, arrayOf("image/*")) }
        findViewById<View>(R.id.toolTextPdf).setOnClickListener { launchTool(ToolAction.TEXT_TO_PDF, arrayOf("text/*", "application/json", "application/xml")) }
        findViewById<View>(R.id.toolInfo).setOnClickListener { launchTool(ToolAction.FILE_INFO, arrayOf("*/*")) }
        findViewById<View>(R.id.toolSearch).setOnClickListener { showSearchDialog() }
        findViewById<View>(R.id.toolFavorites).setOnClickListener { showFavorites() }
        findViewById<View>(R.id.toolCreateZip).setOnClickListener {
            pickZipInputs.launch(arrayOf("*/*"))
        }
        findViewById<View>(R.id.toolExtractZip).setOnClickListener {
            pickZipToExtract.launch(arrayOf("application/zip", "application/x-zip-compressed"))
        }
        findViewById<View>(R.id.toolMultiPdf).setOnClickListener {
            pickImagesForPdf.launch(arrayOf("image/*"))
        }
        findViewById<View>(R.id.toolDetect).setOnClickListener {
            pendingTool = ToolAction.UNKNOWN_FILE
            pickToolFile.launch(arrayOf("*/*"))
        }

        findViewById<View>(R.id.toolPdfMerge).setOnClickListener {
            pickPdfsForMerge.launch(arrayOf("application/pdf"))
        }
        findViewById<View>(R.id.toolPdfSplit).setOnClickListener {
            pickPdfForSplit.launch(arrayOf("application/pdf"))
        }
        findViewById<View>(R.id.toolPdfImages).setOnClickListener {
            pickPdfForImages.launch(arrayOf("application/pdf"))
        }
        findViewById<View>(R.id.toolCompressImage).setOnClickListener {
            pickImageForCompress.launch(arrayOf("image/*"))
        }
        findViewById<View>(R.id.toolResizeImage).setOnClickListener {
            pickImageForResize.launch(arrayOf("image/*"))
        }
        findViewById<View>(R.id.toolManageFile).setOnClickListener {
            pickFileForManage.launch(arrayOf("*/*"))
        }

        onBackPressedDispatcher.addCallback(this, backCallback)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun showHomeScreen() {
        homeScreen.visibility = View.VISIBLE
        toolsScreen.visibility = View.GONE
        bottomNav.menu.findItem(R.id.nav_home).isChecked = true
        toolbar.title = getString(R.string.app_name)
    }

    private fun showToolsScreen() {
        homeScreen.visibility = View.GONE
        toolsScreen.visibility = View.VISIBLE
        bottomNav.menu.findItem(R.id.nav_tools).isChecked = true
        toolbar.title = getString(R.string.tools)
    }

    private fun openInViewer(uri: Uri) {
        startActivity(
            Intent(this, ViewerActivity::class.java)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    /**
     * The app's only About surface. Carries the version, who made it, and the
     * permission list read back out of Android, plus the way in to the licence
     * text the bundled libraries require to travel with the binary.
     */
    private fun showAbout() {
        val view = layoutInflater.inflate(R.layout.dialog_about, null)

        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull().orEmpty()
        view.findViewById<TextView>(R.id.aboutVersion).text =
            getString(R.string.about_version, version)

        val permissions = requestedPermissions()
        val field = view.findViewById<TextView>(R.id.aboutPermissions)
        when {
            // Only when the package manager refused to answer. Printing "none"
            // for a question we could not ask would be the one dishonest thing
            // this dialog could do, so it says nothing at all instead.
            permissions == null ->
                view.findViewById<View>(R.id.aboutPermissionsCard).visibility = View.GONE
            permissions.isEmpty() -> field.setText(R.string.about_permissions_none)
            // Never expected: assembleRelease fails before a build can get here.
            // Shown rather than swallowed, because a broken promise is the thing
            // a reader of this dialog most needs to know.
            else -> {
                field.text = permissions.joinToString("\n")
                field.setTextColor(
                    MaterialColors.getColor(field, com.google.android.material.R.attr.colorError)
                )
            }
        }

        view.findViewById<View>(R.id.aboutAuthor)
            .setOnClickListener { openUrl(getString(R.string.url_author)) }
        view.findViewById<View>(R.id.aboutSource)
            .setOnClickListener { openUrl(getString(R.string.url_source)) }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_gander)
            .setView(view)
            .setPositiveButton(R.string.about_close, null)
            .show()

        view.findViewById<View>(R.id.aboutLicences).setOnClickListener {
            dialog.dismiss()
            openLicences()
        }
    }

    /**
     * What Android says this install asks for, or null if it would not say.
     *
     * androidx.core declares DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION under our
     * own package name so libraries can registerReceiver safely. It is signature
     * level, self-granted and never shown to a user, which is why the permission
     * check in build.gradle.kts allowlists it as well. Anything else carrying our
     * package prefix is ours on the same reasoning, so drop those and report what
     * is left, which is the list Android would actually confront someone with.
     */
    private fun requestedPermissions(): List<String>? = runCatching {
        packageManager
            .getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
            .filterNot { it.startsWith("$packageName.") }
    }.getOrNull()

    /**
     * ANYFILE shows its bundled open-source licences. The asset is copied into the cache and
     * handed to the viewer as a plain path, so the bundled Markdown renderer
     * draws it and there is no second document surface to keep alive.
     *
     * Copied on every open rather than once: the cache outlives an app update,
     * and an update is exactly when the text changes. The viewer only records
     * content:// URIs in Recents, so this cannot turn up there.
     */
    private fun openLicences() {
        val file = File(cacheDir, getString(R.string.licences_file_name))
        val opened = runCatching {
            assets.open(LICENCES_ASSET).use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            startActivity(
                Intent(this, ViewerActivity::class.java)
                    .putExtra(ViewerActivity.EXTRA_PATH, file.absolutePath)
            )
        }.isSuccess
        if (!opened) Toast.makeText(this, R.string.licences_failed, Toast.LENGTH_SHORT).show()
    }

    /**
     * Hands a URL to whichever browser the user has. ANYFILE never fetches
     * anything itself, and without the INTERNET permission it could not.
     */
    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show() }
    }

    private fun render() {
        backCallback.isEnabled = stack.isNotEmpty()
        val rows = if (stack.isEmpty()) homeRows() else folderRows(stack.last())
        homeHero.visibility = if (stack.isEmpty()) View.VISIBLE else View.GONE
        if (stack.isNotEmpty()) {
            homeScreen.visibility = View.VISIBLE
            toolsScreen.visibility = View.GONE
        }
        toolbar.title = if (stack.isEmpty()) getString(R.string.app_name) else stack.last().label
        toolbar.navigationIcon =
            if (stack.isEmpty()) null
            else androidx.appcompat.content.res.AppCompatResources.getDrawable(this, R.drawable.ic_back)
        toolbar.navigationContentDescription = getString(R.string.back)
        adapter.submit(rows)
    }

    private fun homeRows(): List<Row> {
        val rows = mutableListOf<Row>()
        rows += Row.Header(getString(R.string.recent_files))
        val recents = Recents.all(this)
        if (recents.isEmpty()) {
            rows += Row.Hint(getString(R.string.no_recents_hint))
        } else {
            recents.forEach { r ->
                val (badge, color) = badgeFor(r.name, null)
                val ext = r.name.substringAfterLast('.', "").lowercase()
                val uri = Uri.parse(r.uri)
                rows += Row.Item(
                    badge, color, r.name,
                    relativeTimeEnglish(r.time),
                    onClick = { openInViewer(uri) },
                    onLongClick = { showRecentOptions(r.uri, r.name) },
                    thumbUri = uri.takeIf { Thumbs.supported(FileKind.detect(ext, null), ext) },
                    thumbExt = ext
                )
            }
        }
        rows += Row.Header(getString(R.string.folders))
        val roots = contentResolver.persistedUriPermissions
            .filter { it.isReadPermission && isTreeUri(it.uri) }
            .sortedBy { treeLabel(it.uri).lowercase() }
        if (roots.isEmpty()) rows += Row.Hint(getString(R.string.no_folders_hint))
        roots.forEach { perm ->
            val label = treeLabel(perm.uri)
            rows += Row.Item(
                "DIR", DIR_COLOR, label, null,
                onClick = {
                    stack.addLast(
                        Crumb(perm.uri, DocumentsContract.getTreeDocumentId(perm.uri), label)
                    )
                    render()
                },
                onLongClick = {
                    runCatching {
                        contentResolver.releasePersistableUriPermission(
                            perm.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    Toast.makeText(this, R.string.removed, Toast.LENGTH_SHORT).show()
                    render()
                }
            )
        }
        rows += Row.Item("+", ADD_COLOR, getString(R.string.add_folder), null,
            onClick = { openTree.launch(null) })
        return rows
    }

    private fun folderRows(crumb: Crumb): List<Row> {
        data class Child(
            val docId: String, val name: String, val mime: String,
            val size: Long, val modified: Long
        )

        val children = mutableListOf<Child>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            crumb.treeUri, crumb.docId
        )
        runCatching {
            contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    children += Child(
                        c.getString(0), c.getString(1) ?: "?", c.getString(2) ?: "",
                        c.getLong(3), c.getLong(4)
                    )
                }
            }
        }

        val dirs = children
            .filter { it.mime == DocumentsContract.Document.MIME_TYPE_DIR }
            .filterNot { it.name.startsWith(".") }
            .sortedBy { it.name.lowercase() }
        val files = children
            .filter { it.mime != DocumentsContract.Document.MIME_TYPE_DIR }
            .filterNot { it.name.startsWith(".") }
            .sortedBy { it.name.lowercase() }

        val rows = mutableListOf<Row>()
        dirs.forEach { d ->
            rows += Row.Item("DIR", DIR_COLOR, d.name, null, onClick = {
                stack.addLast(Crumb(crumb.treeUri, d.docId, d.name))
                render()
            })
        }
        files.forEach { f ->
            val (badge, color) = badgeFor(f.name, f.mime)
            val ext = f.name.substringAfterLast('.', "").lowercase()
            val fileUri = DocumentsContract.buildDocumentUriUsingTree(crumb.treeUri, f.docId)
            val subtitle = listOfNotNull(
                Formatter.formatShortFileSize(this, f.size).takeIf { f.size > 0 },
                DateUtils.getRelativeTimeSpanString(f.modified).toString()
                    .takeIf { f.modified > 0 }
            ).joinToString(" · ").ifEmpty { null }
            rows += Row.Item(
                badge, color, f.name, subtitle,
                onClick = { openInViewer(fileUri) },
                thumbUri = fileUri.takeIf {
                    Thumbs.supported(FileKind.detect(ext, f.mime), ext)
                },
                thumbExt = ext
            )
        }
        if (rows.isEmpty()) rows += Row.Hint(getString(R.string.empty_folder))
        return rows
    }




    private fun renderPdfPage(renderer: PdfRenderer, index: Int): Bitmap {
        renderer.openPage(index).use { page ->
            val scale = 2
            val bitmap = Bitmap.createBitmap(
                (page.width * scale).coerceAtLeast(1),
                (page.height * scale).coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        }
    }

    private fun addBitmapPage(pdf: PdfDocument, bitmap: Bitmap, pageNumber: Int) {
        val pageWidth = 1240
        val pageHeight = 1754
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        page.canvas.drawColor(Color.WHITE)
        val margin = 32f
        val maxW = pageWidth - margin * 2
        val maxH = pageHeight - margin * 2
        val scale = minOf(maxW / bitmap.width, maxH / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        val left = (pageWidth - w) / 2f
        val top = (pageHeight - h) / 2f
        page.canvas.drawBitmap(bitmap, null, android.graphics.RectF(left, top, left + w, top + h), Paint(Paint.ANTI_ALIAS_FLAG))
        pdf.finishPage(page)
    }

    private fun rasterMergePdfs(inputs: List<Uri>, output: Uri): Boolean = runCatching {
        val pdf = PdfDocument()
        var outputPage = 1
        for (uri in inputs) {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    for (i in 0 until renderer.pageCount) {
                        val bitmap = renderPdfPage(renderer, i)
                        addBitmapPage(pdf, bitmap, outputPage++)
                        bitmap.recycle()
                    }
                }
            }
        }
        val ok = outputPage > 1 && contentResolver.openOutputStream(output)?.use {
            pdf.writeTo(it)
            true
        } == true
        pdf.close()
        ok
    }.getOrDefault(false)

    private fun pdfPageCount(uri: Uri): Int = runCatching {
        contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            PdfRenderer(pfd).use { it.pageCount }
        } ?: 0
    }.getOrDefault(0)

    private fun showSplitDialog(uri: Uri) {
        val count = pdfPageCount(uri)
        if (count <= 0) {
            Toast.makeText(this, R.string.tool_failed, Toast.LENGTH_SHORT).show()
            pendingSinglePdf = null
            return
        }
        val input = android.widget.EditText(this).apply {
            hint = "1-$count"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setText("1-$count")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.pdf_split_title, count))
            .setMessage(R.string.pdf_split_help)
            .setView(input)
            .setPositiveButton(R.string.continue_label) { _, _ ->
                val parsed = parsePageRange(input.text?.toString().orEmpty(), count)
                if (parsed == null) {
                    Toast.makeText(this, R.string.invalid_page_range, Toast.LENGTH_SHORT).show()
                    pendingSinglePdf = null
                } else {
                    pendingSplitRange = parsed
                    createSplitPdf.launch(defaultOutputName(uri, "pdf").substringBeforeLast(".pdf") + "-split.pdf")
                }
            }
            .setNegativeButton(R.string.about_close) { _, _ -> pendingSinglePdf = null }
            .show()
    }

    private fun parsePageRange(text: String, count: Int): IntRange? {
        val t = text.trim()
        val parts = t.split("-")
        val start = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val end = if (parts.size > 1) parts[1].trim().toIntOrNull() ?: return null else start
        if (start < 1 || end < start || end > count) return null
        return (start - 1)..(end - 1)
    }

    private fun rasterPdfRange(input: Uri, output: Uri, start: Int, end: Int): Boolean = runCatching {
        val pdf = PdfDocument()
        var outPage = 1
        contentResolver.openFileDescriptor(input, "r")?.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val safeEnd = minOf(end, renderer.pageCount - 1)
                for (i in start..safeEnd) {
                    val bitmap = renderPdfPage(renderer, i)
                    addBitmapPage(pdf, bitmap, outPage++)
                    bitmap.recycle()
                }
            }
        }
        val ok = outPage > 1 && contentResolver.openOutputStream(output)?.use {
            pdf.writeTo(it)
            true
        } == true
        pdf.close()
        ok
    }.getOrDefault(false)

    private fun exportPdfPagesToImages(uri: Uri) {
        val count = pdfPageCount(uri)
        if (count <= 0) {
            Toast.makeText(this, R.string.tool_failed, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pdf_to_images)
            .setMessage(getString(R.string.pdf_to_images_help, count))
            .setPositiveButton(R.string.continue_label) { _, _ ->
                exportPdfPagesToPictures(uri)
            }
            .setNegativeButton(R.string.about_close, null)
            .show()
    }

    private fun exportPdfPagesToPictures(uri: Uri) {
        val valuesBase = ContentValues().apply {
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ANYFILE")
        }
        var saved = 0
        runCatching {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    for (i in 0 until renderer.pageCount) {
                        val bitmap = renderPdfPage(renderer, i)
                        val values = ContentValues(valuesBase).apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, "ANYFILE-page-${i + 1}.png")
                        }
                        val outUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        if (outUri != null) {
                            contentResolver.openOutputStream(outUri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            saved++
                        }
                        bitmap.recycle()
                    }
                }
            }
        }
        Toast.makeText(this, getString(R.string.pdf_images_saved, saved), Toast.LENGTH_LONG).show()
    }

    private fun compressImage(input: Uri, output: Uri): Boolean = runCatching {
        val bitmap = decodeBitmap(input) ?: return@runCatching false
        val maxSide = 1920
        val scale = minOf(1f, maxSide.toFloat() / maxOf(bitmap.width, bitmap.height))
        val target = if (scale < 1f) Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        ) else bitmap
        val ok = contentResolver.openOutputStream(output)?.use {
            target.compress(Bitmap.CompressFormat.JPEG, 72, it)
        } ?: false
        if (target !== bitmap) target.recycle()
        bitmap.recycle()
        ok
    }.getOrDefault(false)

    private fun showResizeDialog(uri: Uri) {
        val bitmap = decodeBitmap(uri)
        if (bitmap == null) {
            Toast.makeText(this, R.string.tool_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val originalW = bitmap.width
        val originalH = bitmap.height
        bitmap.recycle()

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
        }
        val widthInput = android.widget.EditText(this).apply {
            hint = getString(R.string.width)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(originalW.toString())
        }
        val heightInput = android.widget.EditText(this).apply {
            hint = getString(R.string.height)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(originalH.toString())
        }
        box.addView(widthInput)
        box.addView(heightInput)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.resize_image)
            .setMessage(getString(R.string.original_size, originalW, originalH))
            .setView(box)
            .setPositiveButton(R.string.continue_label) { _, _ ->
                val w = widthInput.text?.toString()?.toIntOrNull() ?: 0
                val h = heightInput.text?.toString()?.toIntOrNull() ?: 0
                if (w !in 1..12000 || h !in 1..12000) {
                    Toast.makeText(this, R.string.invalid_dimensions, Toast.LENGTH_SHORT).show()
                } else {
                    pendingImageUri = uri
                    pendingResizeWidth = w
                    pendingResizeHeight = h
                    createResizedImage.launch(defaultOutputName(uri, "jpg").substringBeforeLast(".jpg") + "-resized.jpg")
                }
            }
            .setNegativeButton(R.string.about_close, null)
            .show()
    }

    private fun resizeImage(input: Uri, output: Uri, width: Int, height: Int): Boolean = runCatching {
        val bitmap = decodeBitmap(input) ?: return@runCatching false
        val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val ok = contentResolver.openOutputStream(output)?.use {
            resized.compress(Bitmap.CompressFormat.JPEG, 90, it)
        } ?: false
        resized.recycle()
        bitmap.recycle()
        ok
    }.getOrDefault(false)

    private fun showManageFileDialog(uri: Uri) {
        val options = arrayOf(
            getString(R.string.rename_file),
            getString(R.string.share_file),
            getString(R.string.delete_file)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(displayName(uri))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameDialog(uri)
                    1 -> shareUri(uri)
                    2 -> confirmDelete(uri)
                }
            }
            .setNegativeButton(R.string.about_close, null)
            .show()
    }

    private fun showRenameDialog(uri: Uri) {
        val input = android.widget.EditText(this).apply {
            setText(displayName(uri))
            setSingleLine(true)
            selectAll()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_file)
            .setView(input)
            .setPositiveButton(R.string.rename) { _, _ ->
                val newName = input.text?.toString()?.trim().orEmpty()
                val renamed = if (newName.isNotBlank()) runCatching {
                    DocumentsContract.renameDocument(contentResolver, uri, newName)
                }.getOrNull() else null
                Toast.makeText(this, if (renamed != null) R.string.rename_done else R.string.rename_failed, Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton(R.string.about_close, null)
            .show()
    }

    private fun shareUri(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = contentResolver.getType(uri) ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, getString(R.string.share_file))) }
            .onFailure { Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show() }
    }

    private fun confirmDelete(uri: Uri) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_file)
            .setMessage(R.string.delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                val deleted = runCatching {
                    DocumentsContract.deleteDocument(contentResolver, uri)
                }.getOrDefault(false)
                Toast.makeText(this, if (deleted) R.string.delete_done else R.string.delete_failed, Toast.LENGTH_SHORT).show()
                if (deleted) {
                    Recents.remove(this, uri.toString())
                    Thumbs.evict(this, uri.toString())
                    render()
                }
            }
            .setNegativeButton(R.string.about_close, null)
            .show()
    }

    private fun createZip(inputs: List<Uri>, output: Uri): Boolean = runCatching {
        contentResolver.openOutputStream(output)?.use { raw ->
            ZipOutputStream(raw).use { zip ->
                val used = mutableSetOf<String>()
                inputs.forEachIndexed { index, uri ->
                    var name = displayName(uri).replace("/", "_")
                    if (name.isBlank()) name = "file-${index + 1}"
                    var unique = name
                    var n = 2
                    while (!used.add(unique)) {
                        unique = "${name.substringBeforeLast('.', name)}-$n" +
                            if (name.contains('.')) ".${name.substringAfterLast('.')}" else ""
                        n++
                    }
                    zip.putNextEntry(ZipEntry(unique))
                    contentResolver.openInputStream(uri)?.use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            true
        } ?: false
    }.getOrDefault(false)

    private fun safeZipName(name: String): String? {
        val clean = name.replace('\\', '/').trimStart('/')
        if (clean.isBlank() || clean.contains("../") || clean == "..") return null
        return clean
    }

    private fun extractZip(zipUri: Uri, treeUri: Uri): Boolean = runCatching {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val dirs = mutableMapOf<String, String>("" to rootId)

        fun ensureDir(path: String): String {
            dirs[path]?.let { return it }
            val parentPath = path.substringBeforeLast('/', "")
            val name = path.substringAfterLast('/')
            val parentId = ensureDir(parentPath)
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
            val created = DocumentsContract.createDocument(
                contentResolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, name
            ) ?: error("Could not create folder")
            val id = DocumentsContract.getDocumentId(created)
            dirs[path] = id
            return id
        }

        contentResolver.openInputStream(zipUri)?.use { raw ->
            ZipInputStream(raw).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    val safe = safeZipName(entry.name) ?: continue
                    val normalized = safe.trimEnd('/')
                    if (normalized.isBlank()) continue
                    if (entry.isDirectory) {
                        ensureDir(normalized)
                    } else {
                        val parentPath = normalized.substringBeforeLast('/', "")
                        val fileName = normalized.substringAfterLast('/')
                        val parentId = ensureDir(parentPath)
                        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
                        val mime = android.webkit.MimeTypeMap.getSingleton()
                            .getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase())
                            ?: "application/octet-stream"
                        val created = DocumentsContract.createDocument(
                            contentResolver, parentUri, mime, fileName
                        ) ?: error("Could not create file")
                        contentResolver.openOutputStream(created)?.use { out -> zin.copyTo(out) }
                    }
                    zin.closeEntry()
                }
            }
        } ?: return@runCatching false
        true
    }.getOrDefault(false)

    private fun imagesToPdf(inputs: List<Uri>, output: Uri): Boolean = runCatching {
        val pdf = PdfDocument()
        val pageWidth = 1240
        val pageHeight = 1754
        val margin = 56f
        var pageNumber = 1
        for (uri in inputs) {
            val bitmap = decodeBitmap(uri) ?: continue
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber++).create())
            val maxW = pageWidth - margin * 2
            val maxH = pageHeight - margin * 2
            val scale = minOf(maxW / bitmap.width, maxH / bitmap.height)
            val w = bitmap.width * scale
            val h = bitmap.height * scale
            val left = (pageWidth - w) / 2f
            val top = (pageHeight - h) / 2f
            page.canvas.drawBitmap(bitmap, null, android.graphics.RectF(left, top, left + w, top + h), Paint(Paint.ANTI_ALIAS_FLAG))
            pdf.finishPage(page)
        }
        val ok = contentResolver.openOutputStream(output)?.use {
            pdf.writeTo(it)
            true
        } ?: false
        pdf.close()
        ok
    }.getOrDefault(false)

    private fun launchTool(action: ToolAction, mimeTypes: Array<String>) {
        pendingTool = action
        pickToolFile.launch(mimeTypes)
    }

    private fun handleToolFile(uri: Uri) {
        when (pendingTool) {
            ToolAction.FILE_INFO -> {
                showFileInfo(uri)
                pendingTool = null
            }
            ToolAction.EXTRACT_ZIP -> {
                pendingZipUri = uri
                pickExtractFolder.launch(null)
            }
            ToolAction.UNKNOWN_FILE -> {
                showUnknownFile(uri)
                pendingTool = null
            }
            ToolAction.IMAGE_TO_JPG -> {
                pendingInputUri = uri
                createOutput.launch(defaultOutputName(uri, "jpg"))
            }
            ToolAction.IMAGE_TO_PNG -> {
                pendingInputUri = uri
                createOutput.launch(defaultOutputName(uri, "png"))
            }
            ToolAction.IMAGE_TO_PDF -> {
                pendingInputUri = uri
                createOutput.launch(defaultOutputName(uri, "pdf"))
            }
            ToolAction.TEXT_TO_PDF -> {
                pendingInputUri = uri
                createOutput.launch(defaultOutputName(uri, "pdf"))
            }
            null -> Unit
        }
    }

    private fun displayName(uri: Uri): String {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "file"
    }

    private fun fileSize(uri: Uri): Long {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            } ?: 0L
        }.getOrDefault(0L)
    }

    private fun defaultOutputName(uri: Uri, ext: String): String {
        val n = displayName(uri)
        return n.substringBeforeLast('.', n) + "." + ext
    }

    private fun decodeBitmap(uri: Uri): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }
    }.getOrNull()

    private fun convertImage(input: Uri, output: Uri, format: Bitmap.CompressFormat): Boolean = runCatching {
        val bitmap = decodeBitmap(input) ?: return@runCatching false
        contentResolver.openOutputStream(output)?.use { out ->
            bitmap.compress(format, if (format == Bitmap.CompressFormat.JPEG) 92 else 100, out)
        } ?: false
    }.getOrDefault(false)

    private fun imageToPdf(input: Uri, output: Uri): Boolean = runCatching {
        val bitmap = decodeBitmap(input) ?: return@runCatching false
        val pdf = PdfDocument()
        val pageWidth = 1240
        val pageHeight = 1754
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create())
        val margin = 56f
        val maxW = pageWidth - margin * 2
        val maxH = pageHeight - margin * 2
        val scale = minOf(maxW / bitmap.width, maxH / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        val left = (pageWidth - w) / 2f
        val top = (pageHeight - h) / 2f
        val dst = android.graphics.RectF(left, top, left + w, top + h)
        page.canvas.drawBitmap(bitmap, null, dst, Paint(Paint.ANTI_ALIAS_FLAG))
        pdf.finishPage(page)
        val ok = contentResolver.openOutputStream(output)?.use {
            pdf.writeTo(it)
            true
        } ?: false
        pdf.close()
        ok
    }.getOrDefault(false)

    private fun textToPdf(input: Uri, output: Uri): Boolean = runCatching {
        val lines = contentResolver.openInputStream(input)?.use { stream ->
            BufferedReader(InputStreamReader(stream)).readLines()
        } ?: return@runCatching false

        val pdf = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 28f
        }
        val pageWidth = 1240
        val pageHeight = 1754
        val left = 72f
        val right = 72f
        val top = 90f
        val bottom = 90f
        val lineHeight = 42f
        val maxWidth = pageWidth - left - right

        fun wrap(text: String): List<String> {
            if (text.isEmpty()) return listOf("")
            val words = text.split(Regex("\\s+"))
            val out = mutableListOf<String>()
            var line = ""
            for (word in words) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (paint.measureText(candidate) <= maxWidth) line = candidate
                else {
                    if (line.isNotEmpty()) out += line
                    line = word
                }
            }
            if (line.isNotEmpty()) out += line
            return out.ifEmpty { listOf("") }
        }

        var pageNumber = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        var y = top
        for (sourceLine in lines) {
            for (line in wrap(sourceLine)) {
                if (y > pageHeight - bottom) {
                    pdf.finishPage(page)
                    pageNumber++
                    page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
                    y = top
                }
                page.canvas.drawText(line, left, y, paint)
                y += lineHeight
            }
        }
        pdf.finishPage(page)
        val ok = contentResolver.openOutputStream(output)?.use {
            pdf.writeTo(it)
            true
        } ?: false
        pdf.close()
        ok
    }.getOrDefault(false)

    private fun showUnknownFile(uri: Uri) {
        val name = displayName(uri)
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        val mime = contentResolver.getType(uri)
            ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"

        val category = when {
            mime == "application/pdf" -> "PDF document"
            mime.startsWith("image/") -> "Image"
            mime.startsWith("video/") -> "Video"
            mime.startsWith("audio/") -> "Audio"
            mime.startsWith("text/") -> "Text / code"
            mime.contains("word") || ext == "docx" || ext == "doc" -> "Word document"
            mime.contains("sheet") || ext in setOf("xlsx", "xls", "csv", "ods") -> "Spreadsheet"
            mime.contains("presentation") || ext in setOf("pptx", "ppt") -> "Presentation"
            ext == "zip" -> "ZIP archive"
            else -> "Unknown / binary file"
        }

        val size = fileSize(uri)
        val message = buildString {
            append(getString(R.string.unknown_name)).append(": ").append(name).append("\n\n")
            append(getString(R.string.unknown_extension)).append(": ")
                .append(if (ext.isBlank()) getString(R.string.unknown_none) else ".$ext").append("\n\n")
            append(getString(R.string.unknown_category)).append(": ").append(category).append("\n\n")
            append("MIME: ").append(mime).append("\n\n")
            append(getString(R.string.file_info_size)).append(": ")
                .append(if (size > 0) Formatter.formatShortFileSize(this@MainActivity, size) else getString(R.string.unknown_size))
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.open_unknown_file)
            .setMessage(message)
            .setPositiveButton(R.string.try_to_open) { _, _ -> openInViewer(uri) }
            .setNeutralButton(R.string.file_info) { _, _ -> showFileInfo(uri) }
            .setNegativeButton(R.string.about_close, null)
            .show()
    }

    private fun showFileInfo(uri: Uri) {
        val name = displayName(uri)
        val type = contentResolver.getType(uri) ?: getString(R.string.unknown_type)
        val size = fileSize(uri)
        val text = buildString {
            append(getString(R.string.file_info_name)).append(": ").append(name).append("\n\n")
            append(getString(R.string.file_info_type)).append(": ").append(type).append("\n\n")
            append(getString(R.string.file_info_size)).append(": ")
            append(if (size > 0) Formatter.formatShortFileSize(this@MainActivity, size) else getString(R.string.unknown_size))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.file_info)
            .setMessage(text)
            .setPositiveButton(R.string.about_close, null)
            .show()
    }

    private fun favoritesPrefs() = getSharedPreferences("favorites", Context.MODE_PRIVATE)

    private fun favoriteMap(): Map<String, String> =
        favoritesPrefs().all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    private fun isFavorite(uri: String): Boolean = favoritesPrefs().contains(uri)

    private fun toggleFavorite(uri: String, name: String) {
        val e = favoritesPrefs().edit()
        if (isFavorite(uri)) e.remove(uri) else e.putString(uri, name)
        e.apply()
        Toast.makeText(
            this,
            if (isFavorite(uri)) R.string.favorite_added else R.string.favorite_removed,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showRecentOptions(uri: String, name: String) {
        val favoriteLabel = if (isFavorite(uri)) getString(R.string.remove_favorite) else getString(R.string.add_favorite)
        val options = arrayOf(favoriteLabel, getString(R.string.remove_from_recents))
        MaterialAlertDialogBuilder(this)
            .setTitle(name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> toggleFavorite(uri, name)
                    1 -> {
                        Recents.remove(this, uri)
                        Thumbs.evict(this, uri)
                        Toast.makeText(this, R.string.removed, Toast.LENGTH_SHORT).show()
                        render()
                    }
                }
            }
            .show()
    }

    private fun showFavorites() {
        val items = favoriteMap().entries.toList()
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.no_favorites, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.favorites)
            .setItems(items.map { it.value }.toTypedArray()) { _, which ->
                openInViewer(Uri.parse(items[which].key))
            }
            .setNegativeButton(R.string.about_close, null)
            .show()
    }

    private data class SearchHit(val name: String, val uri: Uri)

    private fun showSearchDialog() {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.search_hint)
            setSingleLine(true)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.search_files)
            .setView(input)
            .setPositiveButton(R.string.search) { _, _ ->
                val q = input.text?.toString().orEmpty().trim()
                if (q.isNotEmpty()) searchFiles(q)
            }
            .setNegativeButton(R.string.about_close, null)
            .show()
    }

    private fun searchFiles(query: String) {
        val roots = contentResolver.persistedUriPermissions
            .filter { it.isReadPermission && isTreeUri(it.uri) }
            .map { it.uri }

        if (roots.isEmpty()) {
            Toast.makeText(this, R.string.search_needs_folder, Toast.LENGTH_SHORT).show()
            return
        }

        val hits = mutableListOf<SearchHit>()
        roots.forEach { root ->
            val rootId = runCatching { DocumentsContract.getTreeDocumentId(root) }.getOrNull() ?: return@forEach
            searchTree(root, rootId, query, hits, 120)
        }

        if (hits.isEmpty()) {
            Toast.makeText(this, R.string.no_search_results, Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.search_results, hits.size))
            .setItems(hits.map { it.name }.toTypedArray()) { _, which -> openInViewer(hits[which].uri) }
            .setNegativeButton(R.string.about_close, null)
            .show()
    }

    private fun searchTree(treeUri: Uri, parentId: String, query: String, out: MutableList<SearchHit>, limit: Int) {
        if (out.size >= limit) return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        runCatching {
            contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    if (name.startsWith(".")) continue
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        searchTree(treeUri, id, query, out, limit)
                    } else if (name.contains(query, ignoreCase = true)) {
                        out += SearchHit(name, DocumentsContract.buildDocumentUriUsingTree(treeUri, id))
                    }
                }
            }
        }
    }

    private fun relativeTimeEnglish(time: Long): String {
        val diff = (System.currentTimeMillis() - time).coerceAtLeast(0L)
        val minute = 60_000L
        val hour = 60 * minute
        val day = 24 * hour
        return when {
            diff < minute -> "Just now"
            diff < hour -> "${diff / minute} min ago"
            diff < day -> "${diff / hour} hr ago"
            diff < 2 * day -> "Yesterday"
            diff < 7 * day -> "${diff / day} days ago"
            else -> java.text.SimpleDateFormat("MMM d, yyyy", Locale.US).format(java.util.Date(time))
        }
    }

    private fun isTreeUri(uri: Uri): Boolean =
        runCatching { DocumentsContract.getTreeDocumentId(uri) }.isSuccess &&
            uri.pathSegments.firstOrNull() == "tree"

    private fun treeLabel(uri: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return "Folder"
        val name = runCatching {
            contentResolver.query(
                DocumentsContract.buildDocumentUriUsingTree(uri, id),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        return name ?: id.substringAfterLast(':').ifEmpty { id }
    }

    private fun badgeFor(name: String, mime: String?): Pair<String, Int> {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (FileKind.detect(ext, mime)) {
            FileKind.PDF -> "PDF" to 0xFFD32F2F.toInt()
            FileKind.DOCX -> "DOC" to 0xFF1565C0.toInt()
            FileKind.XLSX -> "XLS" to 0xFF2E7D32.toInt()
            FileKind.PPTX -> "PPT" to 0xFFE64A19.toInt()
            FileKind.IMAGE, FileKind.IMAGE_WEB -> "IMG" to 0xFF7B1FA2.toInt()
            FileKind.PLAYER ->
                if (FileKind.isAudioExt(ext)) "AUD" to 0xFF00838F.toInt()
                else "VID" to 0xFFAD1457.toInt()
            FileKind.MD -> "MD" to 0xFF455A64.toInt()
            FileKind.TEXT -> "TXT" to 0xFF616161.toInt()
            FileKind.UNSUPPORTED -> "FILE" to 0xFF78909C.toInt()
        }
    }

    private companion object {
        val DIR_COLOR = 0xFFF9A825.toInt()
        val ADD_COLOR = 0xFF1565C0.toInt()
        const val LICENCES_ASSET = "licences.md"
    }

    private class RowAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val rows = mutableListOf<Row>()

        fun submit(newRows: List<Row>) {
            rows.clear()
            rows.addAll(newRows)
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Header -> 0
            is Row.Hint -> 1
            is Row.Item -> 2
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val layout = when (viewType) {
                0 -> R.layout.row_header
                1 -> R.layout.row_hint
                else -> R.layout.row_item
            }
            return object : RecyclerView.ViewHolder(inflater.inflate(layout, parent, false)) {}
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header ->
                    holder.itemView.findViewById<TextView>(R.id.headerText).text = row.title
                is Row.Hint ->
                    holder.itemView.findViewById<TextView>(R.id.hintText).text = row.text
                is Row.Item -> {
                    val badge = holder.itemView.findViewById<TextView>(R.id.badge)
                    val thumb = holder.itemView.findViewById<ImageView>(R.id.thumb)
                    badge.text = row.badge
                    badge.background.mutate().setTint(row.color)
                    badge.visibility = View.VISIBLE
                    thumb.visibility = View.GONE
                    thumb.setImageDrawable(null)
                    thumb.tag = null
                    if (row.thumbUri != null) {
                        Thumbs.load(
                            holder.itemView.context, row.thumbUri, row.thumbExt, thumb, badge
                        )
                    }
                    holder.itemView.findViewById<TextView>(R.id.title).text = row.title
                    val sub = holder.itemView.findViewById<TextView>(R.id.subtitle)
                    sub.text = row.subtitle
                    sub.visibility = if (row.subtitle == null) View.GONE else View.VISIBLE
                    // The row children are not-important for accessibility, so this is
                    // the whole announcement. Keeping the badge in it matters: the badge
                    // is hidden once a thumbnail loads, and the file type would go with it
                    holder.itemView.contentDescription =
                        listOfNotNull(row.title, row.badge, row.subtitle).joinToString(", ")
                    holder.itemView.setOnClickListener { row.onClick() }
                    holder.itemView.setOnLongClickListener {
                        row.onLongClick?.invoke()
                        row.onLongClick != null
                    }
                }
            }
        }
    }
}
