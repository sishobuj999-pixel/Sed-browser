package com.sed.browser

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.LinearLayout
import android.graphics.Color
import android.view.Gravity

class MainActivity : Activity() {
    private var browser: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            // Inflate the screen before applying fullscreen flags.
            setContentView(R.layout.activity_main)

            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )

            val webView = findViewById<WebView>(R.id.web)
            val addressBar = findViewById<EditText>(R.id.address)
            browser = webView

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                loadsImagesAutomatically = true
            }
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url != null && !addressBar.hasFocus()) addressBar.setText(url)
                }
            }
            webView.webChromeClient = WebChromeClient()

            findViewById<Button>(R.id.go).setOnClickListener {
                var text = addressBar.text.toString().trim()
                if (text.isNotEmpty()) {
                    val target = if (text.startsWith("https://") || text.startsWith("http://")) {
                        text
                    } else if (text.contains(".") && !text.contains(" ")) {
                        "https://$text"
                    } else {
                        "https://www.google.com/search?q=" +
                            java.net.URLEncoder.encode(text, "UTF-8")
                    }
                    webView.loadUrl(target)
                }
            }

            findViewById<Button>(R.id.back).setOnClickListener {
                if (webView.canGoBack()) webView.goBack()
            }

            webView.loadUrl("https://www.google.com")
        } catch (error: Exception) {
            showError(error)
        }
    }

    private fun showError(error: Exception) {
        val message = TextView(this).apply {
            setTextColor(Color.BLACK)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(28, 28, 28, 28)
            text = "SED Browser could not start.\n\n" +
                error.javaClass.simpleName + ": " + (error.message ?: "Unknown error")
        }
        setContentView(message)
    }

    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() {
        val current = browser
        if (current != null && current.canGoBack()) current.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        browser?.apply {
            stopLoading()
            destroy()
        }
        browser = null
        super.onDestroy()
    }
}
