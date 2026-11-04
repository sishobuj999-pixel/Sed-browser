package com.sed.browser

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.graphics.BitmapFactory

class MainActivity : Activity() {
    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        web = findViewById(R.id.web)
        val address = findViewById<EditText>(R.id.address)
        val wall = findViewById<ImageView>(R.id.wallpaper)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        web.loadUrl("https://www.google.com")
        findViewById<Button>(R.id.go).setOnClickListener {
            var q = address.text.toString().trim()
            if (q.isNotEmpty()) {
                if (!q.startsWith("http")) q = if (q.contains(".")) "https://$q"
                else "https://www.google.com/search?q=" + java.net.URLEncoder.encode(q, "UTF-8")
                web.loadUrl(q)
            }
        }
        findViewById<Button>(R.id.back).setOnClickListener { if (web.canGoBack()) web.goBack() }
        val prefs = getSharedPreferences("sed", MODE_PRIVATE)
        prefs.getString("wall", null)?.let { BitmapFactory.decodeFile(it)?.let(wall::setImageBitmap) }
        findViewById<Button>(R.id.wallpaperButton).setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }, 7)
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            contentResolver.openInputStream(uri)?.use { input ->
                val file = java.io.File(filesDir, "wall.jpg")
                file.outputStream().use { output -> input.copyTo(output) }
                getSharedPreferences("sed", MODE_PRIVATE).edit().putString("wall", file.absolutePath).apply()
                findViewById<ImageView>(R.id.wallpaper).setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
            }
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
