package org.voice.changer.lab

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * A manual trigger, not the recorder.
 *
 * Every button starts [CaptureService], which does the capture in its own
 * process with the activity out of the way; results go to logcat (tag `lab`)
 * and to WAV files. The same thing is reachable from a shell:
 *
 *   am start-foreground-service -n org.voice.changer.lab/.CaptureService \
 *       --ez run_all true --ei seconds 3
 */
class MainActivity : Activity() {
    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var dir: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dir = Lab.dir(getExternalFilesDir(null)!!)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        root.addView(TextView(this).apply {
            text = "Capture Lab\n结果写入 $dir\n日志: logcat -s lab"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        buttons.addView(Button(this).apply {
            text = "运行全部（后台服务）"
            setOnClickListener { start(null) }
        })
        for (path in Lab.paths()) {
            buttons.addView(Button(this).apply {
                text = path.label
                setOnClickListener { start(path.id) }
            })
        }
        root.addView(ScrollView(this).apply {
            addView(buttons)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 3f)
        })

        log = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply {
            addView(log)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f)
        }
        root.addView(logScroll)
        setContentView(root)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        } else if (intent?.getBooleanExtra("run_all", false) == true) {
            start(null)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        append("RECORD_AUDIO granted=$granted")
    }

    private fun start(only: String?) {
        val intent = Intent(this, CaptureService::class.java).putExtra("seconds", 3)
        if (only != null) intent.putExtra("only", only)
        startForegroundService(intent)
        append("started ${only ?: "all paths"} in :capture (logcat -s lab)")
    }

    private fun append(line: String) {
        runOnUiThread {
            log.append("$line\n")
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
}
