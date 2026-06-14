package com.yage.opencode_client

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class OpenCodeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        warmUpWebViewAfterLaunch()
    }

    private fun warmUpWebViewAfterLaunch() {
        Handler(Looper.getMainLooper()).post {
            runCatching {
                warmedWebView = WebView(applicationContext).apply {
                    loadUrl("about:blank")
                }
            }
        }
    }

    companion object {
        private var warmedWebView: WebView? = null
    }
}
