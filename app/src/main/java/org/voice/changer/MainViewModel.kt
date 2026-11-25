package org.voice.changer

import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.voice.changer.audio.AudioIO
import org.voice.changer.audio.FloatPlayer
import org.voice.changer.audio.NoiseGate
import org.voice.changer.audio.Recorder
import org.voice.changer.engine.Backend
import org.voice.changer.engine.EngineHub
import org.voice.changer.engine.EngineInfo
import org.voice.changer.engine.EngineParams
import org.voice.changer.engine.LiveProcessor
import org.voice.changer.engine.ModelLoader
import org.voice.changer.engine.rvc.RvcStore
import org.voice.changer.hook.HookBridge
import org.voice.changer.hook.HookSettings
import org.voice.changer.hook.HookStatus

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A launchable app the hook can be pointed at. */
data class AppEntry(val pkg: String, val label: String)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    data class UiState(
        val backend: Backend? = null,
        val modelName: String? = null,
        val modelVersion: String? = null,
        /** RVC packs on disk, whether or not RVC is the resident engine. */
        val rvcVoices: List<String> = emptyList(),
        val rvcVoiceHasIndex: List<Boolean> = emptyList(),
        val rvcSelected: Int = 0,
        /** Beatrice rc.0 speakers, from the model's .toml. */
        val beatriceVoices: List<String> = emptyList(),
        val beatriceSelected: Int = 0,
        val outputRate: Int = 24_000,
        val loadingModel: Boolean = false,
        val importing: Boolean = false,
        val inputName: String? = null,
        /** Whether [inputName] came from the microphone rather than a file. */
        val inputFromMic: Boolean = false,
        val recording: Boolean = false,
        val recordSeconds: Double = 0.0,
        val recordLevel: Float = 0f,
        val playingInput: Boolean = false,
        /** Wall-clock time spent in the last conversion, and the same as RTF. */
        val convertWallMs: Long = 0L,
        val convertElapsedMs: Long = 0L,
        val rtf: Double = 0.0,
        val inputSeconds: Double = 0.0,
        val converting: Boolean = false,
        val progress: Float = 0f,
        val resultSeconds: Double = 0.0,
        val playing: Boolean = false,
        val live: Boolean = false,
        val level: Float = 0f,
        val savedName: String? = null,
        val message: String? = null,
        val error: String? = null,
        val pitch: Float = 0f,
        val formant: Float = 0f,
        val intonation: Float = 1f,
        val correction: Float = 0f,
        val vq: Int = 0,
        val output: Float = 1f,
        val rmsMix: Float = 0.25f,
        val protect: Float = 0.5f,
        val filter: Int = 3,
        val f0Tail: Int = 0,
        val indexRate: Float = 0f,
        val nprobe: Int = 0,
        val noiseGate: Float = -50f,
        /** Beatrice's frames per inference; see [EngineParams.chunkFrames]. */
        val chunkFrames: Int = EngineHub.DEFAULT_CHUNK_FRAMES,
        /** Labels for the backend that is not resident, read from disk. */
        val rvcName: String? = null,
        val rvcSubtitle: String? = null,
        val beatriceName: String? = null,
        val beatriceSubtitle: String? = null,
        val hookEnabled: Boolean = false,
        val hookAll: Boolean = true,
        /** Meters: RTF, measured delay and the oscilloscope; off saves a little CPU. */
        val showMeters: Boolean = true,
        val liveRtf: Float = 0f,
        val liveInferMs: Double = 0.0,
        val liveDelayMs: Int = 0,
        val scope: FloatArray = FloatArray(0),
        val hookTargets: Set<String> = emptySet(),
        val hookActive: Boolean = false,
        val hookedPkg: String? = null,
        val hookFrames: Long = 0L,
        val hookLatencyMs: Int = 0,
        val hookLookaheadMs: Int = 0,
        val hookQueueMs: Int = 0,
        val hookInferMs: Double = 0.0,
        /**
         * Xposed API level when the framework injected into this process, else
         * null — which means "unknown", not "disabled".
         */
        val moduleApiVersion: Int? = null,
        /** package -> last time that package asked us for configuration. */
        val hookSeen: Map<String, Long> = emptyMap(),
        val hasBeatrice: Boolean = false,
        val hasRvc: Boolean = false,
        val apps: List<AppEntry> = emptyList(),
    ) {
        val ready: Boolean get() = modelName != null && !loadingModel
        val busy: Boolean get() = loadingModel || importing || converting || recording
        val isRvc: Boolean get() = backend == Backend.RVC
    }

    private val prefs = app.getSharedPreferences("beatrice", Application.MODE_PRIVATE)
    private val store = RvcStore(app.filesDir)

    private val _state = MutableStateFlow(
        UiState(
            beatriceSelected = prefs.getInt("voice", 0),
            pitch = prefs.getFloat("pitch", 0f),
            formant = prefs.getFloat("formant", 0f),
            intonation = prefs.getFloat("intonation", 1f),
            correction = prefs.getFloat("correction", 0f),
            vq = prefs.getInt("vq", 0),
            output = prefs.getFloat("output", 1f),
            rmsMix = prefs.getFloat("rmsMix", 0.25f),
            protect = prefs.getFloat("protect", 0.5f),
            filter = prefs.getInt("filter", 3),
            // 1 used to mean the middle setting, which produced exactly the
            // audio of 2 (see EngineParams.f0Tail); fold both onto low latency.
            f0Tail = if (prefs.getInt("f0Tail", 0) >= 1) 1 else 0,
            indexRate = prefs.getFloat("indexRate", 0f),
            nprobe = prefs.getInt("nprobe", 0),
            noiseGate = prefs.getFloat("noiseGate", -50f),
            chunkFrames = EngineHub.normalizeChunk(
                prefs.getInt("chunkFrames", EngineHub.DEFAULT_CHUNK_FRAMES),
            ),
            hookEnabled = HookSettings.enabled(app),
            hookAll = HookSettings.allApps(app),
            hookTargets = HookSettings.targets(app),
            showMeters = prefs.getBoolean("meters", true),
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val player = FloatPlayer()
    private val recorder = Recorder()
    private var recordJob: Job? = null
    private var input16k: FloatArray? = null
    private var output: FloatArray? = null

    private val beatriceDir: File get() = File(getApplication<Application>().filesDir, "model")

    init {
        if (LiveService.running) {
            _state.value = _state.value.copy(live = true)
            attachLiveCallbacks()
        }
        refreshAvailability()
        val api = HookStatus.loadedApi()
        Log.i("Beatrice", "xposed: moduleApi=$api (null = the module did not load here)")
        _state.value = _state.value.copy(moduleApiVersion = api)
        if (ModelLoader.isModelDir(beatriceDir) || store.isInstalled()) loadEngine()
        watchHook()
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) { loadApps() }
            _state.value = _state.value.copy(apps = apps)
        }
    }

    // --------------------------------------------------------------- hook

    /** Mirrors the bridge's state into the UI once a second. */
    private fun watchHook() {
        viewModelScope.launch {
            while (true) {
                val pkg = HookBridge.lastPkg
                val stats = HookBridge.stats()
                _state.value = _state.value.copy(
                    hookActive = HookBridge.isActive(),
                    hookedPkg = pkg,
                    hookFrames = pkg?.let { HookBridge.deliveredFor(it) } ?: 0L,
                    hookLatencyMs = stats?.totalMs ?: 0,
                    hookLookaheadMs = stats?.lookaheadMs ?: 0,
                    hookQueueMs = stats?.queueMs ?: 0,
                    hookInferMs = stats?.inferMs ?: 0.0,
                    hookSeen = HookStatus.seen(getApplication()),
                    moduleApiVersion = HookStatus.loadedApi(),
                )
                delay(1_000)
            }
        }
    }

    fun setHookEnabled(on: Boolean) {
        val app = getApplication<Application>()
        HookSettings.setEnabled(app, on)
        _state.value = _state.value.copy(hookEnabled = on, error = null)
        try {
            if (on) {
                if (_state.value.live) stopLive()
                // Foreground service keeps the provider and the engine alive
                // while the hooked app is in front.
                LiveService.startHookMode(app)
            } else if (!_state.value.live) {
                runCatching { LiveService.stop(app) }
            }
        } catch (t: Throwable) {
            fail(t)
        }
    }

    fun setHookAll(on: Boolean) {
        HookSettings.setAllApps(getApplication(), on)
        _state.value = _state.value.copy(hookAll = on)
    }

    fun toggleHookTarget(pkg: String) {
        val now = _state.value.hookTargets.toMutableSet()
        if (!now.add(pkg)) now.remove(pkg)
        HookSettings.setTargets(getApplication(), now)
        // Ticking an app is the explicit "only these" gesture, so it takes over
        // from "every app in scope" instead of quietly doing nothing.
        val all = if (now.isEmpty()) _state.value.hookAll else false
        if (all != _state.value.hookAll) HookSettings.setAllApps(getApplication(), all)
        _state.value = _state.value.copy(hookTargets = now, hookAll = all)
    }

    private fun loadApps(): List<AppEntry> {
        val pm = getApplication<Application>().packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching {
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }.getOrDefault(emptyList())
        val self = getApplication<Application>().packageName
        return resolved
            .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != self && !it.pkg.startsWith("org.voice.changer") }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    private fun attachLiveCallbacks() {
        LiveService.onLevel = { lvl -> _state.value = _state.value.copy(level = lvl) }
        // Also flips a live engine that the system restarted without us.
        LiveService.setMeters(_state.value.showMeters)
        LiveService.onStats = { stats ->
            _state.value = _state.value.copy(
                liveRtf = stats.rtf,
                liveInferMs = stats.inferMs,
                liveDelayMs = stats.latencyMs,
            )
        }
        LiveService.onScope = if (_state.value.showMeters) {
            { scope -> _state.value = _state.value.copy(scope = scope) }
        } else {
            null
        }
        LiveService.onError = { t ->
            viewModelScope.launch {
                _state.value = _state.value.copy(live = false, level = 0f, error = t.message ?: t.toString())
            }
        }
    }

    private fun params(s: UiState): EngineParams = EngineParams(
        pitchShift = s.pitch.toDouble(),
        chunkFrames = s.chunkFrames,
        formantShift = s.formant.toDouble(),
        intonationIntensity = s.intonation.toDouble(),
        pitchCorrection = s.correction.toDouble(),
        speaker = s.beatriceSelected,
        vqNeighbors = s.vq,
        outputGain = s.output.toDouble(),
        rmsMixRate = s.rmsMix.toDouble(),
        protect = s.protect.toDouble(),
        filterRadius = s.filter,
        f0Tail = s.f0Tail,
        indexRate = s.indexRate,
        nprobe = s.nprobe,
        noiseGateDb = s.noiseGate,
    )

    // ------------------------------------------------------------- import

    fun importModel(uri: Uri) {
        if (_state.value.busy || _state.value.live) return
        _state.value = _state.value.copy(importing = true, error = null, message = "Inspecting the folder…")
        viewModelScope.launch {
            try {
                val summary = withContext(Dispatchers.IO) {
                    val tree = DocumentFile.fromTreeUri(getApplication(), uri)
                        ?: error("The picked folder could not be opened")
                    val found = store.scan(tree, getApplication<Application>().contentResolver)
                    when {
                        found.beatrice != null -> {
                            importBeatrice(found.beatrice)
                            prefs.edit().putString("backend", Backend.BEATRICE.name).apply()
                            "Beatrice model"
                        }
                        found.shared != null || found.voices.isNotEmpty() -> {
                            val notes = store.import(
                                tree,
                                getApplication<Application>().contentResolver,
                            ) { msg ->
                                _state.value = _state.value.copy(message = "Copying $msg…")
                            }
                            prefs.edit().putString("backend", Backend.RVC.name).apply()
                            notes
                        }
                        else -> error(
                            "That folder holds neither a Beatrice model (.toml + .bin) " +
                                "nor an RVC pack (hubert/rmvpe graphs or encflow/flowreverse/decnsf)",
                        )
                    }
                }
                EngineHub.reset()
                refreshAvailability()
                _state.value = _state.value.copy(importing = false, message = "Imported $summary")
                loadEngine(force = true)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _state.value = _state.value.copy(importing = false, message = null)
                fail(t)
            }
        }
    }

    private fun importBeatrice(root: DocumentFile) {
        val target = beatriceDir
        if (target.exists()) target.deleteRecursively()
        if (!target.mkdirs()) error("Could not create the model folder")
        copyTree(root, target)
        if (!ModelLoader.isModelDir(target)) error("The folder is missing the .toml or the .bin files")
    }

    private fun copyTree(dir: DocumentFile, out: File) {
        val resolver = getApplication<Application>().contentResolver
        for (f in dir.listFiles()) {
            val name = f.name ?: continue
            if (f.isDirectory) {
                val sub = File(out, name)
                sub.mkdirs()
                copyTree(f, sub)
            } else {
                resolver.openInputStream(f.uri)?.use { input ->
                    File(out, name).outputStream().use { input.copyTo(it, 1 shl 16) }
                }
            }
        }
    }

    // ------------------------------------------------------------- engine

    private fun loadEngine(force: Boolean = false) {
        val cached = EngineHub.processor
        val cachedInfo = EngineHub.info
        if (!force && cached != null && cachedInfo != null) {
            adopt(cached, cachedInfo)
            return
        }
        _state.value = _state.value.copy(loadingModel = true, error = null, message = "Preparing the engine…")
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.Default) {
                    EngineHub.ensureDefault(getApplication(), params(_state.value))
                }
                val created = EngineHub.processor ?: error("The engine could not be created")
                adopt(created, info)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e("Beatrice", "engine load failed", t)
                _state.value = _state.value.copy(loadingModel = false, message = null)
                fail(t)
            }
        }
    }

    /**
     * Which backends have their files on disk. Both can be installed at once,
     * but only one is resident, so the UI offers a switch instead of making
     * the user re-import a folder to change engine.
     */
    private fun refreshAvailability() {
        val hasBeatrice = ModelLoader.isModelDir(beatriceDir)
        val beatrice = if (hasBeatrice) ModelLoader.describe(beatriceDir) else null
        val packs = runCatching { store.voices() }.getOrDefault(emptyList())
        val hasRvc = runCatching { store.sharedReady() }.getOrDefault(false) && packs.isNotEmpty()
        // The pack the engine will stage: the last one used, else the first.
        val selected = packs.indexOfFirst { it.name == prefs.getString("rvcVoice", null) }
            .takeIf { it >= 0 } ?: 0
        val beatriceSelected = prefs.getInt("voice", 0)
            .coerceIn(0, (beatrice?.voices?.size ?: 0).coerceAtLeast(1) - 1)
        _state.value = _state.value.copy(
            hasBeatrice = hasBeatrice,
            hasRvc = hasRvc,
            beatriceName = beatrice?.name,
            beatriceSubtitle = beatrice?.let { "${it.version} · on-device CPU" },
            beatriceVoices = beatrice?.voices ?: emptyList(),
            beatriceSelected = beatriceSelected,
            rvcVoices = packs.map { it.name },
            rvcVoiceHasIndex = packs.map { it.hasIndex },
            rvcSelected = selected,
            rvcName = packs.getOrNull(selected)?.name,
            rvcSubtitle = packs.getOrNull(selected)?.let {
                if (it.hasIndex) "RVC v2 · Hexagon NPU · index" else "RVC v2 · Hexagon NPU"
            },
        )
    }

    /** Swaps the resident backend; the choice is persisted for the hook path too. */
    fun setBackend(backend: Backend) {
        val s = _state.value
        if (s.backend == backend || s.busy || s.live) return
        if (s.hookEnabled) {
            showError("Turn off “Use in other apps” before switching the engine.")
            return
        }
        if (backend == Backend.BEATRICE && !s.hasBeatrice) return
        if (backend == Backend.RVC && !s.hasRvc) return
        prefs.edit().putString("backend", backend.name).apply()
        EngineHub.reset()
        loadEngine(force = true)
    }

    private fun adopt(processor: LiveProcessor, info: EngineInfo) {
        Log.i("Beatrice", "engine ready: ${info.backend} ${info.name} @${info.outputRate}")
        val isRvc = info.backend == Backend.RVC
        val packs = runCatching { store.voices() }.getOrDefault(emptyList())
        val rvcVoices = packs.map { it.name }.ifEmpty { if (isRvc) info.voices else emptyList() }
        val rvcSelected = rvcVoices.indexOf(info.name).takeIf { it >= 0 } ?: 0
        val bVoices = if (isRvc) _state.value.beatriceVoices else info.voices
        val bSelected = prefs.getInt("voice", 0).coerceIn(0, bVoices.size.coerceAtLeast(1) - 1)
        if (isRvc) prefs.edit().putString("rvcVoice", info.name).apply()
        _state.value = _state.value.copy(
            backend = info.backend,
            modelName = info.name,
            modelVersion = info.subtitle,
            rvcName = if (isRvc) info.name else _state.value.rvcName,
            rvcSubtitle = if (isRvc) info.subtitle else _state.value.rvcSubtitle,
            beatriceName = if (isRvc) _state.value.beatriceName else info.name,
            beatriceSubtitle = if (isRvc) _state.value.beatriceSubtitle else info.subtitle,
            rvcVoices = rvcVoices,
            rvcVoiceHasIndex = rvcVoices.map { name -> packs.firstOrNull { it.name == name }?.hasIndex == true },
            rvcSelected = rvcSelected,
            beatriceVoices = bVoices,
            beatriceSelected = bSelected,
            outputRate = info.outputRate,
            loadingModel = false,
            message = null,
        )
        applyParams()
    }

    /** RVC: stages the pack; swapping it needs a reload, so ask for one. */
    fun selectRvcVoice(index: Int) {
        val s = _state.value
        val name = s.rvcVoices.getOrNull(index) ?: return
        if (s.busy || s.live) return
        prefs.edit().putString("rvcVoice", name).apply()
        _state.value = s.copy(rvcSelected = index)
        if (s.backend != Backend.RVC || s.modelName == name) return
        val target = runCatching { store.voices() }.getOrDefault(emptyList())
            .firstOrNull { it.name == name } ?: return
        _state.value = _state.value.copy(loadingModel = true, message = "Loading $name…")
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.Default) {
                    EngineHub.ensureRvc(
                        store, target,
                        getApplication<Application>().applicationInfo.nativeLibraryDir,
                        params(_state.value),
                    )
                }
                val created = EngineHub.processor ?: error("The engine could not be created")
                adopt(created, info)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _state.value = _state.value.copy(loadingModel = false, message = null)
                fail(t)
            }
        }
    }

    /** Beatrice: the rc.0 speaker is a parameter, not a reload. */
    fun selectBeatriceVoice(index: Int) {
        val s = _state.value
        if (index !in s.beatriceVoices.indices || s.busy || s.live) return
        _state.value = s.copy(beatriceSelected = index)
        applyParams()
    }

    // ------------------------------------------------------------- params

    fun setPitch(v: Float) { _state.value = _state.value.copy(pitch = v); applyParams() }
    fun setFormant(v: Float) { _state.value = _state.value.copy(formant = v); applyParams() }
    fun setIntonation(v: Float) { _state.value = _state.value.copy(intonation = v); applyParams() }
    fun setCorrection(v: Float) { _state.value = _state.value.copy(correction = v); applyParams() }
    fun setVq(v: Int) { _state.value = _state.value.copy(vq = v); applyParams() }
    fun setOutput(v: Float) { _state.value = _state.value.copy(output = v); applyParams() }
    fun setRmsMix(v: Float) { _state.value = _state.value.copy(rmsMix = v); applyParams() }
    fun setProtect(v: Float) { _state.value = _state.value.copy(protect = v); applyParams() }
    fun setFilter(v: Int) { _state.value = _state.value.copy(filter = v); applyParams() }
    fun setF0Tail(v: Int) { _state.value = _state.value.copy(f0Tail = v); applyParams() }
    fun setIndexRate(v: Float) { _state.value = _state.value.copy(indexRate = v); applyParams() }
    fun setNprobe(v: Int) { _state.value = _state.value.copy(nprobe = v); applyParams() }
    fun setNoiseGate(v: Float) { _state.value = _state.value.copy(noiseGate = v); applyParams() }

    /**
     * Beatrice's frames per inference: the interval between emissions and the
     * size of each one. The graphs are built per value, so an idle engine is
     * rebuilt right away; a conversion that is already running keeps the value
     * it was built at, and this one lands on the next start.
     */
    fun setChunkFrames(v: Int) {
        val chunk = EngineHub.normalizeChunk(v)
        val s = _state.value
        if (s.chunkFrames == chunk) return
        _state.value = s.copy(chunkFrames = chunk)
        prefs.edit().putInt("chunkFrames", chunk).apply()
        if (s.backend != Backend.BEATRICE || EngineHub.chunkFrames == chunk) return
        if (s.busy || s.live || HookBridge.isActive()) {
            _state.value = _state.value.copy(
                message = "Block size saved; it applies from the next conversion",
            )
            return
        }
        EngineHub.reset()
        loadEngine(force = true)
    }

    /**
     * Retrieval is staged with the voice: the index is only read when the
     * pipeline is created, so a rate or probe change needs the voice reloaded.
     * Called when the slider is released rather than on every step.
     */
    fun commitIndexChange() {
        val s = _state.value
        if (s.backend != Backend.RVC || s.busy || s.live) return
        if (s.hookEnabled) {
            showError("Turn off “Use in other apps” before changing the index.")
            return
        }
        EngineHub.reset()
        loadEngine(force = true)
    }

    private fun applyParams() {
        val s = _state.value
        val p = params(s)
        EngineHub.processor?.setParams(p)
        LiveService.updateParams(p)
        prefs.edit()
            .putInt("voice", s.beatriceSelected)
            .putFloat("pitch", s.pitch)
            .putFloat("formant", s.formant)
            .putFloat("intonation", s.intonation)
            .putFloat("correction", s.correction)
            .putInt("vq", s.vq)
            .putFloat("output", s.output)
            .putFloat("rmsMix", s.rmsMix)
            .putFloat("protect", s.protect)
            .putInt("filter", s.filter)
            .putInt("f0Tail", s.f0Tail)
            .putFloat("indexRate", s.indexRate)
            .putInt("nprobe", s.nprobe)
            .putFloat("noiseGate", s.noiseGate)
            .apply()
    }

    // --------------------------------------------------------------- live

    fun startLive() {
        if (_state.value.busy || _state.value.live || !_state.value.ready) return
        if (EngineHub.processor == null) return
        // Both modes want the same stateful engine, and a hooked stream must
        // never fall back to raw audio, so the live loop waits for it to end.
        if (org.voice.changer.hook.HookBridge.isActive()) {
            _state.value = _state.value.copy(
                error = "变声转换正在进行，等目标 App 停止录音后再试听",
            )
            return
        }
        if (_state.value.hookEnabled) {
            HookSettings.setEnabled(getApplication(), false)
            _state.value = _state.value.copy(hookEnabled = false)
        }
        player.stop()
        attachLiveCallbacks()
        LiveService.updateParams(params(_state.value))
        _state.value = _state.value.copy(live = true, level = 0f, error = null, message = null, playing = false)
        try {
            LiveService.start(getApplication())
        } catch (t: Throwable) {
            _state.value = _state.value.copy(live = false, error = t.message ?: t.toString())
        }
    }

    fun stopLive() {
        if (!_state.value.live) return
        _state.value = _state.value.copy(
            live = false,
            level = 0f,
            liveRtf = 0f,
            liveInferMs = 0.0,
            liveDelayMs = 0,
            scope = FloatArray(0),
        )
        runCatching { LiveService.stop(getApplication()) }
    }

    /** Turns the live meters off: the engine stops copying scope data entirely. */
    fun setMeters(on: Boolean) {
        prefs.edit().putBoolean("meters", on).apply()
        LiveService.setMeters(on)
        LiveService.onScope = if (on) {
            { scope -> _state.value = _state.value.copy(scope = scope) }
        } else {
            null
        }
        _state.value = _state.value.copy(
            showMeters = on,
            scope = if (on) _state.value.scope else FloatArray(0),
        )
    }

    fun showError(text: String) {
        _state.value = _state.value.copy(error = text)
    }

    // ----------------------------------------------------------- offline

    fun pickAudio(uri: Uri, name: String?) {
        if (_state.value.busy || _state.value.live) return
        player.stop()
        _state.value = _state.value.copy(
            message = "Decoding audio…",
            error = null,
            playing = false,
            playingInput = false,
        )
        viewModelScope.launch {
            try {
                val decoded = withContext(Dispatchers.IO) {
                    val audio = AudioIO.decodeMono(getApplication(), uri)
                    val pcm16 = AudioIO.resample(audio.pcm, audio.sampleRate, 16_000)
                    Triple(pcm16, audio.pcm.size.toDouble() / audio.sampleRate, name)
                }
                input16k = decoded.first
                output = null
                _state.value = _state.value.copy(
                    inputName = decoded.third ?: "Clip",
                    inputSeconds = decoded.second,
                    inputFromMic = false,
                    resultSeconds = 0.0,
                    convertWallMs = 0L,
                    convertElapsedMs = 0L,
                    rtf = 0.0,
                    message = null,
                )
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _state.value = _state.value.copy(message = null)
                fail(t)
            }
        }
    }

    /**
     * Records the microphone into the input slot. The samples then take the
     * same route as a picked file, so nothing downstream knows the difference.
     */
    fun startRecording() {
        val s = _state.value
        if (s.recording || s.busy || s.live) return
        if (!recorder.start()) {
            showError("The microphone could not be opened")
            return
        }
        player.stop()
        _state.value = s.copy(
            recording = true,
            recordSeconds = 0.0,
            recordLevel = 0f,
            playing = false,
            playingInput = false,
            error = null,
            message = null,
        )
        recordJob = viewModelScope.launch {
            while (_state.value.recording && recorder.isRunning) {
                _state.update {
                    it.copy(recordSeconds = recorder.seconds, recordLevel = recorder.level)
                }
                delay(100)
            }
            if (_state.value.recording) stopRecording()
        }
    }

    fun stopRecording() {
        if (!_state.value.recording) return
        recordJob?.cancel()
        recordJob = null
        val pcm = recorder.stop()
        _state.value = _state.value.copy(recording = false, recordLevel = 0f, recordSeconds = 0.0)
        if (pcm.size < Recorder.SAMPLE_RATE / 10) {
            showError("The recording was too short")
            return
        }
        input16k = pcm
        output = null
        _state.value = _state.value.copy(
            inputName = "Mic recording",
            inputSeconds = pcm.size / Recorder.SAMPLE_RATE.toDouble(),
            inputFromMic = true,
            resultSeconds = 0.0,
            convertWallMs = 0L,
            convertElapsedMs = 0L,
            rtf = 0.0,
            savedName = null,
            error = null,
            message = null,
        )
    }

    /** Preview of what will be converted, before spending time on it. */
    fun toggleInputPlay() {
        val pcm = input16k ?: return
        if (_state.value.playingInput) {
            player.stop()
            _state.value = _state.value.copy(playingInput = false)
        } else {
            player.stop()
            _state.value = _state.value.copy(playing = false, playingInput = true)
            player.play(pcm, Recorder.SAMPLE_RATE, viewModelScope) {
                _state.value = _state.value.copy(playingInput = false)
            }
        }
    }

    fun convert() {
        val pcm = input16k ?: return
        val e = EngineHub.processor ?: return
        if (_state.value.busy || _state.value.live) return
        if (_state.value.hookEnabled) {
            // The hooked stream owns the stateful processor while it is on.
            showError("Turn off “Use in other apps” before converting a file.")
            return
        }
        player.stop()
        val rate = e.outputRate
        _state.value = _state.value.copy(
            converting = true,
            progress = 0f,
            convertElapsedMs = 0L,
            playing = false,
            playingInput = false,
            error = null,
            savedName = null,
            message = null,
        )
        viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            val ticker = launch {
                while (true) {
                    _state.update {
                        it.copy(convertElapsedMs = SystemClock.elapsedRealtime() - startedAt)
                    }
                    delay(200)
                }
            }
            try {
                val out = withContext(Dispatchers.Default) {
                    val p = params(_state.value)
                    e.setParams(p)
                    val gated = pcm.copyOf()
                    NoiseGate(16_000).apply {
                        setThresholdDb(p.noiseGateDb)
                        process(gated)
                    }
                    e.convert(gated) { done -> _state.update { it.copy(progress = done) } }
                }
                val wall = SystemClock.elapsedRealtime() - startedAt
                val audio = pcm.size / 16_000.0
                val rtf = if (audio > 0) wall / 1000.0 / audio else 0.0
                Log.i("Beatrice", "converted ${out.size} samples @$rate in ${wall} ms (RTF %.2f)".format(rtf))
                output = out
                _state.value = _state.value.copy(
                    converting = false,
                    outputRate = rate,
                    resultSeconds = out.size / rate.toDouble(),
                    convertWallMs = wall,
                    convertElapsedMs = wall,
                    rtf = rtf,
                    message = null,
                )
            } catch (c: CancellationException) {
                _state.value = _state.value.copy(converting = false)
                throw c
            } catch (t: Throwable) {
                _state.value = _state.value.copy(converting = false)
                fail(t)
            } finally {
                ticker.cancel()
            }
        }
    }

    fun togglePlay() {
        val out = output ?: return
        if (!_state.value.playing) {
            player.stop()
            _state.value = _state.value.copy(playingInput = false)
        }
        if (_state.value.playing) {
            player.stop()
            _state.value = _state.value.copy(playing = false)
        } else {
            _state.value = _state.value.copy(playing = true)
            player.play(out, _state.value.outputRate, viewModelScope) {
                _state.value = _state.value.copy(playing = false)
            }
        }
    }

    fun save() {
        val out = output ?: return
        val stamp = System.currentTimeMillis() / 1000
        val prefix = if (_state.value.backend == Backend.RVC) "rvc" else "beatrice"
        val rate = _state.value.outputRate
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    AudioIO.saveWav(getApplication(), out, rate, "${prefix}_$stamp.wav")
                }
                _state.value = _state.value.copy(savedName = "${prefix}_$stamp.wav", message = "Saved to Music/Beatrice")
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null, error = null, savedName = null)
    }

    private fun fail(t: Throwable) {
        _state.value = _state.value.copy(error = t.message ?: t.toString())
    }

    override fun onCleared() {
        player.stop()
        recordJob?.cancel()
        recordJob = null
        if (recorder.isRunning) recorder.stop()
        LiveService.onLevel = null
        LiveService.onError = null
        // The engine stays in EngineHub: the live service or the next activity may still need it.
    }
}
