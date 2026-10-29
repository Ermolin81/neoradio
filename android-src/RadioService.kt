package com.neoradio.carplayer

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

@OptIn(UnstableApi::class)
class RadioService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var shouldResumeOnNetwork = false

    override fun onCreate() {
        super.onCreate()

        // 1. Автомобильный буфер (до 45 сек в памяти, старт после 2.5 сек)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 45_000, 2_500, 5_000)
            .build()

        // 2. Сетевой стек с авто-ретраями при смене вышек 4G
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(8_000)

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpDataSourceFactory)
            .setLoadErrorHandlingPolicy(object : DefaultLoadErrorHandlingPolicy() {
                override fun getMinimumLoadableRetryCount(dataType: Int): Int = 10
                override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long = 1500L
            })

        // 3. AudioFocus: навигатор будет ПРИГЛУШАТЬ радио (Ducking), а не убивать
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true) // Пауза при отвале Bluetooth в машине
            .build()

        // 4. Закольцованный плейлист для аппаратных кнопок руля (Next / Prev по AVRCP)
        player.repeatMode = Player.REPEAT_MODE_ALL

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (player.playWhenReady) {
                    shouldResumeOnNetwork = true
                }
                player.prepare()
                player.play()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) {
                    shouldResumeOnNetwork = false
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    shouldResumeOnNetwork = false
                }
            }
        })

        // 5. Умный реконнект при выезде из туннелей и слепых зон 4G
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Handler(Looper.getMainLooper()).post {
                        if (shouldResumeOnNetwork) {
                            shouldResumeOnNetwork = false
                            if (player.mediaItemCount > 0) {
                                player.seekToDefaultPosition()
                                player.prepare()
                                player.play()
                            }
                        }
                    }
                }

                override fun onLost(network: Network) {
                    Handler(Looper.getMainLooper()).post {
                        if (player.playWhenReady) {
                            shouldResumeOnNetwork = true
                        }
                    }
                }
            }
            networkCallback?.let {
                connectivityManager?.registerNetworkCallback(networkRequest, it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        networkCallback = null
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
