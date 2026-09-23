package com.tinywebgames.browser.repository

import android.content.Context
import android.util.Log
import com.tinywebgames.browser.model.GameItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object GameDownloadManager {
    private const val TAG = "GameDownloadManager"

    // 进度监听器列表
    private val progressListeners = ConcurrentHashMap<String, (Int) -> Unit>()
    // 正在下载的游戏集合
    private val activeDownloads = ConcurrentHashMap.newKeySet<String>()

    fun registerListener(gameId: String, listener: (Int) -> Unit) {
        progressListeners[gameId] = listener
    }

    fun unregisterListener(gameId: String) {
        progressListeners.remove(gameId)
    }

    fun isDownloading(gameId: String): Boolean = activeDownloads.contains(gameId)

    /**
     * 获取已下载游戏的本地物理目录
     */
    fun getGameInstallDir(context: Context, gameId: String): File {
        val root = File(context.filesDir, "games")
        return File(root, gameId)
    }

    /**
     * 检查游戏是否已经下载保存在本地目录中
     */
    fun isGameDownloaded(context: Context, game: GameItem): Boolean {
        val installDir = getGameInstallDir(context, game.id)
        if (!installDir.exists() || !installDir.isDirectory) return false
        val entryName = if (game.entryPath.contains("/")) game.entryPath.substringAfterLast("/") else "index.html"
        val entryFile = File(installDir, entryName)
        return entryFile.exists() && entryFile.length() > 0
    }

    /**
     * 启动下载并解压游戏
     */
    suspend fun downloadGame(
        context: Context,
        game: GameItem,
        onProgress: (Int) -> Unit,
        onComplete: (Boolean, String?) -> Unit
    ) = withContext(Dispatchers.IO) {
        val gameId = game.id
        if (activeDownloads.contains(gameId)) return@withContext

        activeDownloads.add(gameId)
        val installDir = getGameInstallDir(context, gameId)
        val tempZip = File(context.cacheDir, "${gameId}_temp.zip")

        try {
            var downloadSuccess = false
            val downloadUrl = game.downloadUrl

            // 1. 如果配置了可用的远程 zip 链接，优先尝试真实网络下载
            if (!downloadUrl.isNullOrBlank()) {
                downloadSuccess = tryDownloadFromUrl(downloadUrl, tempZip) { pct ->
                    // 下载占 80% 进度
                    val mapped = (pct * 0.8).toInt()
                    notifyProgress(gameId, mapped, onProgress)
                }
            }

            // 2. 如果网络下载未成功或未提供在线 zip，启用 assets 离线包平滑模拟下载
            if (!downloadSuccess) {
                simulateOrExtractFromAssets(context, game) { pct ->
                    notifyProgress(gameId, pct, onProgress)
                }
            } else {
                // 解压 tempZip 到 installDir
                notifyProgress(gameId, 85, onProgress)
                if (installDir.exists()) installDir.deleteRecursively()
                installDir.mkdirs()
                unzip(tempZip, installDir)
                notifyProgress(gameId, 100, onProgress)
            }

            tempZip.delete()
            activeDownloads.remove(gameId)
            withContext(Dispatchers.Main) {
                onComplete(true, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for ${game.title}", e)
            tempZip.delete()
            activeDownloads.remove(gameId)
            withContext(Dispatchers.Main) {
                onComplete(false, e.localizedMessage)
            }
        }
    }

    private suspend fun simulateOrExtractFromAssets(
        context: Context,
        game: GameItem,
        onProgress: (Int) -> Unit
    ) {
        val installDir = getGameInstallDir(context, game.id)
        if (installDir.exists()) installDir.deleteRecursively()
        installDir.mkdirs()

        // 模拟平滑进度以提供良好的视觉反馈
        val steps = listOf(15, 35, 60, 85)
        for (step in steps) {
            onProgress(step)
            delay(150)
        }

        // 从 assets 拷贝到本地 installDir
        val assetDir = "games/${game.id}"
        copyAssetFolder(context, assetDir, installDir)

        onProgress(100)
    }

    private fun copyAssetFolder(context: Context, assetPath: String, targetDir: File) {
        val list = context.assets.list(assetPath) ?: return
        if (list.isEmpty()) {
            // 是文件
            context.assets.open(assetPath).use { input ->
                FileOutputStream(targetDir).use { output ->
                    input.copyTo(output)
                }
            }
        } else {
            // 是目录
            targetDir.mkdirs()
            for (file in list) {
                val subAsset = if (assetPath.isEmpty()) file else "$assetPath/$file"
                val subTarget = File(targetDir, file)
                copyAssetFolder(context, subAsset, subTarget)
            }
        }
    }

    private fun tryDownloadFromUrl(urlStr: String, destFile: File, onProgress: (Int) -> Unit): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 12000
            conn.requestMethod = "GET"
            conn.connect()

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                return false
            }

            val fileLength = conn.contentLength
            conn.inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    val data = ByteArray(4096)
                    var total: Long = 0
                    var count: Int
                    while (input.read(data).also { count = it } != -1) {
                        total += count
                        output.write(data, 0, count)
                        if (fileLength > 0) {
                            val pct = ((total * 100) / fileLength).toInt()
                            onProgress(pct)
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Network download error for $urlStr", e)
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun unzip(zipFile: File, targetDir: File) {
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val file = File(targetDir, entry.name)
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
    }

    private fun notifyProgress(gameId: String, pct: Int, localListener: (Int) -> Unit) {
        localListener(pct)
        progressListeners[gameId]?.invoke(pct)
    }

    /**
     * 删除并清除本地离线缓存
     */
    fun deleteGameOfflineData(context: Context, gameId: String): Boolean {
        val dir = getGameInstallDir(context, gameId)
        return if (dir.exists()) {
            dir.deleteRecursively()
        } else {
            true
        }
    }
}
