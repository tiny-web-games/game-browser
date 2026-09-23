package com.tinywebgames.browser.repository

import android.content.Context
import android.util.Log
import com.tinywebgames.browser.model.DownloadStatus
import com.tinywebgames.browser.model.GameItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object WebGameSaver {
    private const val TAG = "WebGameSaver"

    // 匹配 GitHub Pages 格式: https://<owner>.github.io/<repo>/...
    private val GITHUB_PAGES_PATTERN =
        Pattern.compile("https?://([a-zA-Z0-9_-]+)\\.github\\.io/([a-zA-Z0-9_-]+)", Pattern.CASE_INSENSITIVE)

    /**
     * 将在线网页游戏离线固化并保存至本地大厅
     */
    suspend fun saveOnlineGame(
        context: Context,
        rawUrl: String,
        currentWebTitle: String?,
        onProgress: (Int, String) -> Unit
    ): Result<GameItem> = withContext(Dispatchers.IO) {
        try {
            val cleanUrl = rawUrl.trim()
            val matcher = GITHUB_PAGES_PATTERN.matcher(cleanUrl)

            if (matcher.find()) {
                val owner = matcher.group(1) ?: "github"
                val repo = matcher.group(2) ?: "game"
                val gameId = repo.lowercase()

                onProgress(10, "正在识别 GitHub 仓库: $owner/$repo ...")
                val ghPagesZipUrl = "https://github.com/$owner/$repo/archive/refs/heads/gh-pages.zip"

                val success = downloadAndExtractGhPages(context, ghPagesZipUrl, gameId, onProgress)
                if (success) {
                    val gameTitle = if (!currentWebTitle.isNullOrBlank() && currentWebTitle != "网页游戏浏览器") {
                        currentWebTitle
                    } else {
                        repo.replace("-", " ").replaceFirstChar { it.uppercase() }
                    }

                    // 检查原始 URL 是否带 hash 锚点
                    val hashSuffix = if (cleanUrl.contains("#")) "#" + cleanUrl.substringAfter("#") else ""

                    val gameItem = GameItem(
                        id = gameId,
                        title = gameTitle,
                        description = "离线保存自: $cleanUrl",
                        orientation = "landscape",
                        entryPath = "$gameId/index.html$hashSuffix",
                        remoteUrl = cleanUrl,
                        downloadUrl = ghPagesZipUrl,
                        isOfflineReady = true,
                        downloadStatus = DownloadStatus.DOWNLOADED,
                        icon = "ic_gamepad",
                        author = owner,
                        version = "1.0.0"
                    )

                    // 持久化收录至大厅并清除旧的临时错误记录
                    GameRepository.saveCustomGame(context, gameItem)
                    onProgress(100, "离线保存成功！")
                    return@withContext Result.success(gameItem)
                } else {
                    Log.w(TAG, "GitHub gh-pages 下载失败，尝试切换到页面递归抓取引擎")
                }
            }

            // 通用网页深度资源抓取引擎
            onProgress(20, "正在深度抓取网页静态资源...")
            val fallbackItem = crawlAndSaveGenericWebPage(context, cleanUrl, currentWebTitle, onProgress)
            GameRepository.saveCustomGame(context, fallbackItem)
            onProgress(100, "离线保存成功！")
            Result.success(fallbackItem)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save online game: $rawUrl", e)
            Result.failure(e)
        }
    }

    private suspend fun downloadAndExtractGhPages(
        context: Context,
        zipUrl: String,
        gameId: String,
        onProgress: (Int, String) -> Unit
    ): Boolean {
        val tempZip = File(context.cacheDir, "temp_${gameId}_gh_pages.zip")
        try {
            onProgress(20, "正在连接 GitHub 下载静态代码包...")
            val downloaded = downloadWithRedirects(zipUrl, tempZip) { pct ->
                onProgress(20 + (pct * 0.55).toInt(), "正在下载离线资源包 ($pct%)...")
            }
            if (!downloaded || !tempZip.exists() || tempZip.length() == 0L) {
                Log.w(TAG, "Zip download failed or file empty: $zipUrl")
                tempZip.delete()
                return false
            }

            onProgress(80, "正在解压并部署游戏到本地私有存储...")
            val targetDir = File(context.filesDir, "games/$gameId")
            if (targetDir.exists()) targetDir.deleteRecursively()
            targetDir.mkdirs()

            val unzipped = unzipAndFlatten(tempZip, targetDir)
            tempZip.delete()
            return unzipped
        } catch (e: Exception) {
            Log.w(TAG, "downloadAndExtractGhPages error: $zipUrl", e)
            tempZip.delete()
            return false
        }
    }

    /**
     * 自动处理 301/302/303/307 跨域名重定向的健壮下载器
     */
    private fun downloadWithRedirects(urlStr: String, destFile: File, onPct: (Int) -> Unit): Boolean {
        var curUrl = urlStr
        var redirects = 0
        val maxRedirects = 8

        while (redirects < maxRedirects) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(curUrl)
                conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 12000
                conn.readTimeout = 20000
                conn.instanceFollowRedirects = false // 手动处理，支持跨域名跳转
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:109.0) Gecko/109.0 Firefox/119.0")
                conn.connect()

                val code = conn.responseCode
                Log.d(TAG, "HTTP $code from $curUrl")

                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location.isNullOrEmpty()) return false
                    curUrl = if (location.startsWith("http://", ignoreCase = true) || location.startsWith("https://", ignoreCase = true)) {
                        location
                    } else {
                        URL(url, location).toString()
                    }
                    redirects++
                    continue
                }

                if (code != HttpURLConnection.HTTP_OK) {
                    conn.disconnect()
                    return false
                }

                val length = conn.contentLength
                conn.inputStream.use { input ->
                    FileOutputStream(destFile).use { output ->
                        val buffer = ByteArray(8192)
                        var count: Int
                        var total: Long = 0
                        while (input.read(buffer).also { count = it } != -1) {
                            total += count
                            output.write(buffer, 0, count)
                            if (length > 0) {
                                onPct(((total * 100) / length).toInt())
                            }
                        }
                    }
                }
                conn.disconnect()
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Download error on $curUrl", e)
                conn?.disconnect()
                return false
            }
        }
        return false
    }

    private fun unzipAndFlatten(zipFile: File, targetDir: File): Boolean {
        val tempExtract = File(zipFile.parentFile, "unzip_${System.currentTimeMillis()}")
        tempExtract.mkdirs()

        try {
            // 1. 解压全部文件
            ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val file = File(tempExtract, entry.name)
                    if (entry.isDirectory) {
                        file.mkdirs()
                    } else {
                        file.parentFile?.mkdirs()
                        FileOutputStream(file).use { fos ->
                            zis.copyTo(fos)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            // 2. 找到包含 index.html 的目录，拷贝到 targetDir
            val indexFile = findIndexHtml(tempExtract)
            if (indexFile == null) {
                Log.w(TAG, "No index.html found in unzipped archive")
                tempExtract.deleteRecursively()
                return false
            }

            val sourceDir = indexFile.parentFile ?: tempExtract
            sourceDir.copyRecursively(targetDir, overwrite = true)
            tempExtract.deleteRecursively()

            val finalIndex = File(targetDir, "index.html")
            return finalIndex.exists() && finalIndex.length() > 0
        } catch (e: Exception) {
            Log.e(TAG, "unzipAndFlatten failed", e)
            tempExtract.deleteRecursively()
            return false
        }
    }

    private fun findIndexHtml(root: File): File? {
        if (!root.exists()) return null
        val direct = File(root, "index.html")
        if (direct.exists()) return direct
        root.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                val found = findIndexHtml(file)
                if (found != null) return found
            }
        }
        return null
    }

    private suspend fun crawlAndSaveGenericWebPage(
        context: Context,
        pageUrl: String,
        currentWebTitle: String?,
        onProgress: (Int, String) -> Unit
    ): GameItem {
        val cleanUrlWithoutHash = pageUrl.substringBefore("#")
        val baseUrl = if (cleanUrlWithoutHash.endsWith("/")) cleanUrlWithoutHash else cleanUrlWithoutHash.substringBeforeLast("/") + "/"
        val gameId = "web_" + Math.abs(cleanUrlWithoutHash.hashCode())
        val targetDir = File(context.filesDir, "games/$gameId")
        if (targetDir.exists()) targetDir.deleteRecursively()
        targetDir.mkdirs()

        onProgress(30, "正在下载页面 HTML 入口...")
        val htmlContent = URL(cleanUrlWithoutHash).readText()
        File(targetDir, "index.html").writeText(htmlContent)

        // 解析并下载 script 和 link 样式
        val assetsDir = File(targetDir, "assets")
        assetsDir.mkdirs()

        val srcPattern = Pattern.compile("(?:src|href)=[\"']([^\"']+\\.(?:js|css|svg|png|jpg|wasm))[\"']", Pattern.CASE_INSENSITIVE)
        val matcher = srcPattern.matcher(htmlContent)
        var count = 0

        while (matcher.find()) {
            val relativePath = matcher.group(1) ?: continue
            if (relativePath.startsWith("http://") || relativePath.startsWith("https://")) {
                continue
            }
            val assetClean = relativePath.removePrefix("./").removePrefix("/")
            val fullAssetUrl = baseUrl + assetClean
            val destFile = File(targetDir, assetClean)
            destFile.parentFile?.mkdirs()

            try {
                downloadWithRedirects(fullAssetUrl, destFile) {}
                count++
            } catch (e: Exception) {
                Log.w(TAG, "Failed to download asset: $fullAssetUrl", e)
            }
        }

        onProgress(90, "已抓取核心资源 $count 个，正在配置大厅...")

        val title = if (!currentWebTitle.isNullOrBlank() && currentWebTitle != "网页游戏浏览器") {
            currentWebTitle
        } else {
            extractTitle(htmlContent) ?: "在线网页游戏"
        }

        val hashSuffix = if (pageUrl.contains("#")) "#" + pageUrl.substringAfter("#") else ""

        return GameItem(
            id = gameId,
            title = title,
            description = "离线固化自: $pageUrl",
            orientation = "landscape",
            entryPath = "$gameId/index.html$hashSuffix",
            remoteUrl = pageUrl,
            isOfflineReady = true,
            downloadStatus = DownloadStatus.DOWNLOADED,
            icon = "ic_gamepad",
            author = "自定义保存",
            version = "1.0.0"
        )
    }

    private fun extractTitle(html: String): String? {
        val matcher = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE).matcher(html)
        return if (matcher.find()) matcher.group(1)?.trim() else null
    }
}
