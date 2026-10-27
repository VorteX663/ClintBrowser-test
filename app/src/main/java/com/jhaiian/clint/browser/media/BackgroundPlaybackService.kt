package com.jhaiian.clint.browser.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.jhaiian.clint.R
import com.jhaiian.clint.browser.MainActivity
import com.jhaiian.clint.util.LocaleHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class BackgroundPlaybackService : LifecycleService() {

    companion object {
        private const val CHANNEL_ID = "background_playback"
        private const val NOTIFICATION_ID = 9101
        private const val ACTION_PLAY = "com.jhaiian.clint.BACKGROUND_PLAYBACK_PLAY"
        private const val ACTION_PAUSE = "com.jhaiian.clint.BACKGROUND_PLAYBACK_PAUSE"
        private const val ACTION_STOP = "com.jhaiian.clint.BACKGROUND_PLAYBACK_STOP"
        private const val ACTION_NEXT = "com.jhaiian.clint.BACKGROUND_PLAYBACK_NEXT"
        private const val ACTION_PREVIOUS = "com.jhaiian.clint.BACKGROUND_PLAYBACK_PREVIOUS"
        private const val CUSTOM_ACTION_CLOSE = "com.jhaiian.clint.BACKGROUND_PLAYBACK_CLOSE"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, BackgroundPlaybackService::class.java))
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    private lateinit var mediaSession: MediaSession
    private var promoted = false
    private var lastStartId = 0
    private var noisyReceiverRegistered = false
    private var artworkUrl: String? = null
    private var artwork: Bitmap? = null
    private var artworkJob: Job? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                BackgroundPlayback.send(BackgroundPlayback.Command.Pause)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        mediaSession = MediaSession(this, "ClintBackgroundPlayback").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = BackgroundPlayback.send(BackgroundPlayback.Command.Play)
                override fun onPause() = BackgroundPlayback.send(BackgroundPlayback.Command.Pause)
                override fun onStop() = BackgroundPlayback.send(BackgroundPlayback.Command.Stop)
                override fun onSkipToNext() = BackgroundPlayback.send(BackgroundPlayback.Command.Next)
                override fun onSkipToPrevious() = BackgroundPlayback.send(BackgroundPlayback.Command.Previous)
                override fun onSeekTo(pos: Long) = BackgroundPlayback.send(BackgroundPlayback.Command.SeekTo(pos.coerceAtLeast(0L)))
                override fun onCustomAction(action: String, extras: Bundle?) {
                    if (action == CUSTOM_ACTION_CLOSE) BackgroundPlayback.send(BackgroundPlayback.Command.Stop)
                }
            })
            setSessionActivity(contentIntent())
            isActive = true
        }
        lifecycleScope.launch {
            BackgroundPlayback.session.collect { render(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId
        isRunning = true
        when (intent?.action) {
            ACTION_PLAY -> BackgroundPlayback.send(BackgroundPlayback.Command.Play)
            ACTION_PAUSE -> BackgroundPlayback.send(BackgroundPlayback.Command.Pause)
            ACTION_STOP -> BackgroundPlayback.send(BackgroundPlayback.Command.Stop)
            ACTION_NEXT -> BackgroundPlayback.send(BackgroundPlayback.Command.Next)
            ACTION_PREVIOUS -> BackgroundPlayback.send(BackgroundPlayback.Command.Previous)
        }
        render(BackgroundPlayback.session.value)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        artworkJob?.cancel()
        unregisterNoisyReceiver()
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    private fun render(session: BackgroundPlayback.Session?) {
        if (session == null) {
            if (!promoted) promote(buildNotification(null))
            unregisterNoisyReceiver()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            promoted = false
            if (stopSelfResult(lastStartId)) isRunning = false
            return
        }
        updateArtwork(session.artworkUrl)
        mediaSession.setMetadata(buildMetadata(session))
        mediaSession.setPlaybackState(buildPlaybackState(session))
        promote(buildNotification(session))
        if (session.playing) registerNoisyReceiver() else unregisterNoisyReceiver()
    }

    private fun updateArtwork(url: String?) {
        if (url == artworkUrl) return
        artworkUrl = url
        artworkJob?.cancel()
        artwork = url?.let { BackgroundPlaybackArtwork.peek(it) }
        if (url == null || artwork != null) return
        artworkJob = lifecycleScope.launch {
            val bitmap = BackgroundPlaybackArtwork.load(url) ?: return@launch
            if (artworkUrl != url) return@launch
            artwork = bitmap
            BackgroundPlayback.session.value?.takeIf { it.artworkUrl == url }?.let { render(it) }
        }
    }

    private fun buildMetadata(session: BackgroundPlayback.Session): MediaMetadata {
        val builder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, session.title)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, session.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, session.subtitle)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, session.subtitle)
        if (session.album.isNotEmpty()) builder.putString(MediaMetadata.METADATA_KEY_ALBUM, session.album)
        if (session.durationMs > 0) builder.putLong(MediaMetadata.METADATA_KEY_DURATION, session.durationMs)
        artwork?.let {
            builder.putBitmap(MediaMetadata.METADATA_KEY_ART, it)
            builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
            builder.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, it)
        }
        return builder.build()
    }

    private fun buildPlaybackState(session: BackgroundPlayback.Session): PlaybackState {
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
        if (session.canSeek && session.durationMs > 0) actions = actions or PlaybackState.ACTION_SEEK_TO
        if (session.canSkipNext) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (session.canSkipPrevious) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        val position = if (session.positionMs >= 0) session.positionMs else PlaybackState.PLAYBACK_POSITION_UNKNOWN
        val builder = PlaybackState.Builder()
            .setActions(actions)
            .setState(
                if (session.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                position,
                if (session.playing) session.speed else 0f,
                session.positionUpdatedAt.takeIf { it > 0 } ?: SystemClock.elapsedRealtime()
            )
            .addCustomAction(
                PlaybackState.CustomAction.Builder(
                    CUSTOM_ACTION_CLOSE,
                    getString(R.string.action_close),
                    android.R.drawable.ic_menu_close_clear_cancel
                ).build()
            )
        return builder.build()
    }

    private fun promote(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            promoted = true
        }
    }

    private fun buildNotification(session: BackgroundPlayback.Session?): Notification {
        val playing = session?.playing == true
        val actions = mutableListOf<Notification.Action>()
        val compact = mutableListOf<Int>()
        if (session?.canSkipPrevious == true) {
            compact.add(actions.size)
            actions.add(action(android.R.drawable.ic_media_previous, R.string.background_playback_previous, ACTION_PREVIOUS, 4))
        }
        compact.add(actions.size)
        actions.add(
            if (playing) action(android.R.drawable.ic_media_pause, R.string.action_pause, ACTION_PAUSE, 1)
            else action(android.R.drawable.ic_media_play, R.string.action_play, ACTION_PLAY, 2)
        )
        if (session?.canSkipNext == true) {
            compact.add(actions.size)
            actions.add(action(android.R.drawable.ic_media_next, R.string.background_playback_next, ACTION_NEXT, 5))
        }
        actions.add(action(android.R.drawable.ic_menu_close_clear_cancel, R.string.action_close, ACTION_STOP, 3))
        if (compact.size < 3) compact.add(actions.lastIndex)
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_24)
            .setContentTitle(session?.title ?: getString(R.string.background_playback_title))
            .setContentText(session?.subtitle.orEmpty())
            .setContentIntent(contentIntent())
            .setDeleteIntent(serviceIntent(ACTION_STOP, 3))
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(*compact.toIntArray())
            )
        if (session?.album?.isNotEmpty() == true) builder.setSubText(session.album)
        artwork?.takeIf { session != null }?.let { builder.setLargeIcon(it) }
        actions.forEach { builder.addAction(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    private fun action(icon: Int, label: Int, action: String, requestCode: Int): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(this, icon),
            getString(label),
            serviceIntent(action, requestCode)
        ).build()

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, BackgroundPlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun contentIntent(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.background_playback_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.background_playback_channel_desc)
            setShowBadge(false)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun registerNoisyReceiver() {
        if (noisyReceiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        noisyReceiverRegistered = true
    }

    private fun unregisterNoisyReceiver() {
        if (!noisyReceiverRegistered) return
        runCatching { unregisterReceiver(noisyReceiver) }
        noisyReceiverRegistered = false
    }
}
