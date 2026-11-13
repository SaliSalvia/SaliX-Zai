package com.salixz.app

import android.util.Base64
import android.webkit.JavascriptInterface

/**
 * JS-to-native bridge injected into the chat WebView as `SaliXBridge`.
 *
 * Every method runs on the WebView's JS bridge thread, so all UI work is
 * posted back to the main thread by [MainActivity]. Calls coming from any
 * origin other than the Z.ai family are ignored, so a third-party page can
 * never reach the preview/download machinery.
 */
class Bridge(private val activity: MainActivity) {

    private fun trusted(): Boolean = activity.isTrustedOrigin(activity.web.url)

    /** "پیش‌نمایش" chip on a <pre><code> block in the chat. */
    @JavascriptInterface
    fun openCode(code: String, lang: String) {
        if (!trusted()) return
        activity.runOnUiThread { activity.preview.showCode(code, lang) }
    }

    /** "کپی" chip on a code block. */
    @JavascriptInterface
    fun copyText(text: String) {
        if (!trusted()) return
        activity.runOnUiThread { activity.preview.copyToClipboard(text) }
    }

    /** A download link the site exposes (artifact export etc.). */
    @JavascriptInterface
    fun openDownload(url: String, mime: String, filename: String) {
        if (!trusted()) return
        activity.runOnUiThread {
            activity.preview.handleDownload(url, activity.webUA(), mime, filename)
        }
    }

    /** Result of a `blob:` download fetched inside the page. */
    @JavascriptInterface
    fun onBlob(name: String, mime: String, base64: String) {
        if (!trusted()) return
        val bytes = try {
            Base64.decode(base64, Base64.NO_WRAP)
        } catch (_: Throwable) {
            return
        }
        activity.runOnUiThread { activity.preview.onBlobReceived(name, mime, bytes) }
    }

    @JavascriptInterface
    fun toast(message: String) {
        if (!trusted()) return
        activity.runOnUiThread { activity.toast(message) }
    }
}
