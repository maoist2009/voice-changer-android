package org.voice.changer.hook

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.voice.changer.BuildConfig
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Xposed entry point, loaded by the framework into every scoped app.
 *
 * It installs hooks on `AudioRecord.read` and then stays out of the way:
 * whether the stream should be converted is decided per call from the
 * configuration the app serves, so enabling/disabling and changing targets
 * take effect within a second without restarting the target app.
 *
 * Only the *terminal* overloads are hooked. The framework's short forms
 * (`read(byte[], int, int)`, `read(short[], int, int)`, `read(ByteBuffer, int)`)
 * are one-line delegations to the four-argument forms, so hooking both layers
 * would run the engine twice over the same PCM: the input rate doubles, the
 * tap's ring never drains, and the output is reset away. Hook the method that
 * actually reaches native and the delegating wrappers come along for free.
 *
 * The only process it refuses is its own: the app records for the Convert page
 * through the same AudioRecord, and converting that would feed the engine its
 * own output. Which *other* apps are converted is the manager's scope plus the
 * app's own list — never a guess from the package name.
 */
class HookEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName ?: return
        val process = lpparam.processName ?: pkg
        // First thing, before any filtering: the fact that this runs at all is
        // the proof the app's status card shows, and the only way the app can
        // learn it (the API classes are not visible from the app's classloader).
        // Reflected: the compile-only stub does not carry every framework member.
        val api = runCatching {
            XposedBridge::class.java.getMethod("getXposedVersion").invoke(null) as Int
        }.getOrNull()
        if (api != null) {
            runCatching { HookStatus.markLoaded(api) }
            Log.i("beatrice-hook", "entry loaded in $process (api $api)")
        }
        // Announce the module once, main process only: enough to prove the
        // scope, and subprocesses would wake the app for every push service.
        // Hooking callApplicationOnCreate looked tidier but is a race the
        // framework wins on some apps: it loads modules *after* the Application
        // already exists there, so the hook never fires and the app never hears
        // from that process. Posting to the main looper has no such dependency.
        if (process == pkg) {
            Handler(Looper.getMainLooper()).postDelayed(
                { runCatching { HookRemote.hello(pkg, pkg) } },
                1_000,
            )
        }
        if (pkg == BuildConfig.APPLICATION_ID) return
        if (!installed.add(process)) return
        try {
            val record = XposedHelpers.findClass("android.media.AudioRecord", lpparam.classLoader)
            val firstRead = java.util.concurrent.atomic.AtomicBoolean(false)
            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    // A direct buffer's position is not advanced by the bytes
                    // read, so remember where it was before the call: that is
                    // where the framework put the samples (and where the
                    // converted ones have to go back).
                    val buffer = param.args?.getOrNull(0) as? ByteBuffer ?: return
                    runCatching { param.setObjectExtra(HookAudio.BB_START, buffer.position()) }
                }

                override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    try {
                        if (firstRead.compareAndSet(false, true)) {
                            Log.d("beatrice-hook", "read hit in $process: ${param.method?.name} args=${param.args?.size} result=${param.result}")
                        }
                        if (HookRemote.enabled(pkg)) HookAudio.intercept(param, pkg, process)
                    } catch (t: Throwable) {
                        HookRemote.log("intercept failed: $t")
                    }
                }
            }
            var hooked = 0
            for (method in record.declaredMethods) {
                if (method.name != "read" || !isTerminalRead(method.parameterTypes)) continue
                XposedBridge.hookMethod(method, hook)
                hooked++
            }
            if (hooked == 0) HookRemote.log("no terminal AudioRecord.read overload in $process")
            else HookRemote.log("AudioRecord.read hooked in $process ($hooked terminal)")
            // Which capture API this app really uses. One line per process: a
            // Java hook can only ever see AudioRecord, so if only MediaRecorder
            // shows up here the answer is a native hook, not more Java work.
            probeConstructors(record, "AudioRecord")
            runCatching { XposedHelpers.findClass("android.media.MediaRecorder", lpparam.classLoader) }
                .getOrNull()?.let { probeConstructors(it, "MediaRecorder") }
        } catch (t: Throwable) {
            HookRemote.log("hook failed in $process: $t")
        }
    }

    private fun probeConstructors(clazz: Class<*>, label: String) {
        val once = java.util.concurrent.atomic.AtomicBoolean(false)
        runCatching {
            XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    if (!once.compareAndSet(false, true)) return
                    Log.d("beatrice-hook", "$label ctor: ${param.args?.joinToString(",") ?: "-"}")
                }
            })
        }
    }

    /**
     * `read(byte[]|short[]|float[], offset, size, readMode)` and
     * `read(ByteBuffer, size, readMode)`: the overloads that call native
     * themselves. The three-argument array forms and the two-argument
     * ByteBuffer form only delegate to these and must stay unhooked.
     */
    private fun isTerminalRead(types: Array<Class<*>>): Boolean = when (types.size) {
        4 -> (types[0] == ByteArray::class.java ||
            types[0] == ShortArray::class.java ||
            types[0] == FloatArray::class.java) &&
            types[1] == Int::class.javaPrimitiveType &&
            types[2] == Int::class.javaPrimitiveType &&
            types[3] == Int::class.javaPrimitiveType
        3 -> types[0] == ByteBuffer::class.java &&
            types[1] == Int::class.javaPrimitiveType &&
            types[2] == Int::class.javaPrimitiveType
        else -> false
    }

    private companion object {
        val installed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }
}
