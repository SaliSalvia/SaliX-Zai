package com.salixz.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Mobile preview system for files produced by the models.
 *
 * The panel is a draggable bottom sheet in two sizes (62% / 94% of the
 * content area) over a dimmed scrim. "جمع کردن" hides it into a small pill so
 * the user can keep chatting; tapping the pill restores the preview untouched.
 *
 * Cached files are served through [WebViewAssetLoader] on a virtual https
 * origin, so images never travel through base64 + evaluateJavascript.
 */
class PreviewController(private val activity: MainActivity) {

    enum class Kind { CODE, HTML, MARKDOWN, IMAGE }

    data class Payload(
        var name: String,
        var lang: String,
        var kind: Kind,
        var content: String,   // code/html/md text, or the image URL for Kind.IMAGE
        var mime: String,
        var file: File?        // backing file for downloaded payloads
    )

    private val panel: LinearLayout = activity.findViewById(R.id.previewPanel)
    private val dragBar: View = activity.findViewById(R.id.previewDragBar)
    private val header: View = activity.findViewById(R.id.previewHeader)
    private val nameView: TextView = activity.findViewById(R.id.previewName)
    private val langView: TextView = activity.findViewById(R.id.previewLang)
    private var previewWeb: WebView = activity.findViewById(R.id.previewWeb)
    private val previewLoading: ProgressBar = activity.findViewById(R.id.previewLoading)
    private val pill: LinearLayout = activity.findViewById(R.id.pill)
    private val pillLabel: TextView = activity.findViewById(R.id.pillLabel)
    private val scrim: View = activity.findViewById(R.id.previewScrim)
    private val btnExpand: ImageButton = activity.findViewById(R.id.btnExpand)

    private var payload: Payload? = null
    private var expanded = false
    private var viewerLoaded = false
    private val executor = Executors.newSingleThreadExecutor()

    val isVisible: Boolean get() = panel.visibility == View.VISIBLE
    val pillVisible: Boolean get() = pill.visibility == View.VISIBLE

    private val previewDir: File
        get() = File(activity.cacheDir, PREVIEW_DIR).apply { mkdirs() }

