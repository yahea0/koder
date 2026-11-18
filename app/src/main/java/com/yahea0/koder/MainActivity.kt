package com.yahea0.koder

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    private lateinit var nativeOpenFilesLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var nativeCreateDocLauncher: ActivityResultLauncher<String>

    private val deviceUriMap = ConcurrentHashMap<String, Uri>()

    @Volatile
    private var pendingSaveFileId: String? = null

    @Volatile
    private var pendingSaveContent: String? = null

    private var lastBackPressedMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        applySystemBars("#0A0D12", false)

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        registerLaunchers()

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        root.addView(webView)
        setContentView(root)

        configureWebView()
        setupBackNavigation()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl("https://appassets.androidplatform.net/assets/www/index.html")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        webView.evaluateJavascript("window.__forceSave && window.__forceSave();", null)
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    private fun registerLaunchers() {
        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val cb = filePathCallback
            filePathCallback = null
            if (cb == null) return@registerForActivityResult

            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                val uris = mutableListOf<Uri>()
                val clip = data?.clipData
                if (clip != null) {
                    for (i in 0 until clip.itemCount) {
                        clip.getItemAt(i)?.uri?.let { uris.add(it) }
                    }
                } else {
                    data?.data?.let { uris.add(it) }
                }
                cb.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
            } else {
                cb.onReceiveValue(null)
            }
        }

        nativeOpenFilesLauncher = registerForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            Thread {
                val jsonArray = JSONArray()
                for ((idx, uri) in uris.withIndex()) {
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    } catch (_: Exception) {
                        try {
                            contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (_: Exception) {
                        }
                    }

                    val name = queryDisplayName(uri) ?: "file_${idx + 1}.txt"
                    val content = readTextFromUri(uri) ?: continue
                    val fileId = "dev_${System.currentTimeMillis()}_$idx"
                    deviceUriMap[fileId] = uri

                    val obj = JSONObject().apply {
                        put("id", fileId)
                        put("name", name)
                        put("content", content)
                    }
                    jsonArray.put(obj)
                }

                if (jsonArray.length() > 0) {
                    val quoted = JSONObject.quote(jsonArray.toString())
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "window.__onNativeFilesOpened && window.__onNativeFilesOpened(JSON.parse($quoted));",
                            null
                        )
                    }
                }
            }.start()
        }

        nativeCreateDocLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("*/*")
        ) { uri ->
            val fileId = pendingSaveFileId
            val content = pendingSaveContent
            pendingSaveFileId = null
            pendingSaveContent = null

            if (uri != null && fileId != null && content != null) {
                Thread {
                    val ok = writeTextToUri(uri, content)
                    if (ok) {
                        try {
                            contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            )
                        } catch (_: Exception) {
                        }
                        deviceUriMap[fileId] = uri
                        val savedName = queryDisplayName(uri) ?: "file"
                        val qId = JSONObject.quote(fileId)
                        val qName = JSONObject.quote(savedName)
                        runOnUiThread {
                            webView.evaluateJavascript(
                                "window.__onNativeFileSaved && window.__onNativeFileSaved($qId, $qName);",
                                null
                            )
                        }
                    } else {
                        runOnUiThread {
                            Toast.makeText(this, "تعذر حفظ الملف على الجهاز", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            textZoom = 100
        }

        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url
                val scheme = url.scheme?.lowercase() ?: ""
                if (url.host == "appassets.androidplatform.net" ||
                    scheme == "about" ||
                    scheme == "data" ||
                    scheme == "blob"
                ) {
                    return false
                }
                if (request.isForMainFrame && (scheme == "http" || scheme == "https")) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, url))
                    } catch (_: Exception) {
                    }
                    return true
                }
                return false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    val intent = params?.createIntent() ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    if (params?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    fileChooserLauncher.launch(intent)
                    true
                } catch (_: Exception) {
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = null
                    false
                }
            }

            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("نبض الكود")
                    .setMessage(message ?: "")
                    .setPositiveButton("حسناً") { _, _ -> result?.confirm() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("تأكيد")
                    .setMessage(message ?: "")
                    .setPositiveButton("نعم") { _, _ -> result?.confirm() }
                    .setNegativeButton("إلغاء") { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?
            ): Boolean {
                val input = EditText(this@MainActivity).apply {
                    setText(defaultValue ?: "")
                    setSelection(text.length)
                    setSingleLine(true)
                }
                val pad = (20 * resources.displayMetrics.density).toInt()
                val box = FrameLayout(this@MainActivity).apply {
                    setPadding(pad, pad / 2, pad, 0)
                    addView(input)
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(message ?: "إدخال")
                    .setView(box)
                    .setPositiveButton("موافق") { _, _ ->
                        result?.confirm(input.text.toString())
                    }
                    .setNegativeButton("إلغاء") { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }
        }

        webView.setDownloadListener(DownloadListener { url, _, _, _, _ ->
            if (url.startsWith("http://") || url.startsWith("https://")) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) {
                }
            }
        })
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript(
                    "window.__handleBack ? window.__handleBack() : 'EXIT';"
                ) { res ->
                    val cleaned = res?.trim('"')
                    if (cleaned != "HANDLED") {
                        val now = System.currentTimeMillis()
                        if (now - lastBackPressedMs < 2000L) {
                            finish()
                        } else {
                            lastBackPressedMs = now
                            Toast.makeText(
                                this@MainActivity,
                                "اضغط مرة أخرى للخروج",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        })
    }

    private fun applySystemBars(hexColor: String, isLightIcons: Boolean) {
        try {
            val color = Color.parseColor(hexColor)
            @Suppress("DEPRECATION")
            window.statusBarColor = color
            @Suppress("DEPRECATION")
            window.navigationBarColor = color
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightStatusBars = isLightIcons
            controller.isAppearanceLightNavigationBars = isLightIcons
        } catch (_: Exception) {
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else null
                }
        } catch (_: Exception) {
            null
        }
    }

    private fun readTextFromUri(uri: Uri): String? {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArrayOutputStream()
                val data = ByteArray(16384)
                var nRead: Int
                var total = 0
                val maxBytes = 6 * 1024 * 1024
                while (input.read(data, 0, data.size).also { nRead = it } != -1) {
                    total += nRead
                    if (total > maxBytes) {
                        runOnUiThread {
                            Toast.makeText(this, "الملف أكبر من 6 ميجابايت", Toast.LENGTH_SHORT).show()
                        }
                        return null
                    }
                    buffer.write(data, 0, nRead)
                }
                buffer.toString(Charsets.UTF_8.name())
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeTextToUri(uri: Uri, content: String): Boolean {
        val bytes = content.toByteArray(Charsets.UTF_8)
        for (mode in arrayOf("wt", "rwt")) {
            try {
                contentResolver.openOutputStream(uri, mode)?.use { out ->
                    out.write(bytes)
                    out.flush()
                } ?: continue
                return true
            } catch (_: Exception) {
            }
        }
        return try {
            contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { fos ->
                    fos.channel.truncate(0)
                    fos.write(bytes)
                    fos.flush()
                }
            } ?: return false
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun sanitizeFileName(raw: String): String {
        val cleaned = raw.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return if (cleaned.isEmpty()) "code.txt" else cleaned
    }

    private fun guessMimeType(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js", "mjs" -> "application/javascript"
            "json" -> "application/json"
            "md", "markdown" -> "text/markdown"
            "xml" -> "text/xml"
            "svg" -> "image/svg+xml"
            else -> "application/octet-stream"
        }
    }

    private fun saveToMediaStoreDownloads(fileName: String, content: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val resolver = contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, guessMimeType(fileName))
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/PulseCode"
                )
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: return null
            try {
                resolver.openOutputStream(uri, "w")?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                    out.flush()
                } ?: throw IllegalStateException("Cannot write")

                val publish = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                resolver.update(uri, publish, null, null)
                val savedName = queryDisplayName(uri) ?: fileName
                "Downloads/PulseCode/$savedName"
            } catch (_: Exception) {
                try {
                    resolver.delete(uri, null, null)
                } catch (_: Exception) {
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun launchCreateDocument(fileId: String, fileName: String, content: String) {
        pendingSaveFileId = fileId
        pendingSaveContent = content
        try {
            nativeCreateDocLauncher.launch(fileName)
        } catch (_: Exception) {
            Toast.makeText(this, "تعذر فتح نافذة حفظ الملف", Toast.LENGTH_SHORT).show()
        }
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun pickFiles() {
            runOnUiThread {
                try {
                    nativeOpenFilesLauncher.launch(arrayOf("*/*"))
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "تعذر فتح مدير الملفات", Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }

        @JavascriptInterface
        fun saveFile(fileId: String, fileName: String, content: String): String {
            val safeName = sanitizeFileName(fileName)
            val existingUri = deviceUriMap[fileId]
            if (existingUri != null && writeTextToUri(existingUri, content)) {
                return "DIRECT"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val savedPath = saveToMediaStoreDownloads(safeName, content)
                if (savedPath != null) {
                    return "DOWNLOADS:$savedPath"
                }
            }
            runOnUiThread {
                launchCreateDocument(fileId, safeName, content)
            }
            return "PICKER"
        }

        @JavascriptInterface
        fun saveAs(fileId: String, fileName: String, content: String) {
            val safeName = sanitizeFileName(fileName)
            runOnUiThread {
                launchCreateDocument(fileId, safeName, content)
            }
        }

        @JavascriptInterface
        fun copyText(text: String) {
            runOnUiThread {
                try {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("PulseCode", text))
                } catch (_: Exception) {
                }
            }
        }

        @JavascriptInterface
        fun setSystemBars(hexColor: String, isLightIcons: Boolean) {
            runOnUiThread {
                applySystemBars(hexColor, isLightIcons)
            }
        }
    }
}
