package org.voice.changer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.voice.changer.audio.AudioIO
import org.voice.changer.audio.LiveEngine
import org.voice.changer.engine.EngineHub
import org.voice.changer.engine.EngineParams
import org.voice.changer.engine.LiveProcessor
import org.voice.changer.hook.HookBridge
import org.voice.changer.hook.HookSettings
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Foreground service hosting the microphone loop.
 *
 * Android 14 only allows microphone access from a foreground service with the
 * `microphone` type, and a cached process (screen off, app behind the lock
 * screen) is the first thing the low-memory killer reaps. Both are why live
 * mode cannot run inside the activity.
 */
class LiveService : Service() {
    private var live: LiveEngine? = null
    private var debugChunks: MutableList<FloatArray>? = null
    private var debugOutput: String? = null
    private var debugPlayer: AudioTrack? = null
    private val debugPlaying = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action} debug=${BuildConfig.DEBUG}")
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIF_ID, notification())
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed", t)
            dispatchError(t)
            stopSelf()
            return START_NOT_STICKY
        }
        HookBridge.attach(this)
        // Hook mode only keeps the process (and therefore the provider and the
        // engine) alive; the microphone loop is a separate, mutually exclusive
        // mode. A system restart arrives with a null intent, so the mode comes
        // from the persisted setting in that case.
        val hookOnly = intent?.getBooleanExtra(EXTRA_HOOK_ONLY, false)
            ?: HookSettings.enabled(this)
        if (!hookOnly && live == null) {
            val processor = EngineHub.processor
            if (processor != null) {
                startEngine(processor, intent)
            } else {
                // The process may have been restarted by the system after a kill.
                Thread {
                    try {
                        EngineHub.ensureDefault(this, pendingParams ?: EngineParams())
                        val engine = EngineHub.processor
                            ?: error("The engine could not be created")
                        startEngine(engine, intent)
                    } catch (t: Throwable) {
                        Log.e(TAG, "engine start failed", t)
                        dispatchError(t)
                        stopSelf()
                    }
                }.start()
            }
        }
        if (hookOnly && EngineHub.processor == null) {
            // Warm the engine so the first hooked buffer does not pay the
            // ~1.5 s model load (during which the app hears itself raw).
            Thread {
                runCatching {
                    EngineHub.ensureDefault(this, pendingParams ?: EngineParams())
                    EngineHub.processor?.let { EngineHub.warmup(it) }
                }.onFailure { Log.w(TAG, "hook preload failed: ${it.message}") }
            }.start()
        }
        return START_STICKY
    }

    private fun startEngine(processor: LiveProcessor, intent: Intent?) {
        Log.i(TAG, "starting engine: ${processor.javaClass.simpleName} @${processor.outputRate} Hz")
        val engine = LiveEngine(processor)
        engine.setParams(pendingParams ?: EngineParams())
        engine.wantScope = metersEnabled
        engine.onStats = { stats -> onStats?.invoke(stats) }
        engine.onScope = { scope -> onScope?.invoke(scope) }
        if (BuildConfig.DEBUG) attachDebugTap(intent, engine)
        live = engine
        running = true
        micActive = true
        val levelSink: (Float) -> Unit = { level -> onLevel?.invoke(level) }
        val errorSink: (Throwable) -> Unit = { t ->
            dispatchError(t)
            stopSelf()
        }
        val feed = if (BuildConfig.DEBUG) intent?.getStringExtra("feed") else null
        if (feed != null) {
            val decoded = AudioIO.decodeMono(feed)
            val pcm = AudioIO.resample(decoded.pcm, decoded.sampleRate, 16_000)
            engine.startFromFile(pcm, levelSink, errorSink)
        } else {
            engine.start(levelSink, errorSink)
        }
    }

    /** Debug-only: capture the synthesized stream and optionally loop a file into the speaker. */
    private fun attachDebugTap(intent: Intent?, engine: LiveEngine) {
        val tap = intent?.getStringExtra("tap") ?: return
        val chunks = Collections.synchronizedList(ArrayList<FloatArray>())
        debugChunks = chunks
        debugOutput = tap
        engine.onAudio = { chunks.add(it) }
        val playPath = intent.getStringExtra("play")
        if (playPath != null) {
            debugPlaying.set(true)
            val playRate = engine.outputRate
            Thread {
                try {
                    val decoded = AudioIO.decodeMono(playPath)
                    val pcm = if (decoded.sampleRate == playRate) decoded.pcm
                    else AudioIO.resample(decoded.pcm, decoded.sampleRate, playRate)
                    val minBuf = AudioTrack.getMinBufferSize(
                        playRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_FLOAT,
                    )
                    val track = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build(),
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                                .setSampleRate(playRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build(),
                        )
                        .setBufferSizeInBytes(max(minBuf, playRate / 5 * 4))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                    debugPlayer = track
                    track.play()
                    while (debugPlaying.get()) {
                        var off = 0
                        while (off < pcm.size && debugPlaying.get()) {
                            val n = track.write(pcm, off, minOf(4096, pcm.size - off), AudioTrack.WRITE_BLOCKING)
                            if (n <= 0) break
                            off += n
                        }
                    }
                    runCatching { track.stop() }
                    runCatching { track.release() }
                } catch (t: Throwable) {
                    Log.e(TAG, "debug playback failed", t)
                }
            }.also { it.start() }
        }
        val seconds = intent.getIntExtra("seconds", 8)
        Handler(Looper.getMainLooper()).postDelayed({ stopSelf() }, seconds * 1000L)
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        val wasLive = micActive
        running = false
        micActive = false
        instance = null
        // In hook-only mode this service is a keep-alive around the provider, so
        // tearing the bridge down here would kill a recording that is still
        // converting (it did exactly that once). Only the microphone loop owns
        // the engine and is allowed to drop streams.
        if (wasLive) HookBridge.detach()
        debugPlaying.set(false)
        runCatching { debugPlayer?.stop() }
        runCatching { debugPlayer?.release() }
        debugPlayer = null
        live?.stop()
        val rate = live?.outputRate ?: 24_000
        live = null
        debugChunks?.let { chunks ->
            val total = chunks.sumOf { it.size }
            val out = FloatArray(total)
            var off = 0
            for (c in chunks) {
                System.arraycopy(c, 0, out, off, c.size)
                off += c.size
            }
            if (total > 0) {
                var sum = 0.0
                var peak = 0f
                for (v in out) {
                    val a = if (v < 0) -v else v
                    if (a > peak) peak = a
                    sum += v.toDouble() * v
                }
                Log.i(
                    TAG,
                    "live ${total} samples rms=${"%.4f".format(kotlin.math.sqrt(sum / total))} peak=${"%.4f".format(peak)}",
                )
                debugOutput?.let { path ->
                    runCatching {
                        FileOutputStream(path).use { it.write(AudioIO.encodeWav(out, rate)) }
                        Log.i(TAG, "wrote $path")
                    }.onFailure { Log.e(TAG, "debug write failed", it) }
                }
            }
        }
        debugChunks = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, LiveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Beatrice is changing your voice")
            .setContentText("Tap to open, or stop from here")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, "Live voice change", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Shown while the microphone is being converted"
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "BeatriceLive"
        private const val CHANNEL = "live"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "org.voice.changer.STOP"
        const val EXTRA_HOOK_ONLY = "hookOnly"

        @Volatile private var instance: LiveService? = null

        @Volatile var running = false
            private set

        /** True while the local microphone loop owns the engine. */
        @Volatile var micActive = false
            private set

        @Volatile var onLevel: ((Float) -> Unit)? = null
        @Volatile var onError: ((Throwable) -> Unit)? = null

        /** Mirrors the UI's "Meters" switch; off means nothing is sampled or drawn. */
        @Volatile var metersEnabled = true
        @Volatile var onStats: ((LiveEngine.Stats) -> Unit)? = null
        @Volatile var onScope: ((FloatArray) -> Unit)? = null

        fun setMeters(on: Boolean) {
            metersEnabled = on
            instance?.live?.wantScope = on
        }
        @Volatile private var pendingParams: EngineParams? = null

        fun updateParams(params: EngineParams) {
            pendingParams = params
            instance?.live?.setParams(params)
            HookBridge.setParams(params)
        }

        fun start(context: Context) {
            val intent = Intent(context, LiveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Keeps the process alive for the Xposed bridge without opening the mic. */
        fun startHookMode(context: Context) {
            val intent = Intent(context, LiveService::class.java).putExtra(EXTRA_HOOK_ONLY, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveService::class.java))
        }

        private fun dispatchError(t: Throwable) {
            if (running) onError?.invoke(t)
        }

        private fun dispatchLevel(level: Float) {
            onLevel?.invoke(level)
        }
    }
}
