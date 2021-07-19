package com.ckj.eqaudioplayer

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import androidx.appcompat.app.AppCompatActivity
import com.ckj.commonv2.Audio

class EQAudioPlayer(
    private val activity: AppCompatActivity,
    private val audioList: List<Audio>? = null
) {
   private var mediaService: MediaService? = null
    var serviceBound = false

     private val audioPlayerServiceConnection = object : ServiceConnection {
         override fun onServiceConnected(p0: ComponentName?, p1: IBinder?) {
             val binder = p1 as MediaService.LocalBinder
             mediaService = binder.service
             serviceBound = true
         }

         override fun onServiceDisconnected(p0: ComponentName?) {
             serviceBound = false
         }
     }


    class Builder(private val activity: AppCompatActivity) {

        var audioList: List<Audio>? = null

        fun setPlaylist(audios: List<Audio>): Builder {
            this.audioList = audios
            return this
        }


        fun build(): EQAudioPlayer {
            return EQAudioPlayer(
               this.activity,
                this.audioList

            )
        }


    }


    fun playAudio(audioIndex: Int) {
        //Check is service is active
        if (!serviceBound) {
            //Store Serializable audioList to SharedPreferences
            val storage = this.activity.let { com.ckj.commonv2.StorageUtil(it) }
            storage.storeAudio(audioList)
            storage.storeAudioIndex(audioIndex)
            val playerIntent = Intent(this.activity, MediaService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                this.activity.startForegroundService(playerIntent)
            } else {
                activity.startService(playerIntent)
            }
            activity.bindService(
                playerIntent, this.audioPlayerServiceConnection,
                AppCompatActivity.BIND_AUTO_CREATE
            )
        } else {
            //Store the new audioIndex to SharedPreferences
            val storage = this.activity.let { com.ckj.commonv2.StorageUtil(it) }
            storage.storeAudioIndex(audioIndex)

            //Service is active
            //Send a broadcast to the service -> PLAY_NEW_AUDIO
            val broadcastIntent = Intent(MediaService.PLAY_NEW_AUDIO)
            activity.sendBroadcast(broadcastIntent)
        }
    }

     val isPlayerActive  get() =  mediaService?.mediaPlayer?.isPlaying


     val currentAudioProgress get() = mediaService?.mediaPlayer?.currentSeconds



    fun setAudioProgress(p1:Int) {
       mediaService?.mediaPlayer?.seekTo(p1 * 1000)
    }

    fun pauseAudio() {
        mediaService?.pauseMedia()
    }

    fun resumeAudio() {
      mediaService?.resumeMedia()
    }

    private fun stop() {
     mediaService?.stopSelf()

    }

    private fun getServiceConnectionObject() = audioPlayerServiceConnection

    fun releaseAudioPlayer() {
        if (serviceBound) {
            activity.unbindService(getServiceConnectionObject())
            //service is active
            stop()
        }
    }
}