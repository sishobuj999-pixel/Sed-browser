package com.sed.browser

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import java.net.URLEncoder
import kotlin.math.abs

class MainActivity : Activity() {
    private lateinit var browserContainer: FrameLayout
    private lateinit var toolbar: View
    private lateinit var addressBar: EditText
    private val tabs = mutableListOf<WebView>()
    private var activeTab = 0
    private val hideHandler = Handler()
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var horizontalGestureHandled = false

    private val hideToolbar = Runnable {
        toolbar.visibility = View.GONE
        addressBar.clearFocus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        browserContainer = findViewById(R.id.browserContainer)
        toolbar = findViewById(R.id.browserToolbar)
        addressBar = findViewById(R.id.address)

        // Keep website login sessions and normal WebView cache between launches.
        CookieManager.getInstance().setAcceptCookie(true)

        addressBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                openAddress()
                true
            } else false
        }

        createTab("https://www.google.com")
        showSplash()
    }

    private fun createTab(url: String) {
        if (tabs.size >= 8) {
            tabs.first().destroy()
            tabs.removeAt(0)
            if (activeTab > 0) activeTab--
        }

        val webView = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadsImagesAutomatically = true
                mediaPlaybackRequiresUserGesture = true
                cacheMode = WebSettings.LOAD_DEFAULT
                saveFormData = true
                allowFileAccess = false
                setSupportMultipleWindows(false)
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    if (this@MainActivity.tabs.getOrNull(activeTab) === view &&
                        pageUrl != null && !addressBar.hasFocus()
                    ) addressBar.setText(pageUrl)
                }
            }
            webChromeClient = WebChromeClient()
        }

        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        tabs.add(webView)
        browserContainer.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        activeTab = tabs.lastIndex
        attachGestures(webView)
        showActiveTab()
        webView.loadUrl(url)
    }

    private fun attachGestures(webView: WebView) {
        webView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount >= 2) {
                        gestureStartX = (event.getX(0) + event.getX(1)) / 2f
                        gestureStartY = (event.getY(0) + event.getY(1)) / 2f
                        horizontalGestureHandled = false
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount >= 2 && !horizontalGestureHandled) {
                        val x = (event.getX(0) + event.getX(1)) / 2f
                        val y = (event.getY(0) + event.getY(1)) / 2f
                        val dx = x - gestureStartX
                        val dy = y - gestureStartY
                        if (abs(dx) > 120f && abs(dx) > abs(dy) * 1.25f) {
                            horizontalGestureHandled = true
                            if (dx < 0) {
                                // Two fingers right-to-left: next tab, or create one.
                                if (activeTab < tabs.lastIndex) {
                                    activeTab++
                                    showActiveTab()
                                } else if (tabs.size < 8) {
                                    createTab("https://www.google.com")
                                } else {
                                    activeTab = 0
                                    showActiveTab()
                                }
                            } else if (activeTab > 0) {
                                // Two fingers left-to-right: previous tab.
                                activeTab--
                                showActiveTab()
                            }
                        } else if (abs(dy) > 100f && abs(dy) > abs(dx) * 1.25f) {
                            if (dy > 0) showToolbar()
                        }
                    }
                }
                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    horizontalGestureHandled = false
                }
            }
            false
        }
    }

    private fun showActiveTab() {
        tabs.forEachIndexed { index, webView ->
            webView.visibility = if (index == activeTab) View.VISIBLE else View.GONE
        }
        addressBar.setText(tabs.getOrNull(activeTab)?.url ?: "")
    }

    private fun showToolbar() {
        hideHandler.removeCallbacks(hideToolbar)
        toolbar.visibility = View.VISIBLE
        addressBar.setText(tabs.getOrNull(activeTab)?.url ?: "")
        hideHandler.postDelayed(hideToolbar, 5000)
    }

    private fun openAddress() {
        val text = addressBar.text.toString().trim()
        if (text.isEmpty()) return
        val target = when {
            text.startsWith("https://") || text.startsWith("http://") -> text
            text.contains(".") && !text.contains(" ") -> "https://$text"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(text, "UTF-8")
        }
        tabs.getOrNull(activeTab)?.loadUrl(target)
        hideHandler.postDelayed(hideToolbar, 300)
    }

    private fun showSplash() {
        val splash = TextView(this).apply {
            text = "SED"
            textSize = 54f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
            letterSpacing = 0.18f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(9, 14, 24))
            alpha = 1f
            scaleX = 0.72f
            scaleY = 0.72f
        }
        addContentView(
            splash,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        splash.animate().scaleX(1.28f).scaleY(1.28f).alpha(0f)
            .setDuration(1250).withEndAction {
                (splash.parent as? ViewGroup)?.removeView(splash)
            }.start()
    }

    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() {
        val current = tabs.getOrNull(activeTab)
        if (current?.canGoBack() == true) current.goBack()
        else if (activeTab > 0) {
            activeTab--
            showActiveTab()
        } else super.onBackPressed()
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        hideHandler.removeCallbacksAndMessages(null)
        tabs.forEach { it.stopLoading(); it.destroy() }
        tabs.clear()
        super.onDestroy()
    }
}