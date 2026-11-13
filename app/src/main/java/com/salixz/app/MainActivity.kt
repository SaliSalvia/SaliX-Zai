package com.salixz.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : AppCompatActivity() {

    companion object {
        const val HOME = "https://chat.z.ai"
        const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        /** Domains that stay inside the shell (the chat plus the sign-in providers). */
        private val INTERNAL_DOMAINS = listOf("z.ai")
        private val AUTH_DOMAINS = listOf(
            "accounts.google.com",
            "appleid.apple.com",
            "github.com",
            "login.microsoftonline.com"
        )

        fun isInternalHost(host: String?): Boolean {
            val h = host?.lowercase() ?: return false
            return INTERNAL_DOMAINS.any { h == it || h.endsWith(".$it") } ||
                AUTH_DOMAINS.any { h == it || h.endsWith(".$it") }
        }
    }

    lateinit var web: WebView
    lateinit var preview: PreviewController
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var toolbar: View
    private lateinit var progress: ProgressBar
    private lateinit var intro: FrameLayout
    private lateinit var offline: LinearLayout
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var btnRestoreBar: ImageButton

    private var defaultUA: String = ""
    private var introDone = false
    private var pageLoaded = false
    private var desktopMode = false
    private var immersive = false

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingAudioRequest: PermissionRequest? = null
    private var pendingStorageAction: (() -> Unit)? = null

    private val pickSingle = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        filePathCallback?.onReceiveValue(uri?.let { arrayOf(it) })
        filePathCallback = null
    }
    private val pickMultiple = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        filePathCallback?.onReceiveValue(uris.toTypedArray())
        filePathCallback = null
    }
    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val req = pendingAudioRequest
        pendingAudioRequest = null
        if (granted) req?.grant(req.resources) else req?.deny()
    }
    private val requestStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingStorageAction
        pendingStorageAction = null
        if (granted) action?.invoke() else toast(getString(R.string.storage_permission_denied))
    }

    private var backPressedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.web)
        swipe = findViewById(R.id.swipe)
        toolbar = findViewById(R.id.toolbar)
        progress = findViewById(R.id.progress)
        intro = findViewById(R.id.intro)
        offline = findViewById(R.id.offline)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        btnRestoreBar = findViewById(R.id.btnRestoreBar)

        desktopMode = Prefs.read(this, Prefs.KEY_DESKTOP)
        preview = PreviewController(this)
        setupWeb()
        setupSwipe()
        setupMenu()
        setupBack()
        setupIntro()

        if (Prefs.read(this, Prefs.KEY_IMMERSIVE)) enterImmersive(showToast = false)

        val restored = savedInstanceState?.let { web.restoreState(it) } != null
        if (!restored) web.loadUrl(initialUrl())
        if (savedInstanceState == null) handleShareIntent(intent)
        handleViewIntent(intent)
    }

    // ── WebView shell ─────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        defaultUA = web.settings.userAgentString
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = true
            // Popups/OAuth targets (_blank) are routed back into the shell by onCreateWindow.
            setSupportMultipleWindows(true)
            setGeolocationEnabled(false)
        }
        applyDisplayMode()
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        web.addJavascriptInterface(Bridge(this), "SaliXBridge")
        web.setBackgroundColor(getColor(R.color.dark_background))

        web.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val scheme = (u.scheme ?: "").lowercase()
                if (scheme == "http" || scheme == "https") {
                    // Chat + sign-in providers stay in the shell, everything else
                    // opens in the real browser instead of hijacking the chat page.
                    if (isInternalHost(u.host)) return false
                    openLinkExternally(u)
                    return true
                }
                if (scheme == "about" || scheme == "blob" || scheme == "data" || scheme == "javascript") {
                    return false
                }
                // mailto:, tel:, intent:, market: … → hand to the system
                return handOffToSystem(u)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                pageLoaded = false
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageLoaded = true
                swipe.isRefreshing = false
                offline.visibility = View.GONE
                injectShell()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && !pageLoaded) showOffline()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // The renderer died (usually OOM). Restart the shell instead of
                // leaving the user with a dead white page.
                toast(getString(R.string.renderer_restarted))
                window.decorView.post { if (!isFinishing) recreate() }
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                if (newProgress >= 60) hideIntroOnce()
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                // _blank links and OAuth popups: read the target URL, then either
                // load it in the shell or hand it to the browser.
                val popup = WebView(this@MainActivity)
                popup.settings.javaScriptEnabled = true
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        val uri = request.url
                        if (isInternalHost(uri.host)) web.loadUrl(uri.toString()) else openLinkExternally(uri)
                        v.stopLoading()
                        v.post { v.destroy() }
                        return true
                    }
                }
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                fullscreenContainer.addView(
                    view,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
                fullscreenContainer.visibility = View.VISIBLE
                swipe.visibility = View.GONE
                toolbar.visibility = View.GONE
            }

            override fun onHideCustomView() {
                exitFullscreenVideo()
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val accept = params.acceptTypes
                    .firstOrNull { it.isNotBlank() && it != "*/*" && !it.contains(',') }
                    ?: "*/*"
                try {
                    if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) pickMultiple.launch(accept)
                    else pickSingle.launch(accept)
                } catch (_: ActivityNotFoundException) {
                    filePathCallback = null
                    return false
                }
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                val wantsMic = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                if (wantsMic) {
                    if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        request.grant(request.resources)
                    } else {
                        pendingAudioRequest = request
                        requestMic.launch(Manifest.permission.RECORD_AUDIO)
                    }
                } else {
                    request.deny()
                }
            }
        }

        web.setDownloadListener { url, ua, disposition, mime, _ ->
            preview.handleDownload(url, ua, mime, disposition)
        }

        findViewById<View>(R.id.btnRetry).setOnClickListener {
            offline.visibility = View.GONE
            web.loadUrl(HOME)
        }
        findViewById<View>(R.id.btnOfflineBrowser).setOnClickListener {
            openInBrowser()
        }
    }

    private fun setupSwipe() {
        swipe.setOnRefreshListener { web.reload() }
        swipe.setColorSchemeColors(
            ContextCompat.getColor(this, R.color.purple_primary),
            ContextCompat.getColor(this, R.color.purple_accent)
        )
        swipe.setProgressBackgroundColorSchemeColor(ContextCompat.getColor(this, R.color.dark_surface_variant))
    }

    private fun applyDisplayMode() {
        web.settings.apply {
            userAgentString = if (desktopMode) DESKTOP_UA else defaultUA
            useWideViewPort = desktopMode
            loadWithOverviewMode = desktopMode
        }
    }

    private fun setupIntro() {
        intro.post {
            intro.findViewById<View>(R.id.introLogo)?.apply {
                alpha = 0f
                scaleX = 0.88f
                scaleY = 0.88f
                animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420).start()
            }
        }
    }

    private fun injectShell() {
        try {
            val js = assets.open("shell.js").use { it.readBytes().toString(Charsets.UTF_8) }
            web.evaluateJavascript(js, null)
        } catch (_: Exception) {
        }
    }

    private fun hideIntroOnce() {
        if (introDone) return
        introDone = true
        intro.animate().alpha(0f).setDuration(320)
            .withEndAction { intro.visibility = View.GONE }
            .start()
    }

    private fun showOffline() {
        hideIntroOnce()
        offline.visibility = View.VISIBLE
    }

    fun webUA(): String = web.settings.userAgentString

    fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    fun isTrustedOrigin(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val host = try {
            Uri.parse(url).host
        } catch (_: Throwable) {
            null
        }
        return isInternalHost(host)
    }

    fun openLinkExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
        }
    }

    private fun handOffToSystem(uri: Uri): Boolean {
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    /**
     * Android 8/9 need WRITE_EXTERNAL_STORAGE to land files in the public
     * Downloads folder; newer versions use MediaStore and need nothing.
     */
    fun withDownloadsAccess(action: () -> Unit) {
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (!needsPermission) {
            action()
        } else {
            pendingStorageAction = action
            requestStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    // ── Menu ──────────────────────────────────────────────────────────

    private data class MenuAction(
        val icon: Int,
        val title: String,
        val subtitle: String?,
        val destructive: Boolean = false,
        val onClick: () -> Unit
    )

    private fun setupMenu() {
        findViewById<View>(R.id.btnMenu).setOnClickListener { showMenu() }
        btnRestoreBar.setOnClickListener { exitImmersive() }
    }

    private fun showMenu() {
        val dialog = Dialog(this, R.style.Theme_SaliX_Sheet)
        dialog.setContentView(R.layout.dialog_menu)
        val rows = dialog.findViewById<LinearLayout>(R.id.menuRows)
        val actions = buildMenuActions()

        actions.forEachIndexed { index, action ->
            val row = layoutInflater.inflate(R.layout.view_menu_row, rows, false)
            row.findViewById<ImageView>(R.id.menuIcon).setImageResource(action.icon)
            row.findViewById<TextView>(R.id.menuTitle).apply {
                text = action.title
                if (action.destructive) setTextColor(ContextCompat.getColor(this@MainActivity, R.color.error_red))
            }
            val sub = row.findViewById<TextView>(R.id.menuSubtitle)
            if (action.subtitle.isNullOrBlank()) {
                sub.visibility = View.GONE
            } else {
                sub.text = action.subtitle
            }
            val icon = row.findViewById<ImageView>(R.id.menuIcon)
            if (action.destructive) icon.setColorFilter(ContextCompat.getColor(this, R.color.error_red))
            row.findViewById<View>(R.id.menuDivider).visibility =
                if (index < actions.lastIndex) View.VISIBLE else View.GONE
            row.setOnClickListener {
                dialog.dismiss()
                action.onClick()
            }
            rows.addView(row)
        }

        dialog.findViewById<View>(R.id.menuCancel).setOnClickListener { dialog.dismiss() }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.6f)
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun buildMenuActions(): List<MenuAction> = listOf(
        MenuAction(
            R.drawable.ic_refresh,
            getString(R.string.menu_refresh),
            getString(R.string.menu_refresh_sub)
        ) { web.reload() },
        MenuAction(
            if (desktopMode) R.drawable.ic_mobile else R.drawable.ic_desktop,
            if (desktopMode) getString(R.string.menu_mobile) else getString(R.string.menu_desktop),
            getString(R.string.menu_desktop_sub)
        ) { toggleDesktop() },
        MenuAction(
            if (immersive) R.drawable.ic_arrow_down else R.drawable.ic_expand,
            if (immersive) getString(R.string.menu_show_bar) else getString(R.string.menu_immersive),
            getString(R.string.menu_immersive_sub)
        ) { toggleImmersive() },
        MenuAction(
            R.drawable.ic_open_ext,
            getString(R.string.menu_open_browser),
            getString(R.string.menu_open_browser_sub)
        ) { openInBrowser() },
        MenuAction(
            R.drawable.ic_delete,
            getString(R.string.menu_clear_data),
            getString(R.string.menu_clear_data_sub),
            destructive = true
        ) { confirmClearData() },
        MenuAction(
            R.drawable.ic_info,
            getString(R.string.menu_about),
            getString(R.string.about_version, BuildConfig.VERSION_NAME)
        ) { showAbout() }
    )

    private fun toggleDesktop() {
        desktopMode = !desktopMode
        Prefs.write(this, Prefs.KEY_DESKTOP, desktopMode)
        applyDisplayMode()
        toast(if (desktopMode) getString(R.string.menu_desktop) else getString(R.string.menu_mobile))
        web.reload()
    }

    private fun toggleImmersive() {
        if (immersive) exitImmersive() else enterImmersive()
    }

    private fun enterImmersive(showToast: Boolean = true) {
        immersive = true
        Prefs.write(this, Prefs.KEY_IMMERSIVE, true)
        toolbar.visibility = View.GONE
        btnRestoreBar.visibility = View.VISIBLE
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (showToast) toast(getString(R.string.menu_show_bar))
    }

    private fun exitImmersive() {
        immersive = false
        Prefs.write(this, Prefs.KEY_IMMERSIVE, false)
        btnRestoreBar.visibility = View.GONE
        toolbar.visibility = View.VISIBLE
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    private fun openInBrowser() {
        openLinkExternally(Uri.parse(web.url ?: HOME))
    }

    private fun confirmClearData() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.menu_clear_data))
            .setMessage(getString(R.string.clear_data_confirm))
            .setPositiveButton(getString(R.string.preview_close)) { _, _ -> clearAllData() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun clearAllData() {
        web.clearCache(true)
        web.clearHistory()
        web.clearFormData()
        WebStorage.getInstance().deleteAllData()
        val cm = CookieManager.getInstance()
        cm.removeAllCookies { }
        cm.removeSessionCookies { }
        cm.flush()
        try {
            java.io.File(cacheDir, "previews").deleteRecursively()
        } catch (_: Throwable) {
        }
        toast(getString(R.string.data_cleared))
        web.loadUrl(HOME)
    }

    private fun showAbout() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_name))
            .setMessage(getString(R.string.about_body, BuildConfig.VERSION_NAME))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ── Back navigation ───────────────────────────────────────────────

    private fun setupBack() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> exitFullscreenVideo()
                    preview.isVisible -> preview.minimize()
                    preview.pillVisible -> preview.close()
                    web.canGoBack() -> web.goBack()
                    else -> {
                        val now = System.currentTimeMillis()
                        if (now - backPressedAt < 2000) finish()
                        else {
                            backPressedAt = now
                            toast(getString(R.string.exit_hint))
                        }
                    }
                }
            }
        })
    }

    private fun exitFullscreenVideo() {
        customView?.let { fullscreenContainer.removeView(it) }
        customView = null
        fullscreenContainer.visibility = View.GONE
        swipe.visibility = View.VISIBLE
        toolbar.visibility = if (immersive) View.GONE else View.VISIBLE
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }

    // ── Intents ───────────────────────────────────────────────────────

    private fun initialUrl(): String {
        val data = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data
        return if (data != null && isInternalHost(data.host)) data.toString() else HOME
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val data = intent.data ?: return
        if (isInternalHost(data.host) && data.toString() != web.url) {
            web.loadUrl(data.toString())
        }
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("SaliX-Z", text))
        toast(getString(R.string.shared_text_copied))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
        handleViewIntent(intent)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        preview.onConfigurationChanged()
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        web.onPause()
        web.pauseTimers()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.resumeTimers()
        web.onResume()
    }

    override fun onDestroy() {
        preview.destroy()
        web.destroy()
        super.onDestroy()
    }
}
