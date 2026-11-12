package com.salixz.app

import android.webkit.JavascriptInterface

/**
 * JS-to-native bridge injected into the chat WebView as `SaliXBridge`.
 * Every method runs on the WebView's JS bridge thread, so all UI work is
 * posted back to the main thread by [MainActivity].
 */
class Bridge(private val activity: MainActivity) {

    /** "پیش‌نمایش" chip on a <pre><code> block in the chat. */
    @JavascriptInterface
    fun openCode(code: String, lang: String) {
        activity.runOnUiThread { activity.preview.showCode(code, lang) }
    }

    /** "کپی" chip on a code block. */
    @JavascriptInterface
    fun copyText(text: String) {
        activity.runOnUiThread { activity.preview.copyToClipboard(text) }
    }

    /** A download link the site exposes (artifact export etc.). */
    @JavascriptInterface
    fun openDownload(url: String, mime: String, filename: String) {
        activity.runOnUiThread {
            activity.preview.downloadAndPreview(url, activity.webUA(), mime, filename)
        }
    }

    @JavascriptInterface
    fun toast(message: String) {
        activity.runOnUiThread { activity.toast(message) }
    }
}
