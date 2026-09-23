package com.tinywebgames.browser.model

import java.io.Serializable

enum class DownloadStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED
}

data class GameItem(
    val id: String,
    val title: String,
    val description: String,
    val orientation: String = "landscape", // landscape or portrait
    val entryPath: String, // e.g. "tank/index.html"
    val remoteUrl: String,
    val downloadUrl: String? = null, // 离线 zip 包下载地址
    var isOfflineReady: Boolean = false,
    var downloadStatus: DownloadStatus = DownloadStatus.NOT_DOWNLOADED,
    var downloadProgress: Int = 0,
    val icon: String = "ic_gamepad",
    val author: String = "tiny-web-games",
    val version: String = "1.0.0"
) : Serializable
