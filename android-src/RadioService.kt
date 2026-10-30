package com.neoradio.carplayer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
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
import androidx.media3.common.MimeTypes
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

data class StationData(
    val id: String,
    val url: String,
    val title: String,
    val artist: String,
    val artwork: String? = null
)

@OptIn(UnstableApi::class)
class LiveMetadataForwardingPlayer(
    private val player: ExoPlayer,
    private val service: RadioService
) : ForwardingPlayer(player) {
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

    override fun hasNextMediaItem(): Boolean = RadioService.stationCatalog.size > 1
    override fun hasPreviousMediaItem(): Boolean = RadioService.stationCatalog.size > 1

    override fun play() {
        if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) {
            player.prepare()
        }
        super.play()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady && player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) {
            player.prepare()
        }
        super.setPlayWhenReady(playWhenReady)
    }

    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        if (RadioService.stationCatalog.isNotEmpty()) {
            service.playStationAt(mediaItemIndex, autoPlay = true)
        } else {
            super.seekToDefaultPosition(mediaItemIndex)
        }
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (RadioService.stationCatalog.isNotEmpty()) {
            service.playStationAt(mediaItemIndex, autoPlay = true)
        } else {
            super.seekTo(mediaItemIndex, positionMs)
        }
    }

    override fun seekToNext() {
        seekToNextMediaItem()
    }

    override fun seekToPrevious() {
        seekToPreviousMediaItem()
    }

    override fun seekToNextMediaItem() {
        service.playNextStation()
    }

    override fun seekToPreviousMediaItem() {
        service.playPreviousStation()
    }
}

@OptIn(UnstableApi::class)
class RadioService : MediaSessionService() {
    companion object {
        const val CMD_UPDATE_LIVE_METADATA = "com.neoradio.carplayer.COMMAND_UPDATE_LIVE_METADATA"
        const val CMD_SELECT_STATION = "com.neoradio.carplayer.COMMAND_SELECT_STATION"

        @Volatile var instance: RadioService? = null
        @Volatile var currentStationIndex: Int = 0
        val stationCatalog: MutableList<StationData> = ArrayList()

        val DEFAULT_STATIONS = listOf(
            StationData("radio_ultra", "https://nashe1.hostingradio.ru/ultra-128.mp3", "Радио ULTRA", "Alternative Rock & Nu-Metal • Moscow"),
            StationData("radio_maximum", "https://maximum.hostingradio.ru/maximum128.mp3", "Радио MAXIMUM", "Rock, Modern Rock & Alternative • Moscow"),
            StationData("radio_rock_suomi", "https://aud-stream-radiorock.nm-elemental.nelonenmedia.fi/playlist.m3u8", "Radio Rock Suomi", "Rock / Heavy Metal • Supla"),
            StationData("radio_city", "https://streaming.radioplay.fi/radiocity/hml_128.mp3?direct=true", "Radio City (Finland)", "Classic Rock & Metal • Live"),
            StationData("rock_antenne_classic_perlen", "https://stream.rockantenne.de/classic-perlen/stream/mp3", "Rock Antenne Road Perlen", "Classic Rock & Highway Hits • Road Trip"),
            StationData("star_fm_from_hell", "https://stream.starfm.de/fromhell/mp3-192", "Star FM - From Hell", "Heavy Metal & Hard Rock • Berlin"),
            StationData("rock_antenne_heavy_metal", "https://stream.rockantenne.de/heavy-metal/stream/mp3", "Rock Antenne Heavy Metal", "Heavy Metal & Power • Germany"),
            StationData("ultra_dark_radio", "https://stream.laut.fm/ultradarkradio", "Ultra Dark Radio", "Darkwave & NDT • Blutengel, Mantus"),
            StationData("on_gothic_radio", "https://0n-gothic.radionetz.de/0n-gothic.mp3", "0N Gothic Radio", "Gothic Rock & Synth-Goth • Germany"),
            StationData("rock_antenne_gothic", "https://s6-webradio.rockantenne.de/gothic/stream/mp3", "Rock Antenne Gothic", "Gothic Rock & Dark Alternative"),
            StationData("radio_bob_gothic", "https://streams.radiobob.de/gothic/mp3-192/streams.radiobob.de/", "Radio BOB! Gothic Rock", "Gothic Rock & Post-Punk • Germany"),
            StationData("dark_metal_radio", "https://stream.laut.fm/dark-metal-radio", "Dark Metal-Radio", "Gothic Metal & Symphonic Dark"),
            StationData("electrozombies", "https://stream.laut.fm/electrozombies", "ElectroZombies", "Darkwave & Gothic Synth • Germany"),
            StationData("synthwave_radio", "https://stream.synthwaveradio.eu/listen/synthwaveradio.eu/radio.mp3", "Synthwave Radio 24/7", "Retro Synth & Cyberpunk 24/7"),
            StationData("nightride_fm", "https://stream.nightride.fm/nightride.mp3", "Nightride FM", "Synthwave & Chillwave 320k Ultra HD"),
            StationData("nightride_datawave", "https://stream.nightride.fm/datawave.mp3", "Datawave (Darksynth)", "Darksynth, Cyberpunk & EBM 320k"),
            StationData("darksynth_radio", "https://stream.laut.fm/darksynthradio", "Darksynth Radio", "Raw Dark Synth & Industrial Beats"),
            StationData("deep_one_radio", "https://stream.deep1.ru/deep1mp3", "DEEP ONE Radio", "Deep House, Atmospheric & Melodic Chill • deep1.ru")
        )

        fun createMediaItem(station: StationData, index: Int = -1): MediaItem {
            val metaBuilder = MediaMetadata.Builder()
                .setTitle(station.title)
                .setDisplayTitle(station.title)
                .setArtist(station.artist)

            val extras = Bundle()
            if (index >= 0) {
                extras.putInt("catalogIndex", index)
            }
            extras.putString("stationId", station.id)
            extras.putString("url", station.url)
            metaBuilder.setExtras(extras)

            val artwork = station.artwork
            if (!artwork.isNullOrEmpty() && (artwork.startsWith("http://") || artwork.startsWith("https://") || artwork.startsWith("content://"))) {
                try {
                    metaBuilder.setArtworkUri(Uri.parse(artwork))
                } catch (e: Exception) {}
            }

            val url = station.url
            val isHls = url.contains(".m3u8", ignoreCase = true) || url.contains("m3u8", ignoreCase = true)
            val isAac = url.contains(".aac", ignoreCase = true) || url.contains("aac", ignoreCase = true)
            val isOgg = url.contains(".ogg", ignoreCase = true) || url.contains("ogg", ignoreCase = true)

            val mimeType = when {
                isHls -> MimeTypes.APPLICATION_M3U8
                isAac -> MimeTypes.AUDIO_AAC
                isOgg -> MimeTypes.AUDIO_OGG
                else -> MimeTypes.AUDIO_MPEG
            }

            return MediaItem.Builder()
                .setMediaId(station.id)
                .setUri(url)
                .setMimeType(mimeType)
                .setMediaMetadata(metaBuilder.build())
                .build()
        }
    }

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
        instance = this
        if (stationCatalog.isEmpty()) {
            stationCatalog.addAll(DEFAULT_STATIONS)
        }

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

