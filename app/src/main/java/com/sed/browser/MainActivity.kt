package com.sed.browser

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.TextView

class MainActivity : Activity() {
    private var browser: WebView? = null
    private lateinit var toolbar: View
    private lateinit var addressBar: EditText
    private val hideHandler = Handler()
    private var twoFingerStartY = 0f

    private val hideToolbar = Runnable {
        toolbar.visibility = View.GONE
        addressBar.clearFocus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            setContentView(R.layout.activity_main)

            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )

            val webView = findViewById<WebView>(R.id.web)
            toolbar = findViewById(R.id.browserToolbar)
            addressBar = findViewById(R.id.address)
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

            addressBar.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    openAddress(webView)
                    true
                } else false
            }

            webView.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        if (event.pointerCount == 2) {
                            twoFingerStartY = (event.getY(0) + event.getY(1)) / 2f
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (event.pointerCount == 2) {
                            val currentY = (event.getY(0) + event.getY(1)) / 2f
                            // Two fingers dragged downward reveals the hidden address bar.
                            if (currentY - twoFingerStartY > 80f) showToolbar()
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        // Keep the page's normal touch behaviour intact.
                    }
                }
                false
            }

            webView.loadUrl("https://www.google.com")
        } catch (error: Exception) {
            showError(error)
        }
    }

    private fun showToolbar() {
        hideHandler.removeCallbacks(hideToolbar)
        toolbar.visibility = View.VISIBLE
        addressBar.setText(browser?.url ?: "")
        hideHandler.postDelayed(hideToolbar, 5000)
    }

    private fun openAddress(webView: WebView) {
        val text = addressBar.text.toString().trim()
        if (text.isEmpty()) return

        val target = when {
            text.startsWith("https://") || text.startsWith("http://") -> text
            text.contains(".") && !text.contains(" ") -> "https://$text"
            else -> "https://www.google.com/search?q=" +
                java.net.URLEncoder.encode(text, "UTF-8")
        }
        webView.loadUrl(target)
        hideHandler.postDelayed(hideToolbar, 300)
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
        hideHandler.removeCallbacksAndMessages(null)
        browser?.apply {
            stopLoading()
            destroy()
        }
        browser = null
        super.onDestroy()
    }
}