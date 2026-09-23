package com.tinywebgames.browser.ui.list

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tinywebgames.browser.databinding.ActivityGameListBinding
import com.tinywebgames.browser.model.DownloadStatus
import com.tinywebgames.browser.model.GameItem
import com.tinywebgames.browser.repository.GameDownloadManager
import com.tinywebgames.browser.repository.GameRepository
import com.tinywebgames.browser.ui.game.GameActivity
import kotlinx.coroutines.launch

class GameListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGameListBinding
    private lateinit var adapter: GameAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTopAddressBar()
        setupRecyclerView()
        setupListeners()
        loadData(isInitial = true)
    }

    private fun setupTopAddressBar() {
        // 地址栏右侧【前往】按钮
        binding.btnGoUrl.setOnClickListener {
            handleGoUrl()
        }

        // 软键盘【前往】动作键监听
        binding.etUrlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                handleGoUrl()
                true
            } else {
                false
            }
        }
    }

    private fun handleGoUrl() {
        var rawUrl = binding.etUrlInput.text?.toString()?.trim() ?: ""
        if (rawUrl.isEmpty()) {
            Toast.makeText(this, "请输入要打开的网页游戏网址", Toast.LENGTH_SHORT).show()
            return
        }

        // 自动补齐 https:// 协议前缀
        if (!rawUrl.startsWith("http://", ignoreCase = true) &&
            !rawUrl.startsWith("https://", ignoreCase = true)
        ) {
            rawUrl = "https://$rawUrl"
        }

        val customGame = GameItem(
            id = "custom_" + System.currentTimeMillis(),
            title = "自定义在线页面",
            description = rawUrl,
            orientation = "landscape",
            entryPath = "",
            remoteUrl = rawUrl,
            isOfflineReady = false,
            icon = "ic_gamepad"
        )
        launchGame(customGame, forceOnline = true)
    }

    private fun setupRecyclerView() {
        val spanCount = calculateSpanCount()
        binding.rvGameList.layoutManager = GridLayoutManager(this, spanCount)

        adapter = GameAdapter(
            games = emptyList(),
            onPlayGame = { game, forceOnline ->
                launchGame(game, forceOnline)
            },
            onDownloadGame = { game, position ->
                startDownloadGame(game, position)
            },
            onDeleteOffline = { game, position ->
                confirmDeleteOffline(game, position)
            }
        )
        binding.rvGameList.adapter = adapter
    }

    private fun setupListeners() {
        // 刷新 GitHub 云端清单
        binding.btnRefreshList.setOnClickListener {
            loadData(isInitial = false)
            Toast.makeText(this, "正在同步 GitHub 最新清单...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startDownloadGame(game: GameItem, position: Int) {
        game.downloadStatus = DownloadStatus.DOWNLOADING
        game.downloadProgress = 0
        adapter.notifyItemChanged(position)

        lifecycleScope.launch {
            GameDownloadManager.downloadGame(
                context = this@GameListActivity,
                game = game,
                onProgress = { pct ->
                    runOnUiThread {
                        adapter.updateProgress(game.id, pct)
                    }
                },
                onComplete = { success, errorMsg ->
                    runOnUiThread {
                        if (success) {
                            adapter.updateProgress(game.id, 100)
                            Toast.makeText(
                                this@GameListActivity,
                                "【${game.title}】已成功离线到本地！无需联网即可秒开。",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            game.downloadStatus = DownloadStatus.NOT_DOWNLOADED
                            adapter.notifyItemChanged(position)
                            Toast.makeText(
                                this@GameListActivity,
                                "下载失败: ${errorMsg ?: "网络错误"}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            )
        }
    }

    private fun confirmDeleteOffline(game: GameItem, position: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle("清理本地离线包")
            .setMessage("确定要清除【${game.title}】的本地离线缓存吗？清除后依然可以在线游玩，或随时重新下载。")
            .setPositiveButton("清除") { _, _ ->
                GameDownloadManager.deleteGameOfflineData(this, game.id)
                GameRepository.deleteCustomGameRecord(this, game.id)
                Toast.makeText(this, "已清除【${game.title}】本地离线包", Toast.LENGTH_SHORT).show()
                loadData(isInitial = false)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // 从全屏游戏页面返回时，自动刷新最新离线收录的游戏
        loadData(isInitial = false)
    }

    private fun loadData(isInitial: Boolean = false) {
        lifecycleScope.launch {
            val games = GameRepository.loadGames(this@GameListActivity, forceRemote = !isInitial)
            adapter.updateData(games)
            binding.tvGameCount.text = "共 ${games.size} 款游戏"
        }
    }

    private fun calculateSpanCount(): Int {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val smallestScreenWidthDp = resources.configuration.smallestScreenWidthDp
        val isTablet = smallestScreenWidthDp >= 600

        return when {
            isTablet && isLandscape -> 3
            isTablet && !isLandscape -> 2
            isLandscape -> 2
            else -> 1
        }
    }

    private fun launchGame(game: GameItem, forceOnline: Boolean = false) {
        val intent = Intent(this, GameActivity::class.java).apply {
            putExtra(GameActivity.EXTRA_GAME, game)
            putExtra(GameActivity.EXTRA_FORCE_ONLINE, forceOnline)
        }
        startActivity(intent)
    }
}
