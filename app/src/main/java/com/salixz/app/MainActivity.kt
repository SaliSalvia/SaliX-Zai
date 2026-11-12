package com.salixz.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
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
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class MainActivity : AppCompatActivity() {

    companion object {
        const val HOME = "https://chat.z.ai"
        const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    lateinit var web: WebView
    lateinit var preview: PreviewController
    private lateinit var toolbar: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var intro: FrameLayout
    private lateinit var offline: LinearLayout
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var btnRestoreBar: ImageButton

    private var defaultUA: String = ""
    private var introDone = false
    private var desktopMode = false
    private var immersive = false

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingAudioRequest: PermissionRequest? = null

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

    private var backPressedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.web)
        toolbar = findViewById(R.id.toolbar)
        progress = findViewById(R.id.progress)
        intro = findViewById(R.id.intro)
        offline = findViewById(R.id.offline)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        btnRestoreBar = findViewById(R.id.btnRestoreBar)

        desktopMode = Prefs.read(this, Prefs.KEY_DESKTOP)
        preview = PreviewController(this)
        setupWeb()
        setupMenu()
        setupBack()
        handleSendIntent(intent)

        if (Prefs.read(this, Prefs.KEY_IMMERSIVE)) enterImmersive(showToast = false)
        web.loadUrl(HOME)
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
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            if (desktopMode) userAgentString = DESKTOP_UA
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        web.addJavascriptInterface(Bridge(this), "SaliXBridge")
        web.setBackgroundColor(getColor(R.color.dark_background))

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val scheme = u.scheme ?: return false
                if (scheme == "http" || scheme == "https") return false // stay in the shell
                if (scheme == "about" || scheme == "blob" || scheme == "data") return false
                // mailto:, tel:, intent:, ws: … → hand to the system
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, u))
                } catch (_: ActivityNotFoundException) {
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                injectShell()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showOffline(request.url.toString())
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                if (newProgress >= 60) hideIntroOnce()
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
                web.visibility = View.GONE
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
                val accept = params.acceptTypes.firstOrNull { it.isNotBlank() && it != "*/*" } ?: "*/*"
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
            preview.downloadAndPreview(url, ua, mime, disposition)
        }

        findViewById<View>(R.id.btnRetry).setOnClickListener {
            offline.visibility = View.GONE
            web.loadUrl(HOME)
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
        intro.animate().alpha(0f).setDuration(350).withEndAction { intro.visibility = View.GONE }.start()
    }

    private fun showOffline(url: String) {
        hideIntroOnce()
        offline.visibility = View.VISIBLE
        web.tag = url
    }

    fun webUA(): String = web.settings.userAgentString

    fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ── Menu / toolbar ────────────────────────────────────────────────

    private fun setupMenu() {
        findViewById<View>(R.id.btnMenu).setOnClickListener {
            val labels = arrayOf(
                getString(R.string.menu_refresh),
                if (desktopMode) getString(R.string.menu_mobile) else getString(R.string.menu_desktop),
                if (immersive) getString(R.string.menu_show_bar) else getString(R.string.menu_immersive),
                getString(R.string.menu_open_browser),
                getString(R.string.menu_clear_data),
                getString(R.string.menu_about)
            )
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_name))
                .setItems(labels) { _, which -> when (which) {
                    0 -> web.reload()
                    1 -> toggleDesktop()
                    2 -> toggleImmersive()
                    3 -> openInBrowser()
                    4 -> confirmClearData()
                    5 -> showAbout()
                } }
                .show()
        }

        btnRestoreBar.setOnClickListener { exitImmersive() }
    }

    private fun toggleDesktop() {
        desktopMode = !desktopMode
        Prefs.write(this, Prefs.KEY_DESKTOP, desktopMode)
        web.settings.userAgentString = if (desktopMode) DESKTOP_UA else defaultUA
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
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web.url ?: HOME)))
        } catch (_: ActivityNotFoundException) {
        }
    }

    private fun confirmClearData() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.menu_clear_data))
            .setMessage("همه کوکی‌ها و داده‌های ورود از این پوسته پاک می‌شود. مطمئن هستید؟")
            .setPositiveButton(getString(R.string.preview_close)) { _, _ -> clearAllData() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun clearAllData() {
        web.clearCache(true)
        web.clearHistory()
        WebStorage.getInstance().deleteAllData()
        val cm = CookieManager.getInstance()
        cm.removeAllCookies { }
        cm.removeSessionCookies { }
        cm.flush()
        toast("داده‌ها پاک شد")
        web.loadUrl(HOME)
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_name))
            .setMessage(getString(R.string.about_body))
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
        web.visibility = View.VISIBLE
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }

    // ── Share-in (text from other apps) ───────────────────────────────

    private fun handleSendIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("SaliX-Z", text))
            toast("متن کپی شد — در چت پیست کنید")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSendIntent(intent)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    override fun onPause() {
        CookieManager.getInstance().flush()
        web.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
