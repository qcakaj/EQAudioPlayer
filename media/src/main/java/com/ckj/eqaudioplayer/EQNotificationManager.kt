package com.ckj.eqaudioplayer

import android.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat.*
import android.content.Context
import android.content.res.Resources
import androidx.core.graphics.toColorInt


class EQNotificationManager(private val context: Context, private val audioService: MediaService) {
  private val notificationManager : NotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    //AudioPlayer notification ID
    internal val NOTIFICATION_ID = 101

    internal fun buildNotification(playbackStatus: PlaybackStatus, resources: Resources?=null): Builder {

        var notificationAction = R.drawable.ic_media_pause //needs to be initialized
        var play_pauseAction: PendingIntent? = null

        //Build a new notification according to the current state of the MediaPlayer
        if (playbackStatus === PlaybackStatus.PLAYING) {
            notificationAction = R.drawable.ic_media_pause
            //create the pause action
            play_pauseAction = playbackAction(1,context)
        } else if (playbackStatus === PlaybackStatus.PAUSED) {
            notificationAction = R.drawable.ic_media_play
            //create the play action
            play_pauseAction = playbackAction(0,context)
        }
        val largeIcon = BitmapFactory.decodeResource(
            resources,
            R.drawable.ic_dialog_email
        ) //replace with your own image

        // Create a new Notification
        // 1. Create Notification Channel for O+ and beyond devices (26+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager.notificationChannels.isEmpty()) {

            val notificationChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID, "Audio", NotificationManager.IMPORTANCE_DEFAULT
            )

            // Adds NotificationChannel to system. Attempting to create an
            // existing notification channel with its original values performs
            // no operation, so it's safe to perform the below sequence.
            notificationManager.createNotificationChannel(notificationChannel)
        }

        return notificationBuilder(largeIcon, notificationAction, play_pauseAction)
    }

    private fun notificationBuilder(
        largeIcon: Bitmap?,
        notificationAction: Int,
        play_pauseAction: PendingIntent?,
    ): Builder {
        return Builder(context, NOTIFICATION_CHANNEL_ID)
            .setShowWhen(false)
            .setOngoing(audioService.getMediaPlayer()?.isPlaying == true)// Set the Notification style
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle() // Attach our MediaSession token
                    .setMediaSession(audioService.getMediaSession()) // Show our playback controls in the compact notification view.
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setColor(
                "#800080".toColorInt()
            )
            .setSmallIcon(R.drawable.stat_sys_headset) // Set Notification content information
            .setContentText(audioService.getCurrentAudio()?.artist)
            .setContentTitle(audioService.getCurrentAudio()?.title)
            .setContentInfo(audioService.getCurrentAudio()?.album) // Add playback actions
            .addAction(R.drawable.ic_media_previous, "previous", playbackAction(3,context=this.context))
            .addAction(notificationAction, "pause", play_pauseAction)
            .addAction(
                R.drawable.ic_media_next,
                "next",
                playbackAction(2,context=this.context)
            )
    }

    internal fun removeNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun playbackAction(actionNumber: Int,context: Context): PendingIntent? {
        val playbackAction = Intent(context, MediaService::class.java)
        when (actionNumber) {
            0 -> {
                // Play
                playbackAction.action = ACTION_PLAY
                return PendingIntent.getService(context, actionNumber, playbackAction, 0)
            }
            1 -> {
                // Pause
                playbackAction.action = ACTION_PAUSE
                return PendingIntent.getService(context, actionNumber, playbackAction, 0)
            }
            2 -> {
                // Next track
                playbackAction.action = ACTION_NEXT
                return PendingIntent.getService(context, actionNumber, playbackAction, 0)
            }
            3 -> {
                // Previous track
                playbackAction.action = ACTION_PREVIOUS
                return PendingIntent.getService(context, actionNumber, playbackAction, 0)
            }

            4-> {
                playbackAction.action= ACTION_STOP
                return PendingIntent.getService(context,actionNumber,playbackAction,0)
            }
            else -> {
            }
        }
        return null
    }

    fun updateNotification(status: PlaybackStatus) {
        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification(status).build())
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "audio_channel_01"
         const val ACTION_PLAY = "com.ckj.eqaudioplayer.ACTION_PLAY"
        const val ACTION_PAUSE = "com.ckj.eqaudioplayer.ACTION_PAUSE"
        const val ACTION_PREVIOUS = "com.ckj.eqaudioplayer.ACTION_PREVIOUS"
        const val ACTION_NEXT = "com.ckj.eqaudioplayer.ACTION_NEXT"
        const val ACTION_STOP = "com.ckj.eqaudioplayer.ACTION_STOP"
    }

}