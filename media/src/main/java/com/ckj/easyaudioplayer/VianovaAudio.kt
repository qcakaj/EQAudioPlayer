package com.ckj.easyaudioplayer

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import androidx.appcompat.app.AppCompatActivity
import com.ckj.commonv2.Audio

class VianovaAudio(
    private val activity: AppCompatActivity,
    private val audioList: List<Audio>? = null
) {
   private var audioPlayerService: AudioPlayerService? = null
    var serviceBound = false

     private val audioPlayerServiceConnection by lazy {
        object : ServiceConnection {
            override fun onServiceConnected(p0: ComponentName?, p1: IBinder?) {
                val binder = p1 as AudioPlayerService.LocalBinder
                audioPlayerService = binder.service
                serviceBound = true
            }

            override fun onServiceDisconnected(p0: ComponentName?) {
                serviceBound = false
            }
        }
    }




    class Builder(private val activity: AppCompatActivity) {

        var audioList: List<Audio>? = null

        fun setPlaylist(audios: List<Audio>): Builder {
            this.audioList = audios
            return this
        }


        fun build(): VianovaAudio {
            return VianovaAudio(
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
            val playerIntent = Intent(this.activity, AudioPlayerService::class.java)
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
            val broadcastIntent = Intent(AudioPlayerService.PLAY_NEW_AUDIO)
            activity.sendBroadcast(broadcastIntent)
        }
    }

    val isActive : Boolean? by lazy {
     audioPlayerService?.mediaPlayer?.isPlaying
}
    val currentSeconds by lazy {
        audioPlayerService?.mediaPlayer?.currentSeconds
    }

    fun setProgress(p1:Int) {
       audioPlayerService?.mediaPlayer?.seekTo(p1 * 1000)
    }

    fun pause() {
        audioPlayerService?.pauseMedia()
    }

    fun resume() {
      audioPlayerService?.resumeMedia()
    }

    fun stop() {
     audioPlayerService?.stopSelf()

    }

    fun getServiceConnectionObject() = audioPlayerServiceConnection
}