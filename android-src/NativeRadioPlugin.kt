package com.neoradio.carplayer

import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
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
    companion object {
        const val CMD_UPDATE_LIVE_METADATA = "com.neoradio.carplayer.COMMAND_UPDATE_LIVE_METADATA"
    }

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
                if (ctrl != null) {
                    Handler(Looper.getMainLooper()).post {
                        attachPlayerListener(ctrl)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, MoreExecutors.directExecutor())
    }

    private fun attachPlayerListener(ctrl: MediaController) {
        if (listenerAttached) return
        listenerAttached = true
        lastMediaItemIndex = ctrl.currentMediaItemIndex
        ctrl.addListener(object : Player.Listener {
            private fun dispatchPlaybackState() {
                val isPlaying = ctrl.isPlaying
                val playWhenReady = ctrl.playWhenReady
                val state = ctrl.playbackState
                val currentItem = ctrl.currentMediaItem
                val extrasIndex = currentItem?.mediaMetadata?.extras?.getInt("catalogIndex", -1) ?: -1
                val idx = if (extrasIndex >= 0) extrasIndex else RadioService.currentStationIndex
                val station = if (idx in RadioService.stationCatalog.indices) RadioService.stationCatalog[idx] else null

                // Active playing includes buffering and ready states when user requested playback
                val isActive = isPlaying || (playWhenReady && (state == Player.STATE_READY || state == Player.STATE_BUFFERING))

                val ret = JSObject()
                ret.put("isPlaying", isActive)
                ret.put("isBuffering", state == Player.STATE_BUFFERING)
                ret.put("rawIsPlaying", isPlaying)
                ret.put("index", idx)
                ret.put("stationId", station?.id ?: currentItem?.mediaId ?: "")
                notifyListeners("playbackStateChanged", ret)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val currentItem = mediaItem ?: ctrl.currentMediaItem
                val extrasIndex = currentItem?.mediaMetadata?.extras?.getInt("catalogIndex", -1) ?: -1
                val idx = if (extrasIndex >= 0) extrasIndex else RadioService.currentStationIndex
                lastMediaItemIndex = idx
                val station = if (idx in RadioService.stationCatalog.indices) RadioService.stationCatalog[idx] else null

                val stationId = station?.id ?: currentItem?.mediaId ?: ""
                val uri = station?.url ?: currentItem?.localConfiguration?.uri?.toString()
                    ?: currentItem?.requestMetadata?.mediaUri?.toString()
                    ?: ""
                val title = station?.title ?: currentItem?.mediaMetadata?.title?.toString() ?: ""
                val artist = station?.artist ?: currentItem?.mediaMetadata?.artist?.toString() ?: ""

                val isLiveActive = ctrl.isPlaying || (ctrl.playWhenReady && ctrl.playbackState != Player.STATE_IDLE && ctrl.playbackState != Player.STATE_ENDED)

                val ret = JSObject()
                ret.put("index", idx)
                ret.put("stationId", stationId)
                ret.put("url", uri)
                ret.put("title", title)
                ret.put("artist", artist)
                ret.put("reason", reason)
                ret.put("isPlaying", isLiveActive)
                notifyListeners("stationChanged", ret)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                dispatchPlaybackState()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                dispatchPlaybackState()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                dispatchPlaybackState()
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

    private fun createMediaItem(stationId: String, url: String, title: String, artist: String, artwork: String?, index: Int = -1): MediaItem {
        return RadioService.createMediaItem(StationData(stationId, url, title, artist, artwork), index)
    }

    @PluginMethod
    fun setPlaylist(call: PluginCall) {
        val stationsArray = call.getArray("stations")
        if (stationsArray == null || stationsArray.length() == 0) {
            return call.reject("Missing or empty stations array")
        }
        val startIndex = call.getInt("startIndex") ?: 0
        val autoPlay = call.getBoolean("autoPlay") ?: false

        val catalogList = ArrayList<StationData>()
        for (i in 0 until stationsArray.length()) {
            val obj = stationsArray.getJSONObject(i)
            val url = obj.optString("url")
            if (url.isNullOrEmpty()) continue

            val stationId = if (obj.has("id") && obj.getString("id").isNotEmpty()) {
                obj.getString("id")
            } else if (obj.has("stationId") && obj.getString("stationId").isNotEmpty()) {
                obj.getString("stationId")
            } else {
                url
            }

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

            catalogList.add(StationData(stationId, url, title, artist, artUri))
        }

        if (catalogList.isEmpty()) {
            return call.reject("No valid media items found in stations array")
        }

        withController(call) { ctrl ->
            val safeIndex = if (startIndex in catalogList.indices) startIndex else 0
            lastMediaItemIndex = safeIndex
            val service = RadioService.instance
            if (service != null) {
                service.setCatalog(catalogList, safeIndex)
                service.playStationAt(safeIndex, autoPlay = autoPlay)
            } else {
                RadioService.stationCatalog.clear()
                RadioService.stationCatalog.addAll(catalogList)
                RadioService.currentStationIndex = safeIndex
                val args = Bundle().apply { putInt("index", safeIndex) }
                ctrl.sendCustomCommand(SessionCommand(RadioService.CMD_SELECT_STATION, Bundle.EMPTY), args)
            }

            val ret = JSObject()
            ret.put("count", catalogList.size)
            ret.put("startIndex", safeIndex)
            call.resolve(ret)
        }
    }

    @PluginMethod
    fun selectStation(call: PluginCall) {
        val index = call.getInt("index") ?: return call.reject("Missing index")
        withController(call) { ctrl ->
            lastMediaItemIndex = index
            val service = RadioService.instance
            if (service != null) {
                service.playStationAt(index, autoPlay = true)
            } else {
                val args = Bundle().apply { putInt("index", index) }
                ctrl.sendCustomCommand(SessionCommand(RadioService.CMD_SELECT_STATION, Bundle.EMPTY), args)
            }
            val ret = JSObject()
            ret.put("index", index)
            call.resolve(ret)
        }
    }

    @PluginMethod
    fun updateMetadata(call: PluginCall) {
        val title = call.getString("title")
        val artist = call.getString("artist")
        val artwork = call.getString("artwork") ?: call.getString("art")

        withController(call) { ctrl ->
            val args = Bundle().apply {
                putString("title", title)
                putString("artist", artist)
                putString("artwork", artwork)
            }
            val command = SessionCommand(CMD_UPDATE_LIVE_METADATA, Bundle.EMPTY)
            ctrl.sendCustomCommand(command, args)
            call.resolve()
        }
    }

    @PluginMethod
    fun play(call: PluginCall) {
        val stationId = call.getString("stationId") ?: call.getString("id")
        val url = call.getString("url")
        val title = call.getString("title") ?: "Radio Stream"
        val artist = call.getString("artist") ?: "NeoRadio Live"
        val artwork = call.getString("artwork") ?: call.getString("art")

        withController(call) { ctrl ->
            if (stationId.isNullOrEmpty() && url.isNullOrEmpty()) {
                ctrl.play()
                val ret = JSObject()
                ret.put("resumed", true)
                call.resolve(ret)
                return@withController
            }

            val service = RadioService.instance
            var foundIndex = -1
            for (i in RadioService.stationCatalog.indices) {
                val s = RadioService.stationCatalog[i]
                if (!stationId.isNullOrEmpty() && s.id == stationId) {
                    foundIndex = i
                    break
                }
                if (!url.isNullOrEmpty() && s.url == url) {
                    foundIndex = i
                    break
                }
            }

            if (foundIndex != -1) {
                lastMediaItemIndex = foundIndex
                if (RadioService.currentStationIndex == foundIndex && (ctrl.isPlaying || ctrl.playbackState == Player.STATE_BUFFERING)) {
                    ctrl.play()
                } else {
                    service?.playStationAt(foundIndex, autoPlay = true)
                }
            } else if (!url.isNullOrEmpty()) {
                val effectiveId = if (!stationId.isNullOrEmpty()) stationId else url
                val newStation = StationData(effectiveId, url, title, artist, artwork)
                RadioService.stationCatalog.add(newStation)
                val targetIndex = RadioService.stationCatalog.size - 1
                lastMediaItemIndex = targetIndex
                service?.playStationAt(targetIndex, autoPlay = true)
            } else {
                ctrl.play()
            }

            val ret = JSObject()
            ret.put("index", RadioService.currentStationIndex)
            ret.put("isPlaying", true)
            call.resolve(ret)
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

    @PluginMethod
    fun next(call: PluginCall) {
        withController(call) { ctrl ->
            val service = RadioService.instance
            if (service != null) {
                service.playNextStation()
            } else {
                ctrl.seekToNextMediaItem()
            }
            val ret = JSObject()
            ret.put("index", RadioService.currentStationIndex)
            call.resolve(ret)
        }
    }

    @PluginMethod
    fun prev(call: PluginCall) {
        withController(call) { ctrl ->
            val service = RadioService.instance
            if (service != null) {
                service.playPreviousStation()
            } else {
                ctrl.seekToPreviousMediaItem()
            }
            val ret = JSObject()
            ret.put("index", RadioService.currentStationIndex)
            call.resolve(ret)
        }
    }

    @PluginMethod
    fun getCurrentState(call: PluginCall) {
        withController(call) { ctrl ->
            val ret = JSObject()
            val currentItem = ctrl.currentMediaItem
            val extrasIndex = currentItem?.mediaMetadata?.extras?.getInt("catalogIndex", -1) ?: -1
            val idx = if (extrasIndex >= 0) extrasIndex else RadioService.currentStationIndex
            val station = if (idx in RadioService.stationCatalog.indices) RadioService.stationCatalog[idx] else null
            val state = ctrl.playbackState
            val isPlaying = ctrl.isPlaying || (ctrl.playWhenReady && (state == Player.STATE_READY || state == Player.STATE_BUFFERING))
            ret.put("isPlaying", isPlaying)
            ret.put("isBuffering", state == Player.STATE_BUFFERING)
            ret.put("rawIsPlaying", ctrl.isPlaying)
            ret.put("index", idx)
            ret.put("stationId", station?.id ?: currentItem?.mediaId ?: "")
            val uri = station?.url ?: currentItem?.localConfiguration?.uri?.toString()
                ?: currentItem?.requestMetadata?.mediaUri?.toString()
                ?: ""
            ret.put("url", uri)
            ret.put("title", station?.title ?: currentItem?.mediaMetadata?.title?.toString() ?: "")
            ret.put("artist", station?.artist ?: currentItem?.mediaMetadata?.artist?.toString() ?: "")
            ret.put("mediaItemCount", RadioService.stationCatalog.size)
            call.resolve(ret)
        }
    }

    override fun handleOnDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        listenerAttached = false
        super.handleOnDestroy()
    }
}
