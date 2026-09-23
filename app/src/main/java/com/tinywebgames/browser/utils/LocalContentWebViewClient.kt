package com.tinywebgames.browser.utils

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import java.io.File
import java.io.FileInputStream

class LocalContentWebViewClient(
    private val context: Context,
    private val onPageLoadStart: (() -> Unit)? = null,
    private val onPageLoadFinish: (() -> Unit)? = null
) : WebViewClientCompat() {

    companion object {
        private const val TAG = "LocalContentClient"
        const val VIRTUAL_HOST = "appassets.androidplatform.net"
        const val VIRTUAL_BASE_URL = "https://$VIRTUAL_HOST"

        /**
         * 智能生成本地虚拟域入口 URL，支持参数与 Hash 路由分离
         */
        fun getVirtualGameUrl(context: Context, gameId: String, entryPath: String): String {
            val localInstallDir = File(context.filesDir, "games/$gameId")
            
            // 分离实际文件路径与 URL Hash/Query 参数
            val hashPart = if (entryPath.contains("#")) "#" + entryPath.substringAfter("#") else ""
            val pathWithoutHash = entryPath.substringBefore("#")
            val queryPart = if (pathWithoutHash.contains("?")) "?" + pathWithoutHash.substringAfter("?") else ""
            val cleanPath = pathWithoutHash.substringBefore("?")

            val fileName = if (cleanPath.contains("/")) cleanPath.substringAfterLast("/") else "index.html"
            val actualFileName = if (fileName.isEmpty()) "index.html" else fileName
            val localFile = File(localInstallDir, actualFileName)

            return if (localFile.exists() && localFile.length() > 0) {
                Log.d(TAG, "VirtualGameUrl: Routing to Local Storage -> $gameId/$actualFileName$queryPart$hashPart")
                "$VIRTUAL_BASE_URL/local-games/$gameId/$actualFileName$queryPart$hashPart"
            } else {
                Log.d(TAG, "VirtualGameUrl: Routing to Assets fallback -> $cleanPath")
                "$VIRTUAL_BASE_URL/assets/games/$cleanPath$queryPart$hashPart"
            }
        }
    }

    private val localGamesDir = File(context.filesDir, "games").apply {
        if (!exists()) mkdirs()
    }

    // 配置双源静态资产拦截器 (Local Storage + Assets)
    private val assetLoader = WebViewAssetLoader.Builder()
        .setDomain(VIRTUAL_HOST)
        // 1. 自定义精准 MIME 类型的本地私有存储拦截器 (/local-games/...)
        .addPathHandler("/local-games/", WebViewAssetLoader.PathHandler { path ->
            try {
                val cleanPath = if (path.startsWith("/")) path.substring(1) else path
                val queryOrHashCleaned = cleanPath.substringBefore("?").substringBefore("#")
                val targetFile = File(localGamesDir, queryOrHashCleaned)

                if (targetFile.exists() && targetFile.isFile) {
                    val mime = guessMimeType(targetFile.name)
                    return@PathHandler WebResourceResponse(mime, "UTF-8", FileInputStream(targetFile))
                }
                null
            } catch (e: Exception) {
                Log.e(TAG, "Error serving local-games file: $path", e)
                null
            }
        })
        // 2. 映射 Android assets 资产目录 (/assets/...)
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        // 3. 针对 Vite 项目 base: '/tank/' 路径特殊处理
        .addPathHandler("/tank/", WebViewAssetLoader.PathHandler { path ->
            try {
                val cleanPath = if (path.startsWith("/")) path.substring(1) else path
                val queryOrHashCleaned = cleanPath.substringBefore("?").substringBefore("#")
                val targetName = if (queryOrHashCleaned.isEmpty()) "index.html" else queryOrHashCleaned
                
                // 优先检查本地用户下载目录
                val localFile = File(localGamesDir, "tank/$targetName")
                if (localFile.exists() && localFile.isFile) {
                    val mime = guessMimeType(targetName)
                    return@PathHandler WebResourceResponse(mime, "UTF-8", FileInputStream(localFile))
                }

                // 兜底读取 assets 内置文件
                val assetPath = "games/tank/$targetName"
                val inputStream = context.assets.open(assetPath)
                val mimeType = guessMimeType(assetPath)
                WebResourceResponse(mimeType, "UTF-8", inputStream)
            } catch (e: Exception) {
                null
            }
        })
        .build()

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val uri = request.url
        // 优先由 WebViewAssetLoader 拦截处理本地资源
        val response = assetLoader.shouldInterceptRequest(uri)
        if (response != null) {
            return response
        }
        return super.shouldInterceptRequest(view, request)
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onPageLoadStart?.invoke()
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onPageLoadFinish?.invoke()
    }

    private fun guessMimeType(path: String): String {
        return when {
            path.endsWith(".html", ignoreCase = true) || path.endsWith(".htm", ignoreCase = true) -> "text/html"
            path.endsWith(".js", ignoreCase = true) || path.endsWith(".mjs", ignoreCase = true) -> "application/javascript"
            path.endsWith(".css", ignoreCase = true) -> "text/css"
            path.endsWith(".json", ignoreCase = true) -> "application/json"
            path.endsWith(".png", ignoreCase = true) -> "image/png"
            path.endsWith(".jpg", ignoreCase = true) || path.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
            path.endsWith(".svg", ignoreCase = true) -> "image/svg+xml"
            path.endsWith(".wasm", ignoreCase = true) -> "application/wasm"
            path.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
            path.endsWith(".ogg", ignoreCase = true) -> "audio/ogg"
            path.endsWith(".wav", ignoreCase = true) -> "audio/wav"
            else -> "application/octet-stream"
        }
    }
}
