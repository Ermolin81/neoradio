package com.neoradio.carplayer

import android.content.ComponentName
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

@CapacitorPlugin(name = "NativeRadio")
class NativeRadioPlugin : Plugin() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    override fun load() {
        val sessionToken = SessionToken(context, ComponentName(context, RadioService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                controller = controllerFuture?.get()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, MoreExecutors.directExecutor())
    }

    @PluginMethod
    fun play(call: PluginCall) {
        val url = call.getString("url") ?: return call.reject("Missing URL")
        val title = call.getString("title") ?: "Radio Stream"
        val artist = call.getString("artist") ?: "NeoRadio Live"

        activity.runOnUiThread {
            val ctrl = controller
            if (ctrl != null) {
                val metadata = MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .build()

                val item = MediaItem.Builder()
                    .setUri(url)
                    .setMediaMetadata(metadata)
                    .build()

                ctrl.setMediaItem(item)
                ctrl.prepare()
                ctrl.play()
                call.resolve()
            } else {
                call.reject("MediaController not ready yet")
            }
        }
    }

    @PluginMethod
    fun pause(call: PluginCall) {
        activity.runOnUiThread {
            controller?.pause()
            call.resolve()
        }
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        activity.runOnUiThread {
            controller?.stop()
            call.resolve()
        }
    }
}
