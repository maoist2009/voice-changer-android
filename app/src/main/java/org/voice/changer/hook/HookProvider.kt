package org.voice.changer.hook

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log

/**
 * Binder surface the Xposed module uses from inside a hooked app.
 *
 * The provider is exported: the hook runs as the target app's uid and cannot
 * read our private files, so the configuration and the audio bridge both come
 * through here. It only ever talks to [HookBridge], which keeps the engine in
 * this process.
 */
class HookProvider : ContentProvider() {
    companion object {
        private const val TAG = "hook_provider"
        const val METHOD_CONFIG = "config"
        const val METHOD_PROCESS = "process"
        const val METHOD_NEED = "need"
        const val METHOD_REPORT = "report"
        const val METHOD_HELLO = "hello"
    }

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        HookBridge.attach(ctx)
        // Process start is the earliest point the engine can be loaded, and the
        // latest that still pays off: the first capture buffer is seconds away.
        HookBridge.warm()
        // Keep the process out of the cached cgroup. Starting a foreground
        // service from the background is restricted, so this may fail; the
        // settings screen starts it reliably when the hook is switched on.
        if (HookSettings.enabled(ctx)) runCatching { org.voice.changer.LiveService.startHookMode(ctx) }
        Log.i(TAG, "provider ready")
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        return when (method) {
            METHOD_CONFIG -> {
                // Every configuration request is a heartbeat: it proves the
                // framework loaded the module into the calling package, which
                // is the only scope evidence available without root.
                context?.let { HookStatus.touchCaller(it, extras?.getString("pkg")) }
                HookBridge.config()
            }
            // Sent once per process by HookEntry, so the app can show which
            // targets the framework really loads, before any audio flows.
            METHOD_HELLO -> {
                context?.let { HookStatus.touchCaller(it, extras?.getString("pkg")) }
                Log.i(TAG, "hello from ${extras?.getString("process")}, seen=${HookStatus.seen(requireNotNull(context)).keys}")
                Bundle()
            }
            METHOD_PROCESS -> HookBridge.processCall(extras)
            METHOD_NEED -> HookBridge.needCall(extras)
            METHOD_REPORT -> {
                HookBridge.reportCall(extras)
                Bundle()
            }
            else -> Bundle()
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
