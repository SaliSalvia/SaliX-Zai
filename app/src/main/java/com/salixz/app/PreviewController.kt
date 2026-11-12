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
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Mobile-optimized preview system for files produced by the models.
 *
 * The panel is a draggable bottom sheet in two sizes (62% / 94% of the
 * content area). "جمع کردن" hides it into a small pill so the user can
 * keep chatting; tapping the pill restores the preview untouched.
 */
class PreviewController(private val activity: MainActivity) {

    enum class Kind { CODE, HTML, MARKDOWN, IMAGE }

    data class Payload(
        var name: String,
        var lang: String,
        var kind: Kind,
        var content: String,   // code/html/md text or a data: URI for images
        var mime: String,
        var file: File?        // backing file for downloaded payloads
    )

    private val panel: LinearLayout = activity.findViewById(R.id.previewPanel)
    private val header: LinearLayout = activity.findViewById(R.id.previewHeader)
    private val nameView: TextView = activity.findViewById(R.id.previewName)
    private val langView: TextView = activity.findViewById(R.id.previewLang)
    private val previewWeb: WebView = activity.findViewById(R.id.previewWeb)
    private val previewLoading: ProgressBar = activity.findViewById(R.id.previewLoading)
    private val pill: LinearLayout = activity.findViewById(R.id.pill)
    private val pillLabel: TextView = activity.findViewById(R.id.pillLabel)

    private var payload: Payload? = null
    private var expanded = false
    private var viewerLoaded = false
    private val executor = Executors.newSingleThreadExecutor()

    val isVisible: Boolean get() = panel.visibility == View.VISIBLE
    val pillVisible: Boolean get() = pill.visibility == View.VISIBLE

    init {
        panel.visibility = View.GONE
        setupPreviewWeb()
        wirePanel()
    }

    // ── Public entry points ───────────────────────────────────────────

    /** "پیش‌نمایش" pressed on a code block inside the chat. */
    fun showCode(code: String, langIn: String) {
        val lang = normalizeLang(langIn)
        val kind = kindFor(lang)
        val name = suggestName(lang, kind)
        payload = Payload(name, lang, kind, truncate(code), mimeFor(lang, kind), null)
        openPanel()
        ensureViewerLoaded()
    }