    private val assetLoader: WebViewAssetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler(
                "/$PREVIEW_DIR/",
                WebViewAssetLoader.InternalStoragePathHandler(activity, previewDir)
            )
            .build()
    }

    init {
        panel.visibility = View.GONE
        scrim.visibility = View.GONE
        expanded = Prefs.read(activity, Prefs.KEY_PANEL_EXPANDED, false)
        setupPreviewWeb()
        wirePanel()
    }

    // ── Public entry points ───────────────────────────────────────────

    /** "پیش‌نمایش" pressed on a code block inside the chat. */
    fun showCode(code: String, langIn: String) {
        val lang = normalizeLang(langIn)
        val kind = kindFor(lang)
        val name = suggestName(lang, kind)
        payload = Payload(name, lang, kind, code, mimeFor(lang, kind), null)
        openPanel()
        ensureViewerLoaded()
    }

    /** Any download link the site fires (DownloadListener or the JS bridge). */
    fun handleDownload(url: String, userAgent: String, mime: String, disposition: String) {
        if (url.startsWith("blob:", true)) {
            fetchBlob(url, mime, guessFileName(disposition, url, mime))
        } else {
            downloadAndPreview(url, userAgent, mime, disposition)
        }
    }

    /** A file download started from the site (export, artifact, image…). */
    fun downloadAndPreview(url: String, userAgent: String, mime: String, disposition: String) {
        val displayName = sanitize(guessFileName(disposition, url, mime))
        activity.toast(activity.getString(R.string.download_started))
        executor.execute {
            var target: File? = null
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.let {
                    conn.setRequestProperty("Cookie", it)
                }
                if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
                if (conn.contentLengthLong > MAX_DOWNLOAD_BYTES) {
                    throw IllegalStateException(activity.getString(R.string.download_too_large))
                }
                val realMime = conn.contentType?.substringBefore(';')?.trim()?.ifEmpty { mime } ?: mime
                pruneCache()
                val file = File(previewDir, storedName(extOf(displayName, realMime)))
                target = file
                var written = 0L
                conn.inputStream.use { input ->
                    file.outputStream().use { sink ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            written += read
                            if (written > MAX_DOWNLOAD_BYTES) {
                                throw IllegalStateException(activity.getString(R.string.download_too_large))
                            }
                            sink.write(buffer, 0, read)
                        }
                    }
                }
                activity.runOnUiThread { showDownloaded(file, realMime, displayName) }
            } catch (t: Throwable) {
                target?.delete()
                activity.runOnUiThread {
                    activity.toast(activity.getString(R.string.download_failed) + ": " + (t.message ?: ""))
                }
            }
        }
    }

    /** `blob:` downloads cannot be fetched by the app; ask the page for the bytes. */
    private fun fetchBlob(url: String, mime: String, fileName: String) {
        val safe = sanitize(fileName.ifEmpty { "download.bin" })
        val js = """
            (function () {
              try {
                fetch(${JSONObject.quote(url)})
                  .then(function (r) { return r.blob(); })
                  .then(function (b) {
                    if (b.size > $MAX_BLOB_BYTES) {
                      SaliXBridge.toast('فایل برای دانلود بیش از حد بزرگ است');
                      return;
                    }
                    var fr = new FileReader();
                    fr.onload = function () {
                      var s = String(fr.result), i = s.indexOf(',');
                      SaliXBridge.onBlob(${JSONObject.quote(safe)}, ${JSONObject.quote(mime)}, s.substring(i + 1));
                    };
                    fr.readAsDataURL(b);
                  })
                  .catch(function () { SaliXBridge.toast('دانلود این فایل ممکن نشد'); });
              } catch (e) { }
            })();
        """.trimIndent()
        activity.web.evaluateJavascript(js, null)
    }

    /** Bytes of a blob download, decoded on the JS bridge thread. */
    fun onBlobReceived(name: String, mime: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        if (bytes.size > MAX_BLOB_BYTES) {
            activity.toast(activity.getString(R.string.download_too_large))
            return
        }
        executor.execute {
            try {
                val file = File(previewDir, storedName(extOf(name, mime)))
                file.writeBytes(bytes)
                activity.runOnUiThread { showDownloaded(file, mime, sanitize(name)) }
            } catch (t: Throwable) {
                activity.runOnUiThread {
                    activity.toast(activity.getString(R.string.download_failed) + ": " + (t.message ?: ""))
                }
            }
        }
    }

    /** Downloaded file finished; classify and preview or open externally. */
    fun showDownloaded(file: File, mime: String, displayName: String) {
        val name = displayName.ifBlank { file.name }
        val ext = extOf(name, mime)
        when {
            mime.startsWith("image/") || ext in IMAGE_EXTS -> {
                payload = Payload(name, ext.ifEmpty { "image" }, Kind.IMAGE,
                    imageUrl(file), mime.ifEmpty { "image/*" }, file)
                openPanel()
                ensureViewerLoaded()
            }
            isHtmlFile(ext) || mime.equals("text/html", true) -> {
                payload = Payload(name, "html", Kind.HTML, readCapped(file), "text/html", file)
                openPanel()
                ensureViewerLoaded()
            }
            ext in setOf("md", "markdown") || mime.equals("text/markdown", true) -> {
                payload = Payload(name, "md", Kind.MARKDOWN, readCapped(file), "text/markdown", file)
                openPanel()
                ensureViewerLoaded()
            }
            mime.equals("application/pdf", true) || ext == "pdf" -> openExternally(file, "application/pdf")
            isTextual(ext, mime) -> {
                payload = Payload(name, langForExt(ext), Kind.CODE, readCapped(file),
                    mime.ifEmpty { "text/plain" }, file)
                openPanel()
                ensureViewerLoaded()
            }
            else -> openExternally(file, mime)
        }
    }

    fun copyToClipboard(text: String) {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("SaliX-Z", text))
        activity.toast(activity.getString(R.string.copied))
    }

    // ── Panel lifecycle ───────────────────────────────────────────────

    private fun openPanel() {
        pill.visibility = View.GONE
        if (isVisible) {
            applyHeight()
            nameView.text = payload?.name.orEmpty()
            langView.text = payload?.lang.orEmpty()
            return
        }
        panel.visibility = View.VISIBLE
        scrim.animate().cancel()
        scrim.visibility = View.VISIBLE
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(200).start()
        panel.post {
            applyHeight()
            nameView.text = payload?.name.orEmpty()
            langView.text = payload?.lang.orEmpty()
            btnExpand.setImageResource(if (expanded) R.drawable.ic_collapse else R.drawable.ic_expand)
            panel.translationY = panel.height.toFloat()
            panel.animate()
                .translationY(0f)
                .setDuration(260)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    fun minimize() {
        if (!isVisible) return
        scrim.animate().alpha(0f).setDuration(180)
            .withEndAction { scrim.visibility = View.GONE }
            .start()
        panel.animate()
            .translationY(panel.height.toFloat())
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                panel.visibility = View.GONE
                pillLabel.text = payload?.name ?: activity.getString(R.string.preview_pill)
                pill.visibility = View.VISIBLE
                pill.alpha = 0f
                pill.animate().alpha(1f).setDuration(180).start()
            }
            .start()
    }

    fun restore() {
        if (payload == null) return
        openPanel()
    }

    fun close() {
        scrim.animate().cancel()
        scrim.visibility = View.GONE
        panel.animate().cancel()
        panel.visibility = View.GONE
        pill.visibility = View.GONE
        payload = null
        viewerLoaded = false
        previewWeb.loadUrl("about:blank")
        previewLoading.visibility = View.GONE
    }

    fun destroy() {
        executor.shutdownNow()
        try {
            previewWeb.stopLoading()
            previewWeb.destroy()
        } catch (_: Throwable) {
        }
    }

    fun onConfigurationChanged() {
        if (isVisible) panel.post { applyHeight() }
    }

    private fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        Prefs.write(activity, Prefs.KEY_PANEL_EXPANDED, expanded)
        btnExpand.setImageResource(if (expanded) R.drawable.ic_collapse else R.drawable.ic_expand)
        applyHeight()
    }

    private fun applyHeight() {
        val parentHeight = (panel.parent as? View)?.height ?: 0
        if (parentHeight <= 0) return
        val fraction = if (expanded) 0.94f else 0.62f
        val height = (parentHeight * fraction).toInt()
        panel.layoutParams = panel.layoutParams.also { it.height = height }
    }

    // ── Viewer plumbing ───────────────────────────────────────────────

    private fun setupPreviewWeb() {
        previewWeb.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = false
        }
        previewWeb.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView, url: String) {
                if (url.startsWith(VIEWER_BASE)) {
                    viewerLoaded = true
                    previewLoading.visibility = View.GONE
                    pushPayload()
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val scheme = (u.scheme ?: "").lowercase()
                if (scheme == "http" || scheme == "https") {
                    // Local preview assets stay inside; real links leave the viewer.
                    if (u.host == ASSET_HOST) return false
                    activity.openLinkExternally(u)
                    return true
                }
                return true
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // The viewer renderer died; rebuild it so the next preview still works.
                activity.runOnUiThread { rebuildViewer() }
                return true
            }
        }
    }

    private fun rebuildViewer() {
        val parent = previewWeb.parent as? ViewGroup
        if (parent == null) {
            close()
            return
        }
        val index = parent.indexOfChild(previewWeb)
        val params = previewWeb.layoutParams
        try {
            previewWeb.destroy()
        } catch (_: Throwable) {
        }
        val fresh = WebView(activity).apply { id = R.id.previewWeb }
        parent.addView(fresh, index, params)
        previewWeb = fresh
        viewerLoaded = false
        setupPreviewWeb()
        if (payload != null) ensureViewerLoaded()
    }

    private fun ensureViewerLoaded() {
        if (viewerLoaded) {
            previewLoading.visibility = View.GONE
            pushPayload()
            return
        }
        previewLoading.visibility = View.VISIBLE
        try {
            val html = activity.assets.open("viewer.html").use { it.readBytes().toString(Charsets.UTF_8) }
            previewWeb.loadDataWithBaseURL(VIEWER_BASE, html, "text/html", "utf-8", null)
        } catch (t: Throwable) {
            previewLoading.visibility = View.GONE
            activity.toast(activity.getString(R.string.preview_failed))
        }
    }

    private fun pushPayload() {
        val p = payload ?: return
        val render = if (p.kind == Kind.IMAGE) p.content else p.content.take(MAX_RENDER_CHARS)
        val truncated = p.kind != Kind.IMAGE && p.content.length > render.length
        val json = JSONObject()
            .put("mode", when (p.kind) {
                Kind.CODE -> "code"; Kind.HTML -> "html"
                Kind.MARKDOWN -> "md"; Kind.IMAGE -> "image"
            })
            .put("lang", p.lang)
            .put("name", p.name)
            .put("content", render)
            .put("truncated", truncated)
        previewWeb.evaluateJavascript("window.SaliXViewer && SaliXViewer.setContent($json);", null)
    }

    // ── Panel wiring ──────────────────────────────────────────────────

    private fun wirePanel() {
        btnExpand.setOnClickListener { setExpanded(!expanded) }
        panel.findViewById<ImageButton>(R.id.btnMinimize).setOnClickListener { minimize() }
        panel.findViewById<ImageButton>(R.id.btnClose).setOnClickListener { close() }

        attachDrag(dragBar)
        attachDrag(header)

        panel.findViewById<TextView>(R.id.chipCopy).setOnClickListener { copyPayload() }
        panel.findViewById<TextView>(R.id.chipSave).setOnClickListener { saveToDownloads() }
        panel.findViewById<TextView>(R.id.chipShare).setOnClickListener { share() }
        panel.findViewById<TextView>(R.id.chipExternal).setOnClickListener {
            val p = payload ?: return@setOnClickListener
            val file = p.file?.takeIf { it.exists() }
                ?: if (p.kind == Kind.IMAGE) null else materialize(p)
            if (file != null) openExternally(file, p.mime)
        }

        pill.setOnClickListener { restore() }
        activity.findViewById<View>(R.id.pillClose).setOnClickListener { close() }
        scrim.setOnClickListener { minimize() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(v: View) {
        v.setOnTouchListener(object : View.OnTouchListener {
            private var startY = 0f
            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startY = event.rawY
                        panel.animate().cancel()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = event.rawY - startY
                        panel.translationY = if (dy > 0f) dy else dy * 0.35f
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val dy = panel.translationY
                        when {
                            dy > panel.height * 0.28f -> minimize()
                            dy < -48f && !expanded -> {
                                panel.translationY = 0f
                                setExpanded(true)
                            }
                            else -> panel.animate().translationY(0f).setDuration(160).start()
                        }
                        view.performClick()
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun copyPayload() {
        val p = payload ?: return
        if (p.kind == Kind.IMAGE) {
            activity.toast(activity.getString(R.string.preview_image_copy_hint))
            return
        }
        copyToClipboard(p.content)
    }

    // ── Save / share / open ───────────────────────────────────────────

    private fun saveToDownloads() {
        val p = payload ?: return
        activity.withDownloadsAccess { startSave(p) }
    }

    private fun startSave(p: Payload) {
        executor.execute {
            try {
                val backing = p.file
                val bytes: ByteArray = when {
                    p.kind == Kind.IMAGE -> backing?.takeIf { it.exists() }?.readBytes()
                        ?: throw IllegalStateException(activity.getString(R.string.preview_failed))
                    backing != null && backing.exists() -> backing.readBytes()
                    else -> p.content.toByteArray(Charsets.UTF_8)
                }
                writeToDownloads(sanitize(p.name), p.mime.ifEmpty { "application/octet-stream" }, bytes)
                activity.runOnUiThread { activity.toast(activity.getString(R.string.saved_to_downloads)) }
            } catch (t: Throwable) {
                activity.runOnUiThread {
                    activity.toast(activity.getString(R.string.save_failed) + ": " + (t.message ?: ""))
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun writeToDownloads(name: String, mime: String, bytes: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = activity.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("insert failed")
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("stream failed")
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }, null, null)
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("downloads unavailable")
            File(dir, name).writeBytes(bytes)
        }
    }

    private fun share() {
        val p = payload ?: return
        val file = shareFile(p)
        if (file != null && file.exists()) {
            try {
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = p.mime.ifEmpty { "application/octet-stream" }
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.share_via)))
            } catch (t: Throwable) {
                activity.toast(activity.getString(R.string.share_failed) + ": " + (t.message ?: ""))
            }
        } else if (p.kind != Kind.IMAGE) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, p.content)
            }
            activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.share_via)))
        }
    }

    /** A file whose on-disk name matches the display name, so receivers see it. */
    private fun shareFile(p: Payload): File? {
        val backing = p.file?.takeIf { it.exists() } ?: return if (p.kind == Kind.IMAGE) null else materialize(p)
        val desired = sanitize(p.name)
        if (backing.name == desired) return backing
        val dir = File(previewDir, "share").apply { mkdirs() }
        val target = File(dir, desired)
        if (!target.exists() || target.length() != backing.length()) {
            backing.copyTo(target, overwrite = true)
        }
        return target
    }

    private fun materialize(p: Payload): File {
        val file = File(previewDir, sanitize(p.name).ifEmpty { "preview.txt" })
        if (!file.exists() || file.length() == 0L) file.writeText(p.content)
        return file
    }

    private fun openExternally(file: File, mime: String) {
        try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime.ifEmpty { "*/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            activity.toast(activity.getString(R.string.no_app_for_file, file.name))
        } catch (t: Throwable) {
            activity.toast(activity.getString(R.string.open_failed, t.message ?: ""))
        }
    }

    // ── Storage helpers ───────────────────────────────────────────────

    private fun pruneCache() {
        val files = previewDir.listFiles { file -> file.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= MAX_CACHE_BYTES) break
            total -= file.length()
            file.delete()
        }
    }

    private fun readCapped(file: File, maxBytes: Int = MAX_TEXT_BYTES): String {
        if (file.length() <= maxBytes) return file.readText()
        val buffer = ByteArray(maxBytes)
        file.inputStream().use { stream ->
            var read = 0
            while (read < maxBytes) {
                val n = stream.read(buffer, read, maxBytes - read)
                if (n <= 0) break
                read += n
            }
            return String(buffer, 0, read, Charsets.UTF_8)
        }
    }

    private fun imageUrl(file: File): String = "$VIEWER_BASE$PREVIEW_DIR/${file.name}"

    private fun storedName(ext: String): String = "pv-${System.nanoTime()}.${ext.ifEmpty { "bin" }}"

    // ── Classification helpers ────────────────────────────────────────

    private fun normalizeLang(lang: String): String {
        val l = lang.trim().lowercase()
        return when (l) {
            "", "plaintext", "text", "txt", "none" -> "text"
            "js" -> "javascript"
            "ts" -> "typescript"
            "py" -> "python"
            "kt", "kts" -> "kotlin"
            "rs" -> "rust"
            "sh", "shell", "zsh" -> "bash"
            "yml" -> "yaml"
            "htm" -> "html"
            "c++", "cpp", "cxx" -> "cpp"
            "cs", "c#" -> "csharp"
            else -> l
        }
    }

    private fun kindFor(lang: String): Kind = when (lang) {
        "html", "svg" -> Kind.HTML
        "md", "markdown" -> Kind.MARKDOWN
        else -> Kind.CODE
    }

    private val extByLang = mapOf(
        "html" to ".html", "svg" to ".svg", "md" to ".md", "markdown" to ".md",
        "javascript" to ".js", "typescript" to ".ts", "python" to ".py",
        "kotlin" to ".kt", "java" to ".java", "json" to ".json", "xml" to ".xml",
        "css" to ".css", "scss" to ".scss", "sql" to ".sql", "bash" to ".sh",
        "c" to ".c", "cpp" to ".cpp", "csharp" to ".cs", "go" to ".go",
        "rust" to ".rs", "dart" to ".dart", "swift" to ".swift", "php" to ".php",
        "yaml" to ".yaml", "toml" to ".toml", "text" to ".txt"
    )

    private fun suggestName(lang: String, kind: Kind): String =
        "preview-" + counter.getAndIncrement() + (extByLang[lang] ?: when (kind) {
            Kind.HTML -> ".html"; Kind.MARKDOWN -> ".md"; Kind.IMAGE -> ".png"; Kind.CODE -> ".txt"
        })

    private fun mimeFor(lang: String, kind: Kind): String = when (kind) {
        Kind.HTML -> "text/html"
        Kind.MARKDOWN -> "text/markdown"
        Kind.IMAGE -> "image/png"
        Kind.CODE -> "text/plain"
    }

    private fun langForExt(ext: String): String = when (ext) {
        "js", "mjs" -> "javascript"; "ts" -> "typescript"; "py" -> "python"
        "kt", "kts" -> "kotlin"; "java" -> "java"; "json" -> "json"; "xml" -> "xml"
        "css" -> "css"; "scss" -> "scss"; "sql" -> "sql"; "sh" -> "bash"
        "c" -> "c"; "cpp" -> "cpp"; "cs" -> "csharp"; "go" -> "go"; "rs" -> "rust"
        "dart" -> "dart"; "swift" -> "swift"; "php" -> "php"; "yaml", "yml" -> "yaml"
        "toml" -> "toml"; else -> "text"
    }

    private fun isHtmlFile(ext: String) = ext == "html" || ext == "htm" || ext == "svg"

    private fun isTextual(ext: String, mime: String): Boolean =
        mime.startsWith("text/") || mime.startsWith("application/json") ||
            mime.startsWith("application/javascript") || mime.startsWith("application/xml") ||
            ext in setOf(
                "js", "mjs", "ts", "jsx", "tsx", "py", "kt", "kts", "java", "json", "xml",
                "css", "scss", "less", "sql", "sh", "bash", "c", "h", "cpp", "hpp", "cs",
                "go", "rs", "dart", "swift", "php", "yaml", "yml", "toml", "ini", "cfg",
                "txt", "log", "csv", "gradle", "properties", "md"
            )

    private fun extOf(name: String, mime: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty() && ext.length <= 5) return ext
        return when {
            mime.startsWith("image/") -> mime.substringAfter('/').replace("jpeg", "jpg")
            mime == "text/html" -> "html"
            mime == "application/pdf" -> "pdf"
            mime.startsWith("text/") -> "txt"
            else -> "bin"
        }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|\\u0000-\\u001F]"), "_").trim().take(120).ifEmpty { "file.bin" }

    companion object {
        private const val PREVIEW_DIR = "previews"
        private const val ASSET_HOST = "appassets.androidplatform.net"
        private const val VIEWER_BASE = "https://$ASSET_HOST/"
        private const val MAX_RENDER_CHARS = 400_000
        private const val MAX_TEXT_BYTES = 3_000_000
        private const val MAX_DOWNLOAD_BYTES = 64L * 1024 * 1024
        private const val MAX_BLOB_BYTES = 24L * 1024 * 1024
        private const val MAX_CACHE_BYTES = 80L * 1024 * 1024

        private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "avif")
        private val counter = AtomicInteger(1)

        fun guessFileName(disposition: String?, url: String, mime: String): String {
            val raw = disposition?.trim().orEmpty()
            if (raw.isNotEmpty()) {
                Regex("""filename\*=UTF-8''([^;]+)""").find(raw)?.groupValues?.get(1)?.let { value ->
                    val decoded = try {
                        URLDecoder.decode(value.trim('"', ' '), "UTF-8")
                    } catch (_: Throwable) {
                        value
                    }
                    return decoded
                }
                Regex("""filename="?([^";]+)"?""").find(raw)?.groupValues?.get(1)?.let { value ->
                    return value.trim()
                }
                // The JS bridge passes a bare file name rather than a header.
                if (!raw.contains('/') && raw.contains('.') && !raw.contains(';')) return raw
            }
            val path = try {
                URL(url).path
            } catch (_: Exception) {
                url
            }
            val last = path.substringAfterLast('/')
            if (last.isNotEmpty() && last.contains('.')) return last
            val ext = when {
                mime.startsWith("image/") -> mime.substringAfter('/').replace("jpeg", "jpg")
                mime == "text/html" -> "html"
                mime == "application/pdf" -> "pdf"
                mime.startsWith("text/") -> "txt"
                else -> "bin"
            }
            val stamp = System.currentTimeMillis() / 1000 % 100000
            return "download-$stamp.$ext"
        }
    }
}
