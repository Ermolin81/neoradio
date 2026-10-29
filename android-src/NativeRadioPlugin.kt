package com.neoradio.carplayer

import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

@OptIn(UnstableApi::class)
@CapacitorPlugin(name = "NativeRadio")
class NativeRadioPlugin : Plugin() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var listenerAttached = false
    private var lastMediaItemIndex = -1

    override fun load() {
        val sessionToken = SessionToken(context, ComponentName(context, RadioService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                val ctrl = controllerFuture?.get()
                controller = ctrl
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, MoreExecutors.directExecutor())

        // Аппаратные кнопки руля (Next / Prev через Bluetooth AVRCP)
        MediaButtonBus.onNext = {
            activity?.runOnUiThread {
                notifyListeners("nextStation", JSObject())
            }
        }
        MediaButtonBus.onPrev = {
            activity?.runOnUiThread {
                notifyListeners("prevStation", JSObject())
            }
        }
    }

    private fun attachPlayerListener(ctrl: MediaController) {
        if (listenerAttached) return
        listenerAttached = true
        lastMediaItemIndex = ctrl.currentMediaItemIndex
        ctrl.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val idx = ctrl.currentMediaItemIndex
                if (idx != lastMediaItemIndex) {
                    lastMediaItemIndex = idx
                    val ret = JSObject()
                    ret.put("index", idx)
                    ret.put("reason", reason)
                    ret.put("isPlaying", ctrl.isPlaying || ctrl.playWhenReady)
                    notifyListeners("stationChanged", ret)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val ret = JSObject()
                ret.put("isPlaying", isPlaying)
                notifyListeners("playbackStateChanged", ret)
            }
        })
    }

    private fun withController(call: PluginCall, action: (MediaController) -> Unit) {
        val ctrl = controller
        if (ctrl != null) {
            Handler(Looper.getMainLooper()).post {
                attachPlayerListener(ctrl)
                action(ctrl)
            }
        } else {
            val future = controllerFuture
            if (future != null) {
                future.addListener({
                    try {
                        val resolvedCtrl = future.get()
                        controller = resolvedCtrl
                        Handler(Looper.getMainLooper()).post {
                            if (resolvedCtrl != null) {
                                attachPlayerListener(resolvedCtrl)
                                action(resolvedCtrl)
                            } else {
                                call.reject("MediaController is null")
                            }
                        }
                    } catch (e: Exception) {
                        call.reject("Failed to initialize MediaController: ${e.message}")
                    }
                }, MoreExecutors.directExecutor())
            } else {
                call.reject("MediaController not initialized")
            }
        }
    }

    private fun createMediaItem(url: String, title: String, artist: String, artwork: String?): MediaItem {
        val metaBuilder = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)

        if (!artwork.isNullOrEmpty() && (artwork.startsWith("http://") || artwork.startsWith("https://") || artwork.startsWith("content://"))) {
            try {
                metaBuilder.setArtworkUri(android.net.Uri.parse(artwork))
            } catch (e: Exception) {}
        }

        val mimeType = when {
            url.contains(".m3u8") || url.contains("m3u8") -> androidx.media3.common.MimeTypes.APPLICATION_M3U8
            url.contains(".aac") || url.contains("aac") -> androidx.media3.common.MimeTypes.AUDIO_AAC
            url.contains(".ogg") || url.contains("ogg") -> androidx.media3.common.MimeTypes.AUDIO_OGG
            else -> androidx.media3.common.MimeTypes.AUDIO_MPEG
        }

        return MediaItem.Builder()
            .setUri(url)
            .setMimeType(mimeType)
            .setMediaMetadata(metaBuilder.build())
            .build()
    }

    @PluginMethod
    fun setPlaylist(call: PluginCall) {
        val stationsArray = call.getArray("stations")
        if (stationsArray == null || stationsArray.length() == 0) {
            return call.reject("Missing or empty stations array")
        }
        val startIndex = call.getInt("startIndex") ?: 0
        val autoPlay = call.getBoolean("autoPlay") ?: false

        val mediaItems = ArrayList<MediaItem>()
        for (i in 0 until stationsArray.length()) {
            val obj = stationsArray.getJSONObject(i)
            val url = obj.optString("url")
            if (url.isNullOrEmpty()) continue

            val title = if (obj.has("title") && obj.getString("title").isNotEmpty()) {
                obj.getString("title")
            } else if (obj.has("name") && obj.getString("name").isNotEmpty()) {
                obj.getString("name")
            } else {
                "Radio Stream"
            }

            val artist = if (obj.has("artist") && obj.getString("artist").isNotEmpty()) {
                obj.getString("artist")
            } else if (obj.has("genre") && obj.getString("genre").isNotEmpty()) {
                obj.getString("genre")
            } else {
                "NeoRadio Live"
            }

            val artUri = if (obj.has("artwork") && obj.getString("artwork").isNotEmpty()) {
                obj.getString("artwork")
            } else if (obj.has("art") && obj.getString("art").isNotEmpty()) {
                obj.getString("art")
            } else {
                null
            }

            val item = createMediaItem(url, title, artist, artUri)
            mediaItems.add(item)
        }

        if (mediaItems.isEmpty()) {
            return call.reject("No valid media items found in stations array")
        }

        withController(call) { ctrl ->
            val validIndex = if (startIndex in 0 until mediaItems.size) startIndex else 0
            lastMediaItemIndex = validIndex
            ctrl.setMediaItems(mediaItems, validIndex, 0L)
            ctrl.prepare()
            if (autoPlay) {
                ctrl.play()
            }
            call.resolve()
        }
    }

    @PluginMethod
    fun selectStation(call: PluginCall) {
        val index = call.getInt("index") ?: return call.reject("Missing index")
        withController(call) { ctrl ->
            if (index in 0 until ctrl.mediaItemCount) {
                lastMediaItemIndex = index
                ctrl.seekToDefaultPosition(index)
                ctrl.prepare()
                ctrl.play()
                call.resolve()
            } else {
                call.reject("Index out of bounds: $index (count: ${ctrl.mediaItemCount})")
            }
        }
    }

    @PluginMethod
    fun updateMetadata(call: PluginCall) {
        val title = call.getString("title")
        val artist = call.getString("artist")
        val artwork = call.getString("artwork") ?: call.getString("art")

        withController(call) { ctrl ->
            val currentItem = ctrl.currentMediaItem
            if (currentItem != null) {
                val metaBuilder = currentItem.mediaMetadata.buildUpon()
                if (!title.isNullOrEmpty()) metaBuilder.setTitle(title)
                if (!artist.isNullOrEmpty()) metaBuilder.setArtist(artist)
                if (!artwork.isNullOrEmpty() && (artwork.startsWith("http://") || artwork.startsWith("https://"))) {
                    try {
                        metaBuilder.setArtworkUri(android.net.Uri.parse(artwork))
                    } catch (e: Exception) {}
                } else if (artwork != null && artwork.isEmpty()) {
                    metaBuilder.setArtworkUri(null)
                }
                val updatedItem = currentItem.buildUpon()
                    .setMediaMetadata(metaBuilder.build())
                    .build()
                ctrl.replaceMediaItem(ctrl.currentMediaItemIndex, updatedItem)
            }
            call.resolve()
        }
    }

    @PluginMethod
    fun play(call: PluginCall) {
        val url = call.getString("url")
        val title = call.getString("title") ?: "Radio Stream"
        val artist = call.getString("artist") ?: "NeoRadio Live"
        val artwork = call.getString("artwork") ?: call.getString("art")

        withController(call) { ctrl ->
            if (url != null) {
                val item = createMediaItem(url, title, artist, artwork)
                ctrl.setMediaItem(item)
                ctrl.prepare()
                ctrl.play()
            } else {
                ctrl.play()
            }
            call.resolve()
        }
    }

    @PluginMethod
    fun pause(call: PluginCall) {
        withController(call) { ctrl ->
            ctrl.pause()
            call.resolve()
        }
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        withController(call) { ctrl ->
            ctrl.stop()
            call.resolve()
        }
    }

    override fun handleOnDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        listenerAttached = false
        super.handleOnDestroy()
    }
}
