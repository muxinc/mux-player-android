package com.mux.player.offline

import android.annotation.SuppressLint
import android.app.Notification
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import com.mux.player.R

@OptIn(UnstableApi::class)
class MuxDownloadService : DownloadService(
  /* foregroundNotificationId = */ FOREGROUND_SERVICE_NOTIFICATION_ID,
  /*foregroundNotificationUpdateInterval =*/ FOREGROUND_SERVICE_UPDATE_INTERVAL_MS,
  /*channelId =*/ NOTIFICATION_CHANNEL_ID,
  /*channelNameResourceId =*/ R.string.mux_player_download_notification_channel_name,
  /*channelDescriptionResourceId =*/ R.string.mux_player_download_notification_channel_description
) {

  private val notificationHelper by lazy {
    DownloadNotificationHelper(this, NOTIFICATION_CHANNEL_ID)
  }

  override fun onCreate() {
    super.onCreate()
  }

  override fun getDownloadManager(): DownloadManager =
    MuxPlayerDownloadStore.get(this).downloadManager

  @SuppressLint("MissingPermission") // Perms in calling app
  override fun getScheduler(): Scheduler = PlatformScheduler(this, JOB_ID)

  override fun getForegroundNotification(
    downloads: List<Download>,
    notMetRequirements: Int
  ): Notification {
    return notificationHelper.buildProgressNotification(
      /*context=*/ this,
      /*smallIcon=*/ R.drawable.mux_player_ic_offline_download,
      /*contentIntent=*/ null,
      /*message=*/ null,
      /*downloads=*/ downloads,
      /*notMetRequirements=*/ notMetRequirements,
    )
  }

  companion object {
    const val FOREGROUND_SERVICE_NOTIFICATION_ID = 20001
    const val FOREGROUND_SERVICE_UPDATE_INTERVAL_MS = 2_000L

    const val NOTIFICATION_CHANNEL_ID = "com.mux.player.offline.downloads"

    // Identifies the JobScheduler job used to restart downloads
    const val JOB_ID = 1020002
  }
}