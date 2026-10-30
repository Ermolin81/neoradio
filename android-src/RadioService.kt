package com.neoradio.carplayer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

const val CMD_UPDATE_LIVE_METADATA = "com.neoradio.carplayer.COMMAND_UPDATE_LIVE_METADATA"

@OptIn(UnstableApi::class)
class LiveMetadataForwardingPlayer(private val player: ExoPlayer) : ForwardingPlayer(player) {
    @Volatile var customTitle: String? = null
    @Volatile var customArtist: String? = null
    @Volatile var customArtworkBytes: ByteArray? = null

    private val listenerMap = ConcurrentHashMap<Player.Listener, Player.Listener>()

    override fun addListener(listener: Player.Listener) {
        val wrapper = object : Player.Listener by listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                listener.onMediaMetadataChanged(getMediaMetadata())
            }
        }
        listenerMap[listener] = wrapper
        super.addListener(wrapper)
    }

    override fun removeListener(listener: Player.Listener) {
        val wrapper = listenerMap.remove(listener) ?: listener
        super.removeListener(wrapper)
    }

    fun updateLiveMetadata(title: String?, artist: String?, artworkBytes: ByteArray?) {
        this.customTitle = title
        this.customArtist = artist
        this.customArtworkBytes = artworkBytes
        val notifyRunnable = Runnable {
            val currentMeta = getMediaMetadata()
            for (listener in listenerMap.keys) {
                try {
                    listener.onMediaMetadataChanged(currentMeta)
                } catch (e: Exception) {
                    Log.w("RadioService", "Error notifying onMediaMetadataChanged", e)
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            notifyRunnable.run()
        } else {
            Handler(Looper.getMainLooper()).post(notifyRunnable)
        }
    }

    override fun getMediaMetadata(): MediaMetadata {
        val base = super.getMediaMetadata()
        val builder = base.buildUpon()
        if (!customTitle.isNullOrEmpty()) {
            builder.setTitle(customTitle)
            builder.setDisplayTitle(customTitle)
        }
        if (!customArtist.isNullOrEmpty()) {
            builder.setArtist(customArtist)
        }
        if (customArtworkBytes != null) {
            builder.setArtworkData(customArtworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }
        return builder.build()
    }

    override fun getDuration(): Long = C.TIME_UNSET
    override fun getContentDuration(): Long = C.TIME_UNSET
    override fun getCurrentPosition(): Long = 0L
    override fun getContentPosition(): Long = 0L
    override fun isCurrentMediaItemLive(): Boolean = true
    override fun isCurrentMediaItemDynamic(): Boolean = true
    override fun isCurrentMediaItemSeekable(): Boolean = false

    override fun getAvailableCommands(): Player.Commands {
        val base = super.getAvailableCommands()
        return base.buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .remove(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .build()
    }

    override fun isCommandAvailable(command: Int): Boolean {
        return when (command) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> false
            else -> super.isCommandAvailable(command)
        }
    }

    override fun seekToNext() {
        seekToNextMediaItem()
    }

    override fun seekToPrevious() {
        seekToPreviousMediaItem()
    }

    override fun seekToNextMediaItem() {
        if (player.mediaItemCount <= 0) return
        val nextIndex = (player.currentMediaItemIndex + 1) % player.mediaItemCount
        player.seekToDefaultPosition(nextIndex)
        player.prepare()
        player.play()
    }

    override fun seekToPreviousMediaItem() {
        if (player.mediaItemCount <= 0) return
        val prevIndex = (player.currentMediaItemIndex - 1 + player.mediaItemCount) % player.mediaItemCount
        player.seekToDefaultPosition(prevIndex)
        player.prepare()
        player.play()
    }
}

@OptIn(UnstableApi::class)
class RadioService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var forwardingPlayer: LiveMetadataForwardingPlayer
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var shouldResumeOnNetwork = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isServiceDestroyed = false
    private val artworkExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val artworkCache = object : LruCache<String, ByteArray>(25) {}

    override fun onCreate() {
        super.onCreate()

        // 1. Автомобильный буфер (до 45 сек в памяти, старт после 2.5 сек)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 45_000, 2_500, 5_000)
            .build()

        // 2. Сетевой стек с авто-ретраями при смене вышек 4G и мобильным User-Agent
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)

        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)

        val mediaSourceFactory = DefaultMediaSourceFactory(this, extractorsFactory)
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

        forwardingPlayer = LiveMetadataForwardingPlayer(player)

        // 4. Закольцованный плейлист для аппаратных кнопок руля (Next / Prev по AVRCP)
        player.repeatMode = Player.REPEAT_MODE_ALL

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                forwardingPlayer.updateLiveMetadata(null, null, null)
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e("RadioService", "ExoPlayer error [${error.errorCodeName}]: ${error.message}", error)
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    player.play()
                    return
                }

                if (player.playWhenReady) {
                    if (!isNetworkConnected()) {
                        shouldResumeOnNetwork = true
                    } else {
                        mainHandler.postDelayed({
                            if (!isServiceDestroyed && player.playWhenReady && player.mediaItemCount > 0) {
                                player.seekToDefaultPosition()
                                player.prepare()
                                player.play()
                            }
                        }, 2000L)
                    }
                }
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
                    mainHandler.post {
                        if (!isServiceDestroyed && shouldResumeOnNetwork) {
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
                    mainHandler.post {
                        if (!isServiceDestroyed && player.playWhenReady) {
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

        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                        .add(Player.COMMAND_SEEK_TO_NEXT)
                        .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                        .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                        .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                        .build()

                    val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(CMD_UPDATE_LIVE_METADATA, Bundle.EMPTY))
                        .build()

                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailablePlayerCommands(playerCommands)
                        .setAvailableSessionCommands(sessionCommands)
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == CMD_UPDATE_LIVE_METADATA) {
                        val title = args.getString("title")
                        val artist = args.getString("artist")
                        val artwork = args.getString("artwork")
                        handleUpdateLiveMetadata(title, artist, artwork)
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    return super.onCustomCommand(session, controller, customCommand, args)
                }
            })
            .build()
    }

    private fun handleUpdateLiveMetadata(title: String?, artist: String?, artwork: String?) {
        if (artwork.isNullOrEmpty()) {
            forwardingPlayer.updateLiveMetadata(title, artist, null)
            return
        }

        val cached = artworkCache.get(artwork)
        if (cached != null) {
            forwardingPlayer.updateLiveMetadata(title, artist, cached)
            return
        }

        forwardingPlayer.updateLiveMetadata(title, artist, null)
        val targetTitle = title
        val targetArtist = artist
        val targetArtwork = artwork

        artworkExecutor.execute {
            val bytes = downloadAndCompressArtwork(targetArtwork)
            if (bytes != null) {
                artworkCache.put(targetArtwork, bytes)
                Handler(Looper.getMainLooper()).post {
                    if (forwardingPlayer.customTitle == targetTitle && forwardingPlayer.customArtist == targetArtist) {
                        forwardingPlayer.updateLiveMetadata(targetTitle, targetArtist, bytes)
                    }
                }
            }
        }
    }

    private fun downloadAndCompressArtwork(initialUrl: String): ByteArray? {
        var currentUrl = initialUrl
        var redirects = 0
        while (redirects < 5) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(currentUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                )
                connection.instanceFollowRedirects = true
                connection.doInput = true
                connection.connect()

                val code = connection.responseCode
                if (code in 301..308) {
                    val location = connection.getHeaderField("Location")
                    if (!location.isNullOrEmpty()) {
                        currentUrl = URL(url, location).toExternalForm()
                        redirects++
                        continue
                    }
                }

                if (code in 200..299) {
                    return connection.inputStream.use { input ->
                        val originalBitmap = BitmapFactory.decodeStream(input) ?: return null
                        val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, 300, 300, true)
                        val out = ByteArrayOutputStream()
                        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
                        if (scaledBitmap != originalBitmap) {
                            originalBitmap.recycle()
                        }
                        scaledBitmap.recycle()
                        out.toByteArray()
                    }
                } else {
                    return null
                }
            } catch (e: Exception) {
                Log.w("RadioService", "Artwork download/compress error for $initialUrl: ${e.message}")
                return null
            } finally {
                try {
                    connection?.disconnect()
                } catch (e: Exception) {}
            }
        }
        return null
    }

    private fun isNetworkConnected(): Boolean {
        val cm = connectivityManager ?: return true
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        isServiceDestroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        networkCallback = null
        try {
            artworkExecutor.shutdownNow()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
