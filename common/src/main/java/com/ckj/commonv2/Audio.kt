package com.ckj.commonv2

import java.io.Serializable
import java.util.*

data class Audio(
    var id : String = UUID.randomUUID().toString(),
    var data: String,
    var title: String,
    var album: String,
    var artist: String,
    var currentlyPlaying: Boolean = false
) : Serializable {
}