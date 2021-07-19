package com.ckj.easyaudioplayer


import android.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.session.MediaSessionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat.*
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat
import com.ckj.commonv2.Audio
import com.ckj.commonv2.StorageUtil
import java.io.IOException


class AudioPlayerService : Service(), MediaPlayer.OnCompletionListener,
    MediaPlayer.OnPreparedListener, MediaPlayer.OnErrorListener, MediaPlayer.OnSeekCompleteListener,
    MediaPlayer.OnInfoListener, MediaPlayer.OnBufferingUpdateListener,
    AudioManager.OnAudioFocusChangeListener {

    private val localBinder = LocalBinder()
     var mediaPlayer: MediaPlayer? = null
    private var audioManager: AudioManager? = null

    //Used to pause/resume MediaPlayer
    private var resumePosition: Int? = 0

    //Handle incoming phone calls
    private var ongoingCall = false
    private var phoneStateListener: PhoneStateListener? = null
    private var telephonyManager: TelephonyManager? = null

    //List of available Audio files
    private var audioList: List<Audio?>? = null
    private var audioIndex  : Int = -1
    private var activeAudio //an object of the currently playing audio
            : Audio? = null

    val ACTION_PLAY = "com.ckj.audioplayer.ACTION_PLAY"
    val ACTION_PAUSE = "com.ckj.audioplayer.ACTION_PAUSE"
    val ACTION_PREVIOUS = "com.ckj.audioplayer.ACTION_PREVIOUS"
    val ACTION_NEXT = "com.ckj.audioplayer.ACTION_NEXT"
    val ACTION_STOP = "com.ckj.audioplayer.ACTION_STOP"

    //MediaSession
    private var mediaSessionManager: MediaSessionManager? = null
    private var mediaSession: MediaSessionCompat? = null
    private var transportControls: MediaControllerCompat.TransportControls? = null
    private lateinit var notificationManager: NotificationManager

    //AudioPlayer notification ID
    private val NOTIFICATION_ID = 101

    private val playNewAudio: BroadcastReceiver by lazy {
        object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {

            //Get the new media index form SharedPreferences
            audioIndex = StorageUtil(applicationContext).loadAudioIndex() ?: 2
            if (audioIndex != -1 && audioIndex < audioList!!.size) {
                //index is in a valid range
                activeAudio = audioList!![audioIndex]
            } else {
                stopSelf()
            }

            //A PLAY_NEW_AUDIO action received
            //reset mediaPlayer to play the new Audio
            stopMedia()
            mediaPlayer?.reset()
            initMediaPlayer()
            updateMetaData()
            notificationManager.notify(
                NOTIFICATION_ID,
                buildNotification(PlaybackStatus.PLAYING).build()
            )

        }
    }
    }

    //Becoming noisy
    private val becomingNoisyReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            //pause audio on ACTION_AUDIO_BECOMING_NOISY
            pauseMedia()
            notificationManager.notify(
                NOTIFICATION_ID,
                buildNotification(PlaybackStatus.PAUSED).build()
            )
        }
    }

    override fun onBind(p0: Intent): IBinder {
        return localBinder
    }

    override fun onCreate() {
        super.onCreate()
        // Perform one-time setup procedures
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Manage incoming phone calls during playback.
        // Pause MediaPlayer on incoming call,
        // Resume on hangup.
        callStateListener()
        //ACTION_AUDIO_BECOMING_NOISY -- change in audio outputs -- BroadcastReceiver
        registerBecomingNoisyReceiver()
        //Listen for new Audio to play -- BroadcastReceiver
        register_playNewAudio()
    }

    override fun onCompletion(p0: MediaPlayer?) {
        //Invoked when playback of a media source has completed.
        stopMedia()
        //stop the service
        stopSelf()
        skipToNext()
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            //Load data from SharedPreferences
            val storage = StorageUtil(applicationContext)
            audioList = storage.loadAudio()
            audioIndex = storage.loadAudioIndex()!!
            if (audioIndex != -1 && audioIndex < audioList?.size!!) {
                //index is in a valid range
                activeAudio = audioList!![audioIndex]
            } else {
                stopSelf()
            }
        } catch (e: NullPointerException) {
            stopSelf()
        }

        //Request audio focus

        //Request audio focus
        if (!requestAudioFocus()) {
            //Could not gain focus
            stopSelf()
        }

        if (mediaSessionManager == null) {
            try {
                initMediaSession()
                initMediaPlayer()
            } catch (e: RemoteException) {
                e.printStackTrace()
                stopSelf()
            }

        }

        //Handle Intent action from MediaSession.TransportControls
        handleIncomingActions(intent)


        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.e("tagg", "onDestroy() called")
        if (mediaPlayer != null) {
            stopMedia()
            mediaPlayer?.release();
        }
        removeAudioFocus();
        //Disable the PhoneStateListener
        if (phoneStateListener != null) {
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
        }

        removeNotification();

        //unregister BroadcastReceivers
        unregisterReceiver(becomingNoisyReceiver);
        unregisterReceiver(playNewAudio);

        //clear cached playlist
        StorageUtil(applicationContext).clearCachedAudioPlaylist();
    }

    override fun onPrepared(p0: MediaPlayer?) {
        //Invoked when the media source is ready for playback.
        playMedia();
    }

    override fun onError(p0: MediaPlayer?, p1: Int, p2: Int): Boolean {
        //Invoked when there has been an error during an asynchronous operation
        when (p1) {
            MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK -> Log.e(
                TAG,
                "MEDIA ERROR NOT VALID FOR PROGRESSIVE PLAYBACK $p2"
            )
            MediaPlayer.MEDIA_ERROR_SERVER_DIED -> Log.e(
                TAG,
                "MEDIA ERROR SERVER DIED $p2"
            )
            MediaPlayer.MEDIA_ERROR_UNKNOWN -> Log.e(
                TAG,
                "MEDIA ERROR UNKNOWN $p2"
            )
        }
        return true
    }

    override fun onSeekComplete(p0: MediaPlayer?) {
    }

    override fun onInfo(p0: MediaPlayer?, p1: Int, p2: Int): Boolean {
        return true
    }

    override fun onBufferingUpdate(p0: MediaPlayer?, p1: Int) {
    }


    override fun onAudioFocusChange(p0: Int) {
        //Invoked when the audio focus of the system is updated.
        when (p0) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                // resume playback
                if (mediaPlayer == null) initMediaPlayer() else if (mediaPlayer?.isPlaying == false) mediaPlayer?.start()
                mediaPlayer?.setVolume(1.0f, 1.0f)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Lost focus for an unbounded amount of time: stop playback and release media player
                if (mediaPlayer?.isPlaying == true) mediaPlayer?.stop()
                mediaPlayer?.release()
                mediaPlayer = null
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                // Lost focus for a short time, but we have to stop
                // playback. We don't release the media player because playback
                // is likely to resume
                if (mediaPlayer?.isPlaying == true) mediaPlayer?.pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                // Lost focus for a short time, but it's ok to keep playing
                // at an attenuated level
                if (mediaPlayer?.isPlaying == true) mediaPlayer?.setVolume(0.1f, 0.1f)
        }
    }

    private fun initMediaPlayer() {
        mediaPlayer = MediaPlayer()
        //Set up MediaPlayer event listeners
        mediaPlayer?.let {
            it.setOnCompletionListener(this@AudioPlayerService)
            it.setOnErrorListener(this@AudioPlayerService)
            it.setOnPreparedListener(this@AudioPlayerService)
            it.setOnBufferingUpdateListener(this@AudioPlayerService)
            it.setOnSeekCompleteListener(this@AudioPlayerService)
            it.setOnInfoListener(this@AudioPlayerService)
            //Reset so that the MediaPlayer is not pointing to another data source
            it.reset()
            it.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            try {
                // Set the data source to the mediaFile location
                it.setDataSource(activeAudio?.data);
            } catch (e: IOException) {
                e.printStackTrace()
                stopSelf()
            }
            try {
                it.prepareAsync()
            } catch (e: Exception) {
                e.printStackTrace()
                stopSelf()
            }

        }

    }

    /**
     * Class used for the client Binder.  Since this service runs in the same process as its
     * clients, we don't need to deal with IPC.
     */
    inner class LocalBinder : Binder() {
        val service: AudioPlayerService
            get() = this@AudioPlayerService

    }

    companion object {
        private const val TAG = "AudioPlayerService"
        private const val NOTIFICATION_CHANNEL_ID = "audio_channel_01"
            const val PLAY_NEW_AUDIO = "com.ckj.audiolibrary.PlayNewAudio"

    }

    private fun playMedia() {
        if (mediaPlayer?.isPlaying == false) {
            mediaPlayer?.start()
            val notification = buildNotification(PlaybackStatus.PLAYING).build()
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopMedia() {
        if (mediaPlayer == null) return
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.stop()
        }
    }

     fun pauseMedia() {
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
            resumePosition = mediaPlayer?.currentPosition
        }
    }

     fun resumeMedia() {
        if (mediaPlayer?.isPlaying == false) {
            resumePosition?.let { mediaPlayer?.seekTo(it) }
            mediaPlayer?.start()
        }

    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).run {
                setAudioAttributes(AudioAttributes.Builder().run {
                    setUsage(AudioAttributes.USAGE_GAME)
                    setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    build()
                })
                setAcceptsDelayedFocusGain(true)
                setOnAudioFocusChangeListener(this@AudioPlayerService)
                build()

            }
            val focusLock = Any()

            var playbackDelayed = false
            var playbackNowAuthorized = false

            val res = audioManager?.requestAudioFocus(focusRequest)
            synchronized(focusLock) {
                playbackNowAuthorized = when (res) {
                    AudioManager.AUDIOFOCUS_REQUEST_FAILED -> false
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                        true
                    }
                    AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                        playbackDelayed = true
                        false
                    }
                    else -> false
                }
            }
            playbackNowAuthorized
        } else {
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

            val result = audioManager?.requestAudioFocus(
                this,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            //Could not gain focus
        }
    }

    private fun removeAudioFocus(): Boolean {
        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED ==
                audioManager?.abandonAudioFocus(this)
    }


    private fun registerBecomingNoisyReceiver() {
        //register after getting audio focus
        val intentFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(becomingNoisyReceiver, intentFilter)
    }

    //Handle incoming phone calls
    private fun callStateListener() {
        // Get the telephony manager
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        //Starting listening for PhoneState changes
        phoneStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, incomingNumber: String) {
                when (state) {
                    TelephonyManager.CALL_STATE_OFFHOOK, TelephonyManager.CALL_STATE_RINGING -> if (mediaPlayer != null) {
                        pauseMedia()
                        ongoingCall = true
                    }
                    TelephonyManager.CALL_STATE_IDLE ->                   // Phone idle. Start playing.
                        if (mediaPlayer != null) {
                            if (ongoingCall) {
                                ongoingCall = false
                                resumeMedia()
                            }
                        }
                }
            }
        }
        // Register the listener with the telephony manager
        // Listen for changes to the device call state.
        telephonyManager?.listen(
            phoneStateListener,
            PhoneStateListener.LISTEN_CALL_STATE
        )
    }


    private fun register_playNewAudio() {
        //Register playNewMedia receiver
        val filter = IntentFilter(PLAY_NEW_AUDIO)
        registerReceiver(playNewAudio, filter)
    }

    @Throws(RemoteException::class)
    private fun initMediaSession() {
        if (mediaSessionManager != null) return  //mediaSessionManager exists
        mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        // Create a new MediaSession
        mediaSession = MediaSessionCompat(applicationContext, "AudioPlayer")
        //Get MediaSessions transport controls
        transportControls = mediaSession?.controller?.transportControls
        //set MediaSession -> ready to receive media commands
        mediaSession?.isActive = true
        //indicate that the MediaSession handles transport control commands
        // through its MediaSessionCompat.Callback.
        mediaSession?.setFlags(MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)

        //Set mediaSession's MetaData
        updateMetaData()

        // Attach Callback to receive MediaSession updates
        mediaSession?.setCallback(object : MediaSessionCompat.Callback() {
            // Implement callbacks
            override fun onPlay() {
                super.onPlay()
                resumeMedia()
                notificationManager.notify(
                    NOTIFICATION_ID,
                    buildNotification(PlaybackStatus.PLAYING).build()
                )
            }

            override fun onPause() {
                super.onPause()
                pauseMedia()
                notificationManager.notify(
                    NOTIFICATION_ID,
                    buildNotification(PlaybackStatus.PAUSED).build()
                )
            }

            override fun onSkipToNext() {
                super.onSkipToNext()
                skipToNext()
                updateMetaData()
                notificationManager.notify(
                    NOTIFICATION_ID,
                    buildNotification(PlaybackStatus.PLAYING).build()
                )
            }

            override fun onSkipToPrevious() {
                super.onSkipToPrevious()
                skipToPrevious()
                updateMetaData()
                notificationManager.notify(
                    NOTIFICATION_ID,
                    buildNotification(PlaybackStatus.PLAYING).build()
                )
            }

            override fun onStop() {
                super.onStop()
                stopMedia()
                removeNotification()
                //Stop the service
                stopSelf()
            }

            override fun onSeekTo(position: Long) {
                super.onSeekTo(position)
            }
        })
    }

    private fun updateMetaData() {
        val albumArt = BitmapFactory.decodeResource(
            resources,
            R.drawable.ic_media_play
        ) //replace with medias albumArt
        // Update the current metadata
        mediaSession?.setMetadata(
            MediaMetadataCompat.Builder()
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, activeAudio?.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, activeAudio?.album)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, activeAudio?.title)
                .build()
        )
    }

    private fun skipToNext() {
        if (audioIndex == audioList!!.size - 1) {
            //if last in playlist
            audioIndex = 0
            activeAudio = audioList!![audioIndex]
        } else {
            //get next in playlist
            activeAudio = audioList!![++audioIndex]
        }

        //Update stored index
        StorageUtil(applicationContext).storeAudioIndex(audioIndex)
        stopMedia()
        //reset mediaPlayer
        mediaPlayer?.reset()
        initMediaPlayer()
    }

    private fun skipToPrevious() {
        if (audioIndex == 0) {
            //if first in playlist
            //set index to the last of audioList
            if (audioList != null) {
                audioIndex = audioList?.size?.minus(1) ?: 0
            }
            activeAudio = audioList?.get(audioIndex)
        } else {
            //get previous in playlist
            activeAudio = audioList?.get(--audioIndex)
        }

        //Update stored index
        StorageUtil(applicationContext).storeAudioIndex(audioIndex)
        stopMedia()
        //reset mediaPlayer
        mediaPlayer?.reset()
        initMediaPlayer()
    }

    private fun buildNotification(playbackStatus: PlaybackStatus): Builder {
        var notificationAction = R.drawable.ic_media_pause //needs to be initialized
        var play_pauseAction: PendingIntent? = null

        //Build a new notification according to the current state of the MediaPlayer
        if (playbackStatus === PlaybackStatus.PLAYING) {
            notificationAction = R.drawable.ic_media_pause
            //create the pause action
            play_pauseAction = playbackAction(1)
        } else if (playbackStatus === PlaybackStatus.PAUSED) {
            notificationAction = R.drawable.ic_media_play
            //create the play action
            play_pauseAction = playbackAction(0)
        }
        val largeIcon = BitmapFactory.decodeResource(
            resources,
            R.drawable.ic_media_play
        ) //replace with your own image

        // Create a new Notification
        // 1. Create Notification Channel for O+ and beyond devices (26+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

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
        play_pauseAction: PendingIntent?
    ) = Builder(this, NOTIFICATION_CHANNEL_ID)
        .setShowWhen(false)
        .setOngoing(mediaPlayer?.isPlaying == true)// Set the Notification style
        .setStyle(
            NotificationCompat.MediaStyle() // Attach our MediaSession token
                .setMediaSession(mediaSession?.sessionToken) // Show our playback controls in the compact notification view.
                .setShowActionsInCompactView(0, 1, 2)
        ) // Set the Notification color
        .setColor(
            ContextCompat.getColor(
                applicationContext,
                R.color.holo_blue_dark
            )
        ) // Set the large and small icons
        .setLargeIcon(largeIcon)
        .setSmallIcon(R.drawable.stat_sys_headset) // Set Notification content information
        .setContentText(activeAudio?.artist)
        .setContentTitle(activeAudio?.title)
        .setContentInfo(activeAudio?.album) // Add playback actions
        .addAction(R.drawable.ic_media_previous, "previous", playbackAction(3))
        .addAction(notificationAction, "pause", play_pauseAction)
        .addAction(
            R.drawable.ic_media_next,
            "next",
            playbackAction(2)
        )

    private fun removeNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun playbackAction(actionNumber: Int): PendingIntent? {
        val playbackAction = Intent(this, AudioPlayerService::class.java)
        when (actionNumber) {
            0 -> {
                // Play
                playbackAction.action = ACTION_PLAY
                return PendingIntent.getService(this, actionNumber, playbackAction, 0)
            }
            1 -> {
                // Pause
                playbackAction.action = ACTION_PAUSE
                return PendingIntent.getService(this, actionNumber, playbackAction, 0)
            }
            2 -> {
                // Next track
                playbackAction.action = ACTION_NEXT
                return PendingIntent.getService(this, actionNumber, playbackAction, 0)
            }
            3 -> {
                // Previous track
                playbackAction.action = ACTION_PREVIOUS
                return PendingIntent.getService(this, actionNumber, playbackAction, 0)
            }

            4-> {
                playbackAction.action= ACTION_STOP
                return PendingIntent.getService(this,actionNumber,playbackAction,0)
            }
            else -> {
            }
        }
        return null
    }

    private fun handleIncomingActions(playbackAction: Intent?) {
        if (playbackAction == null || playbackAction.action == null) return
        val actionString = playbackAction.action
        when {
            actionString.equals(ACTION_PLAY, ignoreCase = true) -> {
                transportControls?.play()
            }
            actionString.equals(ACTION_PAUSE, ignoreCase = true) -> {
                transportControls?.pause()
            }
            actionString.equals(ACTION_NEXT, ignoreCase = true) -> {
                transportControls?.skipToNext()
            }
            actionString.equals(ACTION_PREVIOUS, ignoreCase = true) -> {
                transportControls?.skipToPrevious()
            }
            actionString.equals(ACTION_STOP, ignoreCase = true) -> {
                transportControls?.stop()
            }
        }
    }

}