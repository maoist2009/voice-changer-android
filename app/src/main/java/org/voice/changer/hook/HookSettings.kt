package org.voice.changer.hook

import android.content.Context
import org.voice.changer.engine.EngineHub
import org.voice.changer.engine.EngineParams

/**
 * The hook's persisted settings, shared by the UI, the content provider and
 * the foreground service.
 *
 * Everything lives in the app's regular `beatrice` preferences so a hooked
 * stream converts with exactly the parameters the user last saw in the UI.
 */
object HookSettings {
    const val PREFS = "beatrice"

    private const val KEY_ENABLED = "hookEnabled"
    private const val KEY_ALL = "hookAllApps"
    private const val KEY_TARGETS = "hookTargets"

    /** A target list entry that stands for "every app". */
    const val ALL = "*"

    fun enabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * Default on: which apps the module is injected into is the manager's scope,
     * so the app should not make the user tick the same list twice.
     */
    fun allApps(context: Context): Boolean = prefs(context).getBoolean(KEY_ALL, true)

    fun setAllApps(context: Context, all: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALL, all).apply()
    }

    fun targets(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_TARGETS, emptySet())?.toSet() ?: emptySet()

    fun setTargets(context: Context, packages: Set<String>) {
        prefs(context).edit().putStringSet(KEY_TARGETS, packages).apply()
    }

    /** True when the master switch is on and [pkg] is in scope. */
    fun covers(context: Context, pkg: String): Boolean =
        enabled(context) && (allApps(context) || targets(context).contains(pkg))

    /** The conversion parameters, mirroring the ones the UI persists. */
    fun engineParams(context: Context): EngineParams {
        val p = prefs(context)
        return EngineParams(
            pitchShift = p.getFloat("pitch", 0f).toDouble(),
            chunkFrames = EngineHub.normalizeChunk(
                p.getInt("chunkFrames", EngineHub.DEFAULT_CHUNK_FRAMES),
            ),
            formantShift = p.getFloat("formant", 0f).toDouble(),
            intonationIntensity = p.getFloat("intonation", 1f).toDouble(),
            pitchCorrection = p.getFloat("correction", 0f).toDouble(),
            speaker = p.getInt("voice", 0),
            speakerId = p.getInt("voice", 0),
            vqNeighbors = p.getInt("vq", 0),
            outputGain = p.getFloat("output", 1f).toDouble(),
            rmsMixRate = p.getFloat("rmsMix", 0.25f).toDouble(),
            protect = p.getFloat("protect", 0.5f).toDouble(),
            filterRadius = p.getInt("filter", 3),
            f0Tail = if (p.getInt("f0Tail", 0) >= 1) 1 else 0,
            indexRate = p.getFloat("indexRate", 0f),
            nprobe = p.getInt("nprobe", 0),
            noiseGateDb = p.getFloat("noiseGate", -50f),
        )
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