        forwardingPlayer = LiveMetadataForwardingPlayer(player, this)

        // 4. Закольцованный плейлист для аппаратных кнопок руля (Next / Prev по AVRCP)
        player.repeatMode = Player.REPEAT_MODE_ALL

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                forwardingPlayer.updateLiveMetadata(null, null, null)
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e("RadioService", "ExoPlayer error [${error.errorCodeName}]: ${error.message}", error)
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    playStationAt(currentStationIndex, autoPlay = true)
                    return
                }

                if (player.playWhenReady) {
                    if (!isNetworkConnected()) {
                        shouldResumeOnNetwork = true
                    } else {
                        mainHandler.postDelayed({
                            if (!isServiceDestroyed && player.playWhenReady && stationCatalog.isNotEmpty()) {
                                playStationAt(currentStationIndex, autoPlay = true)
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
                            if (stationCatalog.isNotEmpty()) {
                                playStationAt(currentStationIndex, autoPlay = true)
                            } else if (player.mediaItemCount > 0) {
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
                        .add(SessionCommand(CMD_SELECT_STATION, Bundle.EMPTY))
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
                    } else if (customCommand.customAction == CMD_SELECT_STATION) {
                        val index = args.getInt("index", 0)
                        val autoPlay = args.getBoolean("autoPlay", true)
                        playStationAt(index, autoPlay = autoPlay)
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    return super.onCustomCommand(session, controller, customCommand, args)
                }
            })
            .build()
    }

    fun setCatalog(stations: List<StationData>, startIndex: Int = 0) {
        stationCatalog.clear()
        stationCatalog.addAll(stations)
        val safeIndex = if (startIndex in stationCatalog.indices) startIndex else 0
        currentStationIndex = safeIndex
    }

    fun playStationAt(index: Int, autoPlay: Boolean = true) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { playStationAt(index, autoPlay) }
            return
        }
        if (stationCatalog.isEmpty()) return
        val safeIndex = ((index % stationCatalog.size) + stationCatalog.size) % stationCatalog.size
        currentStationIndex = safeIndex
        val station = stationCatalog[safeIndex]

        forwardingPlayer.updateLiveMetadata(null, null, null)
        val freshItem = createMediaItem(station, safeIndex)
        player.setMediaItem(freshItem)
        if (autoPlay) {
            player.prepare()
            player.play()
        }
    }

    fun playNextStation() {
        if (stationCatalog.isEmpty()) return
        val nextIndex = (currentStationIndex + 1) % stationCatalog.size
        playStationAt(nextIndex, autoPlay = true)
    }

    fun playPreviousStation() {
        if (stationCatalog.isEmpty()) return
        val prevIndex = (currentStationIndex - 1 + stationCatalog.size) % stationCatalog.size
        playStationAt(prevIndex, autoPlay = true)
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
        if (instance == this) {
            instance = null
        }
        super.onDestroy()
    }
}
