package com.tinywebgames.browser.ui.list

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.tinywebgames.browser.R
import com.tinywebgames.browser.databinding.ItemGameCardBinding
import com.tinywebgames.browser.model.DownloadStatus
import com.tinywebgames.browser.model.GameItem

class GameAdapter(
    private var games: List<GameItem>,
    private val onPlayGame: (game: GameItem, forceOnline: Boolean) -> Unit,
    private val onDownloadGame: (game: GameItem, position: Int) -> Unit,
    private val onDeleteOffline: (game: GameItem, position: Int) -> Unit
) : RecyclerView.Adapter<GameAdapter.GameViewHolder>() {

    fun updateData(newGames: List<GameItem>) {
        this.games = newGames
        notifyDataSetChanged()
    }

    fun updateProgress(gameId: String, progress: Int) {
        val index = games.indexOfFirst { it.id == gameId }
        if (index != -1) {
            val item = games[index]
            item.downloadStatus = if (progress >= 100) DownloadStatus.DOWNLOADED else DownloadStatus.DOWNLOADING
            item.downloadProgress = progress
            if (progress >= 100) {
                item.isOfflineReady = true
            }
            notifyItemChanged(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GameViewHolder {
        val binding = ItemGameCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return GameViewHolder(binding)
    }

    override fun onBindViewHolder(holder: GameViewHolder, position: Int) {
        holder.bind(games[position], position)
    }

    override fun getItemCount(): Int = games.size

    inner class GameViewHolder(private val binding: ItemGameCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(game: GameItem, position: Int) {
            binding.tvGameTitle.text = game.title
            val preferredOrientation = com.tinywebgames.browser.utils.OrientationManager.getPreferredOrientation(
                itemView.context,
                game.id,
                game.orientation
            )
            val orientationText = if (preferredOrientation.equals("landscape", ignoreCase = true)) "横屏" else "竖屏"
            binding.tvGameMeta.text = "v${game.version} · $orientationText · ${game.author}"

            // 状态渲染
            when (game.downloadStatus) {
                DownloadStatus.DOWNLOADING -> {
                    binding.layoutDownloadProgress.visibility = View.VISIBLE
                    binding.progressBarDownload.progress = game.downloadProgress
                    binding.tvDownloadProgress.text = "${game.downloadProgress}%"

                    binding.tvGameBadge.text = "下载中"
                    binding.tvGameBadge.setBackgroundResource(R.drawable.bg_badge_online)
                    binding.tvGameBadge.setTextColor(ContextCompat.getColor(itemView.context, R.color.badge_online_text))

                    binding.btnDownload.visibility = View.GONE
                    binding.btnPlayOffline.visibility = View.GONE
                    binding.btnDeleteOffline.visibility = View.GONE
                }
                DownloadStatus.DOWNLOADED -> {
                    binding.layoutDownloadProgress.visibility = View.GONE

                    binding.tvGameBadge.text = "已保存在本地"
                    binding.tvGameBadge.setBackgroundResource(R.drawable.bg_badge_offline)
                    binding.tvGameBadge.setTextColor(ContextCompat.getColor(itemView.context, R.color.badge_offline_text))

                    binding.btnDownload.visibility = View.GONE
                    binding.btnPlayOffline.visibility = View.VISIBLE
                    binding.btnDeleteOffline.visibility = View.VISIBLE
                }
                DownloadStatus.NOT_DOWNLOADED -> {
                    binding.layoutDownloadProgress.visibility = View.GONE

                    binding.tvGameBadge.text = "云端就绪"
                    binding.tvGameBadge.setBackgroundResource(R.drawable.bg_badge_online)
                    binding.tvGameBadge.setTextColor(ContextCompat.getColor(itemView.context, R.color.badge_online_text))

                    binding.btnDownload.visibility = View.VISIBLE
                    binding.btnPlayOffline.visibility = View.GONE
                    binding.btnDeleteOffline.visibility = View.GONE
                }
            }

            // 根据 icon 标识选择图标
            if (game.icon == "ic_tank" || game.id.contains("tank", ignoreCase = true)) {
                binding.ivGameIcon.setImageResource(R.drawable.ic_tank)
            } else if (game.icon == "ic_weiqi" || game.id.contains("go-", ignoreCase = true) || game.id.contains("weiqi", ignoreCase = true)) {
                binding.ivGameIcon.setImageResource(R.drawable.ic_weiqi)
            } else {
                binding.ivGameIcon.setImageResource(R.drawable.ic_gamepad)
            }

            // 在线游玩
            binding.btnPlayOnline.setOnClickListener {
                onPlayGame(game, true)
            }

            // 离线保存
            binding.btnDownload.setOnClickListener {
                onDownloadGame(game, position)
            }

            // 离线秒开
            binding.btnPlayOffline.setOnClickListener {
                onPlayGame(game, false)
            }

            // 删除离线缓存
            binding.btnDeleteOffline.setOnClickListener {
                onDeleteOffline(game, position)
            }

            // 卡片默认点击：已下载走离线，未下载走在线
            binding.root.setOnClickListener {
                if (game.downloadStatus == DownloadStatus.DOWNLOADED) {
                    onPlayGame(game, false)
                } else if (game.downloadStatus != DownloadStatus.DOWNLOADING) {
                    onPlayGame(game, true)
                }
            }
        }
    }
}
