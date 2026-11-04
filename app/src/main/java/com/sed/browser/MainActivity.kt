package com.sed.browser

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.graphics.BitmapFactory
import android.view.Gravity
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private var web: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
            val browser = findViewById<WebView>(R.id.web)
            web = browser
            val address = findViewById<EditText>(R.id.address)
            val wall = findViewById<ImageView>(R.id.wallpaper)

            browser.settings.javaScriptEnabled = true
            browser.settings.domStorageEnabled = true
            browser.settings.mediaPlaybackRequiresUserGesture = false
            browser.webViewClient = WebViewClient()
            browser.webChromeClient = WebChromeClient()

            findViewById<Button>(R.id.go).setOnClickListener {
                var q = address.text.toString().trim()
                if (q.isNotEmpty()) {
                    if (!q.startsWith("http://") && !q.startsWith("https://")) {
                        q = if (q.contains(".")) "https://$q"
                        else "https://www.google.com/search?q=" + java.net.URLEncoder.encode(q, "UTF-8")
                    }
                    browser.loadUrl(q)
                }
            }
            findViewById<Button>(R.id.back).setOnClickListener {
                if (browser.canGoBack()) browser.goBack()
            }

            getSharedPreferences("sed", MODE_PRIVATE).getString("wall", null)?.let { path ->
                try { BitmapFactory.decodeFile(path)?.let(wall::setImageBitmap) } catch (_: Exception) {}
            }
            findViewById<Button>(R.id.wallpaperButton).setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }, 7)
            }
            browser.loadUrl("https://www.google.com")
        } catch (t: Throwable) {
            showStartupError(t)
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

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7 && resultCode == RESULT_OK) {
            try {
                val uri = data?.data ?: return
                contentResolver.openInputStream(uri)?.use { input ->
                    val file = java.io.File(filesDir, "wall.jpg")
                    file.outputStream().use { output -> input.copyTo(output) }
                    getSharedPreferences("sed", MODE_PRIVATE).edit().putString("wall", file.absolutePath).apply()
                    findViewById<ImageView>(R.id.wallpaper).setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
                }
            } catch (t: Throwable) { showStartupError(t) }
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() {
        val browser = web
        if (browser != null && browser.canGoBack()) browser.goBack() else super.onBackPressed()
    }
}
