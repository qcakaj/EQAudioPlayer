package com.ckj.easyaudioplayer

import android.media.MediaPlayer

// Extension property to get media player duration in seconds
val MediaPlayer.seconds:Int
    get() {
        return this.duration / 1000
    }


// Extension property to get media player current position in seconds
val MediaPlayer.currentSeconds:Int
    get() {
        return this.currentPosition/1000
    }