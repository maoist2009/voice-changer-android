package org.voice.changer.lab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.util.Log

/**
 * Runs the capture paths with no UI in front.
 *
 *   am start-foreground-service -n org.voice.changer.lab/.CaptureService \
 *       --ez run_all true --ei seconds 3 --ei gap_ms 1500
 *
 *   am start-foreground-service -n org.voice.changer.lab/.CaptureService \
 *       --es only j5-bytebuffer-44k
 *
 * A foreground service of type `microphone` counts as "in use", which is what
 * makes capture legal with the screen off and the activity gone. It also runs
 * in the `:capture` process, so a module scoped to the package has to reach a
 * non-main process to affect these files at all.
 */
class CaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val seconds = intent?.getIntExtra("seconds", 3) ?: 3
        val gapMs = intent?.getIntExtra("gap_ms", 1500) ?: 1500
        val only = intent?.getStringExtra("only")
        startForeground(1, notification())

        Thread {
            try {
                val dir = Lab.dir(getExternalFilesDir(null)!!)
                val list = Lab.paths().let { all -> if (only != null) all.filter { it.id == only } else all }
                Log.i("lab", "=== lab start pid=${Process.myPid()} paths=${list.size} seconds=$seconds dir=$dir")
                for (path in list) {
                    Lab.run(path, dir, seconds)
                    Thread.sleep(gapMs.toLong())
                }
                Log.i("lab", "=== lab done pid=${Process.myPid()}")
            } catch (t: Throwable) {
                Log.w("lab", "lab failed: $t")
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.apply { isDaemon = true }.start()

        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("lab", "Capture Lab", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, "lab")
            .setContentTitle("Capture Lab")
            .setContentText("recording test paths")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }
}
