package org.voice.changer.engine

import ai.onnxruntime.OrtEnvironment
import android.util.Log
import org.voice.changer.LiveService
import org.voice.changer.engine.rvc.RvcJni
import org.voice.changer.engine.rvc.RvcProcessor
import org.voice.changer.engine.rvc.RvcStore
import java.io.File

enum class Backend { BEATRICE, RVC }

/** What the UI needs to know about the engine that is loaded. */
data class EngineInfo(
    val backend: Backend,
    val name: String,
    /** Small caption under the name: version, device, voice count… */
    val subtitle: String,
    /** Selectable voices: rc.0 speakers, or the one staged RVC voice. */
    val voices: List<String>,
    val outputRate: Int,
)

/**
 * Process-wide owner of the loaded model and its processor.
 *
 * Live mode runs in a foreground service while the screen is off, so the
 * engine cannot belong to a ViewModel: the UI must be able to come and go
 * without tearing down the audio graph. Exactly one backend is resident at a
 * time — the RVC graphs alone are ~220 MB of NPU contexts, so keeping both
 * loaded would invite the low-memory killer.
 */
object EngineHub {
    /**
     * Frames per inference. This is the dominant latency lever, because the
     * engine needs two chunks of look-ahead and emits one chunk per call:
     *
     * | chunk | look-ahead | emit | inference | measured end to end |
     * |-------|-----------|------|-----------|---------------------|
     * |  12   | 262 ms    | 120  | 28-40 ms  | 479 ms              |
     * |   8   | 182 ms    |  80  | 24-36 ms  | 220-300 ms          |
     * |   4   | 102 ms    |  40  | 16-27 ms  | 180-260 ms          |
     *
     * The graphs are built per chunk from the same weights, so the chunk size
     * costs CPU, not quality; 4 is the floor (the phone graph needs a multiple
     * of four). 8 leaves 2-3x headroom, which is what a screen-off recording
     * needs when the device is busy.
     */
    const val CHUNK_FRAMES = 8
    const val ATTN_POSITIONS = 64

    private const val TAG = "engine_hub"

    @Volatile var info: EngineInfo? = null
        private set

    @Volatile var processor: LiveProcessor? = null
        private set

    private var rc0: Rc0Processor? = null
    private var rvc: RvcProcessor? = null

    @Volatile private var qnnReady = false

    /** Builds the rc.0 engine at most once, even when two activities race a cold start. */
    @Synchronized
    fun ensureBeatrice(dir: File, params: EngineParams): EngineInfo {
        val existing = rc0
        val loaded = info
        if (existing != null && loaded?.backend == Backend.BEATRICE) return loaded

        release()
        val parsed = ModelLoader.load(dir)
        val created = Rc0Processor.create(
            OrtEnvironment.getEnvironment(),
            parsed,
            chunkFrames = CHUNK_FRAMES,
            attnPositions = ATTN_POSITIONS,
            params = params,
        )
        rc0 = created
        processor = created
        val info = EngineInfo(
            backend = Backend.BEATRICE,
            name = parsed.name,
            subtitle = "${parsed.version} · on-device CPU",
            voices = parsed.voices.map { it.name },
            outputRate = created.outputRate,
        )
        this.info = info
        return info
    }

