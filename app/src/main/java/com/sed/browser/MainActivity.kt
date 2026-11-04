package com.sed.browser

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

class MainActivity : Activity() {
    private var web: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersiveMode()
        try {
            setContentView(R.layout.activity_main)
            val browser = findViewById<WebView>(R.id.web)
            web = browser
            val address = findViewById<EditText>(R.id.address)

            browser.settings.javaScriptEnabled = true
            browser.settings.domStorageEnabled = true
            browser.settings.mediaPlaybackRequiresUserGesture = false
            browser.settings.loadsImagesAutomatically = true
            browser.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url != null && !address.hasFocus()) address.setText(url)
                }
            }
            browser.webChromeClient = WebChromeClient()

            findViewById<Button>(R.id.go).setOnClickListener {
                var q = address.text.toString().trim()
                if (q.isNotEmpty()) {
                    if (!q.startsWith("http://") && !q.startsWith("https://")) {
                        q = if (q.contains(".") && !q.contains(" "))
                            "https://$q"
                        else
                            "https://www.google.com/search?q=" + java.net.URLEncoder.encode(q, "UTF-8")
                    }
                    browser.loadUrl(q)
                }
            }
            findViewById<Button>(R.id.back).setOnClickListener {
                if (browser.canGoBack()) browser.goBack()
            }
            browser.loadUrl("https://www.google.com")
        } catch (t: Throwable) {
            showStartupError(t)
        }
    }

    private fun enterImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }

    private fun showStartupError(t: Throwable) {
        val message = TextView(this).apply {
            text = "SED Browser startup error\n\n${t.javaClass.name}: ${t.message}\n\nPlease send this screen to the developer."
            textSize = 16f
            setPadding(32, 48, 32, 32)
            gravity = Gravity.CENTER_VERTICAL
        }
        setContentView(message)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() {
        val browser = web
        if (browser != null && browser.canGoBack()) browser.goBack() else super.onBackPressed()
    }
}
