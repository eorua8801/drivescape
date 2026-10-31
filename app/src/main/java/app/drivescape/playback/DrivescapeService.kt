package app.drivescape.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.session.MediaButtonReceiver
import app.drivescape.R
import app.drivescape.drive.DriveHub
import app.drivescape.ui.MainActivity

/**
 * 미디어·안전 계층.
 * Pleos Music/Audio 앱 정책에 따라 MediaBrowserService + MediaSession으로 재생을 노출한다.
 * - 스티어링 휠 키 / 미디어 센터: 재생·일시정지·정지·다음/이전(=세트 넘기기)·미디어 ID 재생·검색 재생
 * - 오디오 포커스: LOSS → 정지 + 세션 비활성, LOSS_TRANSIENT(_CAN_DUCK) → 일시정지, GAIN → 재개
 *   (덕킹은 Pleos/AAOS 오디오 포커스 정책이 처리하도록 하드코딩하지 않는다)
 * - 연결만으로 자동 재생하지 않는다.
 */
class DrivescapeService : MediaBrowserServiceCompat() {

    private lateinit var session: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        DriveHub.init(this)
        current = this
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        session = MediaSessionCompat(this, "Drivescape").apply {
            setCallback(callback)
            setSessionActivity(
                PendingIntent.getActivity(
                    this@DrivescapeService, 0, Intent(this@DrivescapeService, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
        sessionToken = session.sessionToken
        updateSession(PlaybackStateCompat.STATE_STOPPED)

        // bind/unbind만 되는 경우에도 onTaskRemoved를 받도록 Started 상태로 만든다 (Pleos 가이드)
        startService(Intent(this, DrivescapeService::class.java))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(session, intent)
        if (player.isPlaying) goForeground()
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopMedia()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopMedia()
        session.release()
        if (current === this) current = null
        super.onDestroy()
    }

    // ---------------- 브라우징: 사운드 세트 목록 ----------------

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot =
        BrowserRoot(ROOT_ID, null)

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        if (parentId != ROOT_ID) { result.sendResult(mutableListOf()); return }
        val items = DriveHub.sets.map { s ->
            MediaBrowserCompat.MediaItem(
                MediaDescriptionCompat.Builder()
                    .setMediaId(s.id)
                    .setTitle(s.name)
                    .setSubtitle(s.subtitle)
                    .build(),
                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
            )
        }.toMutableList()
        result.sendResult(items)
    }

    // ---------------- 세션 콜백 ----------------

    private val callback = object : MediaSessionCompat.Callback() {
        override fun onPlay() { play() }
        override fun onPause() { pauseMedia() }
        override fun onStop() { stopMedia() }
        override fun onSkipToNext() { switchSet(1) }
        override fun onSkipToPrevious() { switchSet(-1) }
        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            mediaId?.let { DriveHub.selectSet(it) }
            play()
        }
        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            if (!query.isNullOrBlank()) {
                DriveHub.sets.firstOrNull { it.name.contains(query, ignoreCase = true) }?.let { DriveHub.selectSet(it.id) }
            }
            play()
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { focus ->
        when (focus) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                stopMedia()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (player.isPlaying) { resumeOnFocusGain = true; pauseMedia(keepFocus = true) }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnFocusGain) { resumeOnFocusGain = false; play() }
            }
        }
    }

    private fun requestFocus(): Boolean {
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build().also { focusRequest = it }
        return audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    }

    fun play() {
        if (!requestFocus()) return
        player.engine.soundSet = DriveHub.currentSet
        player.play()
        session.isActive = true
        updateSession(PlaybackStateCompat.STATE_PLAYING)
        goForeground()
    }

    fun pauseMedia(keepFocus: Boolean = false) {
        player.stop()
        if (!keepFocus) abandonFocus()
        updateSession(PlaybackStateCompat.STATE_PAUSED)
        leaveForeground()
    }

    fun stopMedia() {
        player.stop()
        abandonFocus()
        updateSession(PlaybackStateCompat.STATE_STOPPED)
        session.isActive = false
        leaveForeground()
    }

    fun switchSet(delta: Int) {
        DriveHub.nextSet(delta)
        player.engine.soundSet = DriveHub.currentSet
        updateSession(if (player.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED)
        if (foreground) goForeground()
    }

    private fun updateSession(state: Int) {
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
        val set = DriveHub.currentSet
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, set?.id ?: "")
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, set?.name ?: getString(R.string.app_name))
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, getString(R.string.tagline))
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, getString(R.string.app_name))
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, set?.name ?: getString(R.string.app_name))
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, getString(R.string.tagline))
                .build()
        )
    }

    // ---------------- 포그라운드 알림 ----------------

    private fun goForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_drivescape)
            .setContentTitle(DriveHub.currentSet?.name ?: getString(R.string.app_name))
            .setContentText(getString(R.string.tagline))
            .setContentIntent(session.controller.sessionActivity)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setMediaSession(session.sessionToken))
            .build()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            foreground = true
        } catch (e: Exception) {
            android.util.Log.w("DrivescapeService", "startForeground 실패", e)
        }
    }

    private fun leaveForeground() {
        if (!foreground) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
    }

    companion object {
        private const val ROOT_ID = "drivescape_root"
        private const val CHANNEL = "playback"
        private const val NOTIFICATION_ID = 1

        /** 프로세스 전체에서 하나의 엔진을 쓴다 (서비스·화면이 같은 소리를 본다). */
        val player = EnginePlayer()

        @Volatile var current: DrivescapeService? = null; private set
    }
}