    /**
     * Builds the RVC NPU engine for [voice], staging its graphs first.
     * [libDir] must be the app's nativeLibraryDir (the HTP stub resolves the
     * skel from there).
     */
    @Synchronized
    fun ensureRvc(
        store: RvcStore,
        voice: RvcStore.Voice,
        libDir: String,
        params: EngineParams,
    ): EngineInfo {
        val existing = rvc
        val loaded = info
        if (existing != null && loaded?.backend == Backend.RVC && loaded.voices.firstOrNull() == voice.name) {
            return loaded
        }

        release()
        if (!RvcJni.load()) error("librvc_qnn.so could not be loaded")
        if (!qnnReady) {
            val err = RvcJni.nativeInit(libDir, libDir)
            if (err.isNotEmpty()) error("QNN init: $err")
            qnnReady = true
        }
        val stageErr = store.stage(voice)
        if (stageErr != null) error("staging ${voice.name}: $stageErr")

        val manifest = File(store.stageDir, "voice.json")
            .takeIf { it.isFile }?.readText()
            ?: error("${voice.name} has no voice.json")
        val merged = mergeManifest(manifest, params, voice.hasIndex)

        val createErr = RvcJni.pipelineCreate()
        if (createErr.isNotEmpty()) error("pipelineCreate: $createErr")
        val initErr = RvcJni.pipelineInit(store.stageDir.absolutePath, libDir, merged)
        if (initErr.isNotEmpty()) {
            RvcJni.pipelineDestroy()
            error("pipelineInit: $initErr")
        }

        val sr = runCatching { org.json.JSONObject(merged).optInt("sr", 40_000) }.getOrDefault(40_000)
        val created = RvcProcessor(voice.name, sr)
        created.setParams(params)
        rvc = created
        processor = created
        val info = EngineInfo(
            backend = Backend.RVC,
            name = voice.name,
            subtitle = "RVC v2 · Hexagon NPU · ${sr / 1000} kHz",
            voices = listOf(voice.name),
            outputRate = sr,
        )
        this.info = info
        Log.i(TAG, "RVC ready: ${info.name} @${sr} Hz")
        return info
    }

    /**
     * Merges the voice manifest with the session-level tunables. The native
     * parser reads the keys it knows and ignores the rest, so re-serialising
     * the object is safe.
     */
    private fun mergeManifest(manifest: String, p: EngineParams, hasIndex: Boolean): String = try {
        val o = org.json.JSONObject(manifest)
        o.put("sid", p.speakerId)
        o.put("rms_mix_rate", p.rmsMixRate)
        o.put("protect", p.protect)
        o.put("filter_radius", p.filterRadius)
        o.put("f0_tail", p.f0Tail)
        // Retrieval needs the .index file; without one, skip the work entirely.
        o.put("index_rate", if (hasIndex) p.indexRate else 0.0f)
        if (hasIndex) o.put("nprobe", p.nprobe) else o.put("nprobe", 0)
        o.toString()
    } catch (e: Exception) {
        Log.w(TAG, "manifest is not an object; using defaults")
        manifest
    }

    /**
     * Runs a throwaway stretch of silence through the backend.
     *
     * The first inference after a load is several times slower (NPU queues,
     * CPU kernels, first-touch page faults). Doing it here — while no real
     * audio is waiting — keeps that spike out of the first hooked buffer.
     */
    fun warmup(processor: LiveProcessor) {
        runCatching {
            processor.reset()
            val silence = PcmSource { 0f }
            var steps = 0
            while (processor.outputSamples < 9_000 && steps < 12) {
                processor.next(silence)
                steps++
            }
        }
        runCatching { processor.reset() }
    }

    /** Drops the cached engine; the live service keeps its own reference until it stops. */
    @Synchronized
    fun reset() {
        release()
    }

    /**
     * Loads whichever backend the user last used. Called by the live service
     * on a cold start (the process may have been restarted by the system
     * while the screen was off), so the choice has to come from prefs + disk
     * rather than from a ViewModel.
     */
    fun ensureDefault(context: android.content.Context, params: EngineParams): EngineInfo {
        val files = context.filesDir
        val prefs = context.getSharedPreferences("beatrice", android.content.Context.MODE_PRIVATE)
        val store = RvcStore(files)
        val wantRvc = prefs.getString("backend", null) == Backend.RVC.name
        val rvcVoice = prefs.getString("rvcVoice", null)
        val voices = store.voices()
        val voice = voices.firstOrNull { it.name == rvcVoice } ?: voices.firstOrNull()
        val libDir = context.applicationInfo.nativeLibraryDir
        if (wantRvc && store.sharedReady() && voice != null) {
            return ensureRvc(store, voice, libDir, params)
        }
        val beatriceDir = File(files, "model")
        if (ModelLoader.isModelDir(beatriceDir)) return ensureBeatrice(beatriceDir, params)
        if (store.sharedReady() && voices.isNotEmpty()) return ensureRvc(store, voice!!, libDir, params)
        error("No model is installed")
    }

    private fun release() {
        val old = processor
        processor = null
        info = null
        rc0 = null
        rvc = null
        // The microphone loop holds its own reference and stops asynchronously;
        // anything else (UI, hook bridge) must not keep a dead native context.
        if (!LiveService.micActive) runCatching { old?.close() }
    }
}
