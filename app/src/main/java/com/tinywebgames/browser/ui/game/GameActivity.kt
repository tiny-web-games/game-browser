package com.tinywebgames.browser.ui.game

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tinywebgames.browser.R
import com.tinywebgames.browser.databinding.ActivityGameBinding
import com.tinywebgames.browser.model.GameItem
import com.tinywebgames.browser.utils.FullscreenHelper
import com.tinywebgames.browser.utils.LocalContentWebViewClient
import kotlinx.coroutines.launch

class GameActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_GAME = "extra_game_item"
        const val EXTRA_FORCE_ONLINE = "extra_force_online"
    }

    private lateinit var binding: ActivityGameBinding
    private var gameItem: GameItem? = null
    private var isCurrentOnlineMode: Boolean = false
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val handler = Handler(Looper.getMainLooper())
    private var isPanelExpanded = false
    private val autoCollapseRunnable = Runnable {
        collapseFloatingPanel()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. 初始化全屏模式
        FullscreenHelper.enableImmersiveFullscreen(window)
        FullscreenHelper.setKeepScreenOn(window, true)

        binding = ActivityGameBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 2. 解析游戏参数
        @Suppress("DEPRECATION")
        gameItem = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(EXTRA_GAME, GameItem::class.java)
        } else {
            intent.getSerializableExtra(EXTRA_GAME) as? GameItem
        }

        val forceOnline = intent.getBooleanExtra(EXTRA_FORCE_ONLINE, false)
        isCurrentOnlineMode = forceOnline || (gameItem?.isOfflineReady != true)

        if (gameItem == null) {
            Toast.makeText(this, "未找到游戏信息", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 3. 锁定屏幕方向
        setupOrientation()

        // 4. 配置 WebView
        setupWebView()

        // 5. 配置悬浮控制面板与防误退拦截
        setupFloatingControls()
        setupBackInterceptor()

        // 6. 加载游戏内容
        loadGameContent()
    }

    private fun setupOrientation() {
        val game = gameItem ?: return
        val savedOrientation = com.tinywebgames.browser.utils.OrientationManager.getPreferredOrientation(
            this,
            game.id,
            game.orientation
        )
        applyOrientation(savedOrientation)
    }

    private fun applyOrientation(orientation: String) {
        if (orientation.equals("portrait", ignoreCase = true)) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView
        val settings = webView.settings

        // 核心性能与现代 Web 支持
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false // 使用 WebViewAssetLoader 代替不安全的文件直接访问
        settings.allowContentAccess = false

        // 视图与缩放优化
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false

        // 缓存策略：离线时优先读缓存
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // 硬件加速
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // WebClient 配置
        webView.webViewClient = LocalContentWebViewClient(
            context = this,
            onPageLoadStart = {
                binding.loadingBar.visibility = View.VISIBLE
            },
            onPageLoadFinish = {
                binding.loadingBar.visibility = View.GONE
            }
        )

        // WebChromeClient 支持 HTML5 视频全屏以及加载进度
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.loadingBar.progress = newProgress
                if (newProgress >= 100) {
                    binding.loadingBar.visibility = View.GONE
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                binding.webView.visibility = View.GONE
                binding.customViewContainer.visibility = View.VISIBLE
                binding.customViewContainer.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }

            override fun onHideCustomView() {
                if (customView == null) return
                binding.customViewContainer.removeView(customView)
                customView = null
                binding.customViewContainer.visibility = View.GONE
                binding.webView.visibility = View.VISIBLE
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
            }
        }
    }

    private fun loadGameContent() {
        val game = gameItem ?: return
        if (!isCurrentOnlineMode && game.isOfflineReady) {
            val virtualUrl = LocalContentWebViewClient.getVirtualGameUrl(this, game.id, game.entryPath)
            Toast.makeText(this, getString(R.string.mode_offline_hint), Toast.LENGTH_SHORT).show()
            binding.btnToggleSource.setImageResource(R.drawable.ic_globe)
            binding.btnToggleSource.contentDescription = "切换到在线模式"
            binding.webView.loadUrl(virtualUrl)
        } else {
            Toast.makeText(this, getString(R.string.mode_online_hint), Toast.LENGTH_SHORT).show()
            binding.btnToggleSource.setImageResource(R.drawable.ic_bolt)
            binding.btnToggleSource.contentDescription = "切换到离线模式"
            binding.webView.loadUrl(game.remoteUrl)
        }
    }

    private fun setupFloatingControls() {
        // 点击悬浮球展开/收起面板
        binding.btnFloatingBall.setOnClickListener {
            toggleFloatingPanel()
        }

        // 退出游戏按钮
        binding.btnExitGame.setOnClickListener {
            showExitConfirmDialog()
        }

        // 重新加载按钮
        binding.btnReloadGame.setOnClickListener {
            binding.webView.reload()
            collapseFloatingPanel()
            Toast.makeText(this, "正在刷新游戏...", Toast.LENGTH_SHORT).show()
        }

        // 切换横竖屏并持久化记忆
        binding.btnRotateScreen.setOnClickListener {
            val game = gameItem ?: return@setOnClickListener
            val currentOrientation = resources.configuration.orientation
            val newOrientation = if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                "portrait"
            } else {
                "landscape"
            }
            applyOrientation(newOrientation)
            com.tinywebgames.browser.utils.OrientationManager.savePreferredOrientation(
                this,
                game.id,
                newOrientation
            )
            val orientationName = if (newOrientation == "landscape") "横屏" else "竖屏"
            Toast.makeText(this, "已切换并记住该游戏偏好为【$orientationName】", Toast.LENGTH_SHORT).show()
            collapseFloatingPanel()
        }

        // 切换在线/离线源
        binding.btnToggleSource.setOnClickListener {
            val game = gameItem
            if (game == null) return@setOnClickListener

            if (!game.isOfflineReady) {
                Toast.makeText(this, "该游戏未打包离线资源，仅支持在线游玩", Toast.LENGTH_SHORT).show()
            } else {
                isCurrentOnlineMode = !isCurrentOnlineMode
                loadGameContent()
                collapseFloatingPanel()
            }
        }

        // 保存此游戏为离线包并加入大厅
        binding.btnSaveOffline.setOnClickListener {
            handleSaveCurrentGameOffline()
        }
    }

    private fun handleSaveCurrentGameOffline() {
        val game = gameItem ?: return
        val currentUrl = binding.webView.url ?: game.remoteUrl
        val webTitle = binding.webView.title ?: game.title

        MaterialAlertDialogBuilder(this)
            .setTitle("保存为此游戏离线包")
            .setMessage("确定要将【$webTitle】离线保存到平板本地吗？\n保存后将永久收录在首页游戏大厅，支持断网秒开！")
            .setPositiveButton("立即保存") { _, _ ->
                collapseFloatingPanel()
                executeSaveOffline(currentUrl, webTitle)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun executeSaveOffline(currentUrl: String, webTitle: String) {
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle("正在离线保存游戏...")
            .setMessage("正在分析并下载游戏静态资源包...")
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val result = com.tinywebgames.browser.repository.WebGameSaver.saveOnlineGame(
                context = this@GameActivity,
                rawUrl = currentUrl,
                currentWebTitle = webTitle,
                onProgress = { _, statusText ->
                    runOnUiThread {
                        progressDialog.setMessage(statusText)
                    }
                }
            )

            progressDialog.dismiss()
            if (result.isSuccess) {
                val savedItem = result.getOrNull()
                if (savedItem != null) {
                    gameItem = savedItem
                    isCurrentOnlineMode = false
                    Toast.makeText(
                        this@GameActivity,
                        "【${savedItem.title}】已成功离线并添加至大厅！现在为您切至离线秒开模式。",
                        Toast.LENGTH_LONG
                    ).show()
                    loadGameContent()
                }
            } else {
                Toast.makeText(
                    this@GameActivity,
                    "离线保存失败: ${result.exceptionOrNull()?.localizedMessage ?: "网络异常"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun toggleFloatingPanel() {
        if (isPanelExpanded) {
            collapseFloatingPanel()
        } else {
            expandFloatingPanel()
        }
    }

    private fun expandFloatingPanel() {
        isPanelExpanded = true
        binding.floatingPanel.visibility = View.VISIBLE
        binding.btnFloatingBall.alpha = 0.9f
        // 5秒无操作自动收起
        handler.removeCallbacks(autoCollapseRunnable)
        handler.postDelayed(autoCollapseRunnable, 5000)
    }

    private fun collapseFloatingPanel() {
        isPanelExpanded = false
        binding.floatingPanel.visibility = View.GONE
        binding.btnFloatingBall.alpha = 0.35f
        handler.removeCallbacks(autoCollapseRunnable)
    }

    private fun setupBackInterceptor() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 如果当前在全屏视频模式，优先退出全屏视频
                if (customView != null) {
                    binding.webView.webChromeClient?.onHideCustomView()
                    return
                }
                // 弹出退出确认弹窗，防止误触手势退出游戏
                showExitConfirmDialog()
            }
        })
    }

    private fun showExitConfirmDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.exit_confirm_title)
            .setMessage(R.string.exit_confirm_message)
            .setPositiveButton(R.string.action_exit) { _, _ ->
                finish()
            }
            .setNegativeButton(R.string.action_cancel) { dialog, _ ->
                dialog.dismiss()
                FullscreenHelper.enableImmersiveFullscreen(window)
            }
            .setOnDismissListener {
                FullscreenHelper.enableImmersiveFullscreen(window)
            }
            .show()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            FullscreenHelper.enableImmersiveFullscreen(window)
        }
    }

    override fun onResume() {
        super.onResume()
        FullscreenHelper.enableImmersiveFullscreen(window)
        binding.webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.webView.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoCollapseRunnable)
        binding.webView.destroy()
        super.onDestroy()
    }
}
