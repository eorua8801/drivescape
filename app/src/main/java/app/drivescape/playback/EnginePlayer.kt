package app.drivescape.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import app.drivescape.audio.SAMPLE_RATE
import app.drivescape.audio.SynthEngine
import app.drivescape.audio.UiEvent
import app.drivescape.drive.DriveHub

/**
 * AudioTrack(float 스테레오 48kHz)에 엔진 출력을 계속 써 넣는 렌더 스레드.
 * 실제 재생 위치(playhead)를 알려 주어 화면 표시를 소리와 맞춘다.
 */
class EnginePlayer {
    val engine = SynthEngine { DriveHub.current() }

    private val lock = Any()
    @Volatile private var thread: Thread? = null
    @Volatile private var stopRequested = false
    @Volatile private var restartRequested = false
    @Volatile private var track: AudioTrack? = null
    /** 이 트랙을 시작할 때의 엔진 프레임 */
    @Volatile private var trackStartFrame = 0L

    val isPlaying get() = thread != null && !stopRequested

    fun play() = synchronized(lock) {
        if (thread != null) {
            // 페이드아웃 중에 다시 재생을 누른 경우: 렌더 스레드가 엔진을 다시 시작한다
            if (stopRequested) { stopRequested = false; restartRequested = true }
            return
        }
        stopRequested = false
        restartRequested = false
        val t = Thread({ runLoop() }, "drivescape-audio")
        thread = t
        t.start()
    }

    /** 부드럽게 멈춘다 (0.35초 페이드아웃 후 트랙 해제). */
    fun stop() = synchronized(lock) {
        if (thread != null) stopRequested = true
    }

    private fun runLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val minBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val bufferBytes = maxOf(minBytes * 2, SAMPLE_RATE / 10 * 8)
        val tr = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferBytes)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack 생성 실패", e)
            synchronized(lock) { thread = null; stopRequested = false }
            return
        }
        track = tr
        trackStartFrame = engine.frame
        engine.start()
        tr.play()

        val frames = 256
        val buf = FloatArray(frames * 2)
        var fading = false
        try {
            while (true) {
                if (restartRequested) { restartRequested = false; fading = false; engine.start() }
                if (stopRequested && !fading) { engine.fadeOut(); fading = true }
                engine.render(buf, frames)
                tr.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
                if (fading && engine.isSilent) {
                    val exit = synchronized(lock) {
                        if (stopRequested) { thread = null; stopRequested = false; true } else false
                    }
                    if (exit) break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "렌더 루프 오류", e)
            synchronized(lock) { thread = null; stopRequested = false }
        } finally {
            try { tr.pause(); tr.flush(); tr.release() } catch (_: Exception) {}
            if (track === tr) track = null
        }
    }

    /** 지금 스피커에서 나오고 있는 엔진 프레임 위치 (추정) */
    fun playheadFrame(): Long {
        val tr = track ?: return engine.frame
        val head = tr.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        return trackStartFrame + head
    }

    /** 재생 위치까지 도달한 표시 이벤트를 꺼낸다 (UI 스레드). */
    fun drainEvents(sink: (UiEvent) -> Unit) {
        val now = playheadFrame()
        val q = engine.events
        val keep = ArrayList<UiEvent>()
        while (true) {
            val e = q.poll() ?: break
            if (e.t > now) keep += e else sink(e)
        }
        q.addAll(keep)
    }

    companion object { private const val TAG = "EnginePlayer" }
}