    /** A file download started from the site (export, artifact, image…). */
    fun downloadAndPreview(url: String, userAgent: String, mime: String, disposition: String) {
        val name = sanitize(guessFileName(disposition, url, mime))
        activity.toast(activity.getString(R.string.download_started))
        executor.execute {
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
                val dir = File(activity.cacheDir, "previews").apply { mkdirs() }
                val out = File(dir, name)
                out.outputStream().use { sink -> conn.inputStream.use { it.copyTo(sink) } }
                val realMime = conn.contentType?.substringBefore(';')?.trim()?.ifEmpty { mime } ?: mime
                activity.runOnUiThread { showDownloaded(out, realMime) }
            } catch (t: Throwable) {
                activity.runOnUiThread {
                    activity.toast(activity.getString(R.string.download_failed) + ": " + (t.message ?: ""))
                }
            }
        }
    }

    /** Downloaded file finished; classify and preview or open externally. */
    fun showDownloaded(file: File, mime: String) {
        val name = file.name
        val ext = name.substringAfterLast('.', "").lowercase()
        when {
            mime.startsWith("image/") && file.length() < 8_000_000 -> {
                val bytes = file.readBytes()
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                payload = Payload(name, ext.ifEmpty { "image" }, Kind.IMAGE,
                    "data:$mime;base64,$b64", mime, file)
                openPanel()
                ensureViewerLoaded()
            }
            isHtmlFile(ext) || mime.equals("text/html", true) -> {
                payload = Payload(name, "html", Kind.HTML, truncate(file.readText()), "text/html", file)
                openPanel()
                ensureViewerLoaded()
            }
            ext in setOf("md", "markdown") || mime.equals("text/markdown", true) -> {
                payload = Payload(name, "md", Kind.MARKDOWN, truncate(file.readText()), "text/markdown", file)
                openPanel()
                ensureViewerLoaded()
            }
            mime.equals("application/pdf", true) || ext == "pdf" -> openExternally(file, mime)
            isTextual(ext, mime) -> {
                payload = Payload(name, langForExt(ext), Kind.CODE, truncate(file.readText()), mime.ifEmpty { "text/plain" }, file)
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
        panel.visibility = View.VISIBLE
        panel.post {
            setPanelHeight()
            header.post { nameView.text = payload?.name ?: "" }
            langView.text = payload?.lang ?: ""
            panel.translationY = panel.height.toFloat()
            panel.animate()
                .translationY(0f)
                .setDuration(260)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    fun minimize() {
        panel.animate()
            .translationY(panel.height.toFloat())
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                panel.visibility = View.GONE
                pillLabel.text = payload?.name ?: activity.getString(R.string.preview_pill)
                pill.visibility = View.VISIBLE
                pill.animate().alpha(1f).setDuration(180).start()
            }
            .start()
    }

    fun restore() {
        pill.visibility = View.GONE
        openPanel()
    }

    fun close() {
        panel.visibility = View.GONE
        pill.visibility = View.GONE
        payload = null
        previewWeb.loadUrl("about:blank")
        viewerLoaded = false
    }

    private fun toggleExpand() {
        expanded = !expanded
        setPanelHeight()
    }

    private fun setPanelHeight() {
        val parent = panel.parent as View
        val h = (parent.height * if (expanded) 0.94f else 0.62f).toInt()
        panel.layoutParams = panel.layoutParams.apply { height = h }
    }

    // ── Viewer plumbing ───────────────────────────────────────────────

    private fun setupPreviewWeb() {
        previewWeb.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = false
        }
        previewWeb.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (url != "about:blank") {
                    viewerLoaded = true
                    previewLoading.visibility = View.GONE
                    pushPayload()
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.scheme == "http" || u.scheme == "https") {
                    // Rendered previews must not navigate the viewer itself.
                    try {
                        activity.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: ActivityNotFoundException) {
                    }
                    return true
                }
                return false
            }
        }
    }

    private fun ensureViewerLoaded() {
        previewLoading.visibility = View.VISIBLE
        if (viewerLoaded) {
            pushPayload()
        } else {
            val html = activity.assets.open("viewer.html").use { it.readBytes().toString(Charsets.UTF_8) }
            previewWeb.loadDataWithBaseURL("https://salix.viewer/", html, "text/html", "utf-8", null)
        }
    }

    private fun pushPayload() {
        val p = payload ?: return
        val json = JSONObject()
            .put("mode", when (p.kind) {
                Kind.CODE -> "code"; Kind.HTML -> "html"
                Kind.MARKDOWN -> "md"; Kind.IMAGE -> "image"
            })
            .put("lang", p.lang)
            .put("name", p.name)
            .put("content", p.content)
        previewWeb.evaluateJavascript("SaliXViewer.setContent($json);", null)
    }

    // ── Chips ─────────────────────────────────────────────────────────

    private fun wirePanel() {
        panel.findViewById<ImageButton>(R.id.btnExpand).setOnClickListener { toggleExpand() }
        panel.findViewById<ImageButton>(R.id.btnMinimize).setOnClickListener { minimize() }
        panel.findViewById<ImageButton>(R.id.btnClose).setOnClickListener { close() }

        header.setOnTouchListener(object : View.OnTouchListener {
            private var startY = 0f
            private var dragging = false
            @SuppressLint("ClickableViewAccessibility")
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startY = event.rawY; dragging = true; return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = event.rawY - startY
                        if (dy > 0) panel.translationY = dy
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (panel.translationY > panel.height * 0.25f) minimize()
                        else panel.animate().translationY(0f).setDuration(180).start()
                        dragging = false
                        return true
                    }
                }
                return false
            }
        })

        panel.findViewById<TextView>(R.id.chipCopy).setOnClickListener {
            payload?.let { if (it.kind != Kind.IMAGE) copyToClipboard(it.content) }
        }
        panel.findViewById<TextView>(R.id.chipSave).setOnClickListener { saveToDownloads() }
        panel.findViewById<TextView>(R.id.chipShare).setOnClickListener { share() }
        panel.findViewById<TextView>(R.id.chipExternal).setOnClickListener {
            val p = payload ?: return@setOnClickListener
            val f = p.file ?: run {
                // In-memory payload: materialize it first.
                val dir = File(activity.cacheDir, "previews").apply { mkdirs() }
                val f2 = File(dir, p.name)
                f2.writeText(p.content)
                f2
            }
            openExternally(f, p.mime)
        }

        pill.setOnClickListener { restore() }
        activity.findViewById<View>(R.id.pillClose).setOnClickListener { close() }
    }

    private fun saveToDownloads() {
        val p = payload ?: return
        executor.execute {
            try {
                val backing = p.file
                val bytes: ByteArray = when {
                    p.kind == Kind.IMAGE && p.content.startsWith("data:") ->
                        Base64.decode(p.content.substringAfter("base64,"), Base64.NO_WRAP)
                    backing != null && backing.exists() -> backing.readBytes()
                    else -> p.content.toByteArray(Charsets.UTF_8)
                }
                val mime = p.mime.ifEmpty { "application/octet-stream" }
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, p.name)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = activity.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("insert failed")
                    activity.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: throw IllegalStateException("stream failed")
                } else {
                    // No WRITE permission needed in the app's external Download dir.
                    val dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                        ?: File(activity.filesDir, "downloads").apply { mkdirs() }
                    File(dir, p.name).writeBytes(bytes)
                }
                activity.runOnUiThread { activity.toast(activity.getString(R.string.saved_to_downloads)) }
            } catch (t: Throwable) {
                activity.runOnUiThread { activity.toast("ذخیره ناموفق: ${t.message ?: ""}") }
            }
        }
    }

    private fun share() {
        val p = payload ?: return
        val backing = p.file
        if (backing != null && backing.exists()) {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", backing)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = p.mime.ifEmpty { "application/octet-stream" }
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.share_via)))
        } else {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, p.content)
            }
            activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.share_via)))
        }
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
            activity.toast("برنامه‌ای برای باز کردن «${file.name}» پیدا نشد")
        } catch (t: Throwable) {
            activity.toast("باز کردن ناموفق: ${t.message ?: ""}")
        }
    }

    // ── Classification helpers ────────────────────────────────────────

    private fun truncate(s: String): String =
        if (s.length > 800_000) s.take(800_000) + "\n\n/* … (برای کارایی، ادامهٔ فایل کوتاه شده) */"
        else s

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
        "preview-" + System.currentTimeMillis() % 100000 + (extByLang[lang] ?: when (kind) {
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

    private fun sanitize(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|]"), "_").take(120).ifEmpty { "file.bin" }

    companion object {
        fun guessFileName(disposition: String?, url: String, mime: String): String {
            disposition?.let {
                Regex("""filename\*=UTF-8''([^;]+)""").find(it)?.groupValues?.get(1)?.let { d ->
                    return URLDecoder.decode(d.trim('"', ' '), "UTF-8")
                }
                Regex("""filename="?([^";]+)"?""").find(it)?.groupValues?.get(1)?.let { d ->
                    return d.trim()
                }
            }
            val path = try { URL(url).path } catch (_: Exception) { url }
            val last = path.substringAfterLast('/')
            if (last.isNotEmpty() && last.contains('.')) return last
            val ext = when {
                mime.startsWith("image/") -> mime.substringAfter('/').replace("jpeg", "jpg")
                mime == "text/html" -> "html"
                else -> "bin"
            }
            return "download-$ext." + if (ext == "bin") "bin" else ext
        }
    }
}
