package com.tinywebgames.browser.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tinywebgames.browser.model.DownloadStatus
import com.tinywebgames.browser.model.GameItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object GameRepository {
    private const val TAG = "GameRepository"
    private const val GAMES_JSON_FILE = "games.json"
    private const val CUSTOM_GAMES_FILE = "custom_games.json"

    // GitHub 组织公开仓库列表 API
    const val GITHUB_ORG_REPOS_API = "https://api.github.com/orgs/tiny-web-games/repos"

    private data class GitHubRepo(
        val name: String,
        val description: String?,
        val homepage: String?,
        val html_url: String?
    )

    suspend fun loadGames(context: Context, forceRemote: Boolean = false): List<GameItem> =
        withContext(Dispatchers.IO) {
            val officialList = mutableListOf<GameItem>()
            var fetchedFromRemote = false

            // 1. 优先读取本地 assets/games.json 预置信息作为基础字典
            val localPresetMap = mutableMapOf<String, GameItem>()
            try {
                context.assets.open(GAMES_JSON_FILE).use { inputStream ->
                    InputStreamReader(inputStream).use { reader ->
                        val type = object : TypeToken<List<GameItem>>() {}.type
                        val parsed: List<GameItem> = Gson().fromJson(reader, type)
                        for (item in parsed) {
                            localPresetMap[item.id] = item
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load local fallback games.json", e)
            }

            // 2. 尝试从 GitHub API 动态拉取 tiny-web-games 下的全部游戏仓库
            try {
                val reposJson = fetchRemoteJson(GITHUB_ORG_REPOS_API)
                if (!reposJson.isNullOrBlank() && reposJson.trim().startsWith("[")) {
                    val repoType = object : TypeToken<List<GitHubRepo>>() {}.type
                    val repos: List<GitHubRepo> = Gson().fromJson(reposJson, repoType)
                    
                    for (repo in repos) {
                        // 过滤掉非游戏的元数据仓库（如 .github）
                        if (repo.name.startsWith(".")) continue

                        val preset = localPresetMap[repo.name]
                        val homeUrl = if (!repo.homepage.isNullOrBlank()) repo.homepage else "https://tiny-web-games.github.io/${repo.name}/"
                        val iconName = preset?.icon ?: if (repo.name.contains("go-", ignoreCase = true) || repo.name.contains("weiqi", ignoreCase = true)) "ic_weiqi" else if (repo.name.contains("tank", ignoreCase = true)) "ic_tank" else "ic_gamepad"
                        val title = preset?.title ?: formatRepoTitle(repo.name)
                        val desc = if (!repo.description.isNullOrBlank()) repo.description else (preset?.description ?: "tiny-web-games 网页游戏")

                        val gameItem = GameItem(
                            id = repo.name,
                            title = title,
                            description = desc,
                            orientation = preset?.orientation ?: "landscape",
                            entryPath = "${repo.name}/index.html",
                            remoteUrl = homeUrl,
                            downloadUrl = preset?.downloadUrl ?: "https://github.com/tiny-web-games/${repo.name}/archive/refs/heads/gh-pages.zip",
                            isOfflineReady = false,
                            downloadStatus = DownloadStatus.NOT_DOWNLOADED,
                            icon = iconName,
                            author = "tiny-web-games",
                            version = preset?.version ?: "1.0.0"
                        )
                        officialList.add(gameItem)
                    }
                    fetchedFromRemote = true
                    Log.i(TAG, "Successfully loaded ${officialList.size} games directly from tiny-web-games GitHub API!")
                }
            } catch (e: Exception) {
                Log.w(TAG, "GitHub API fetch failed, fallback to local preset", e)
            }

            // 3. 若从 GitHub 动态获取失败或为空，直接使用本地预置列表
            if (!fetchedFromRemote || officialList.isEmpty()) {
                officialList.addAll(localPresetMap.values)
            }

            // 4. 读取用户手动输入并离线保存的自定义游戏 (custom_games.json)
            val customList = loadCustomGamesInternal(context)

            // 5. 合并去重列表（自定义游戏优先置顶）
            val combinedList = mutableListOf<GameItem>()
            val addedIds = mutableSetOf<String>()

            for (custom in customList) {
                if (addedIds.add(custom.id)) {
                    combinedList.add(custom)
                }
            }
            for (official in officialList) {
                if (addedIds.add(official.id)) {
                    combinedList.add(official)
                }
            }

            // 6. 校验并标记每个游戏的本地离线状态（仅检查应用私有目录是否已下载保存）
            for (game in combinedList) {
                val isDownloadedInStorage = GameDownloadManager.isGameDownloaded(context, game)

                if (isDownloadedInStorage) {
                    game.isOfflineReady = true
                    game.downloadStatus = DownloadStatus.DOWNLOADED
                } else {
                    game.isOfflineReady = false
                    game.downloadStatus = if (GameDownloadManager.isDownloading(game.id)) {
                        DownloadStatus.DOWNLOADING
                    } else {
                        DownloadStatus.NOT_DOWNLOADED
                    }
                }
            }

            combinedList
        }

    private fun formatRepoTitle(name: String): String {
        return when (name.lowercase()) {
            "tank" -> "钢铁咆哮 KV-44 (Tank)"
            "go-little-adventure" -> "围棋小萌主 (Go Adventure)"
            else -> name.split("-").joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
        }
    }

    /**
     * 保存用户自定义网页游戏并持久化
     */
    fun saveCustomGame(context: Context, game: GameItem) {
        synchronized(this) {
            val list = loadCustomGamesInternal(context).toMutableList()
            list.removeAll { it.id == game.id }
            list.add(0, game)
            val file = File(context.filesDir, CUSTOM_GAMES_FILE)
            file.writeText(Gson().toJson(list))
        }
    }

    /**
     * 删除自定义游戏元数据
     */
    fun deleteCustomGameRecord(context: Context, gameId: String) {
        synchronized(this) {
            val list = loadCustomGamesInternal(context).toMutableList()
            val removed = list.removeAll { it.id == gameId }
            if (removed) {
                val file = File(context.filesDir, CUSTOM_GAMES_FILE)
                file.writeText(Gson().toJson(list))
            }
        }
    }

    private fun loadCustomGamesInternal(context: Context): List<GameItem> {
        val file = File(context.filesDir, CUSTOM_GAMES_FILE)
        if (!file.exists() || file.length() == 0L) return emptyList()
        return try {
            val type = object : TypeToken<List<GameItem>>() {}.type
            Gson().fromJson(file.readText(), type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun fetchRemoteJson(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:109.0)")
            conn.instanceFollowRedirects = true
            conn.connect()

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
