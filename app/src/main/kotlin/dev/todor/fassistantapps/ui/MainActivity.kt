package dev.todor.fassistantapps.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.todor.fassistantapps.R
import dev.todor.fassistantapps.check.DownloadCheck
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var runButton: Button
    private lateinit var shareButton: Button
    private lateinit var log: TextView
    private lateinit var scroller: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = dp(20)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        column.addView(TextView(this).apply {
            text = getString(R.string.check_title)
            setTypeface(null, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        })

        column.addView(TextView(this).apply {
            text = getString(R.string.check_intro)
            setPadding(0, dp(10), 0, dp(18))
        })

        runButton = Button(this).apply {
            text = getString(R.string.check_run)
            setOnClickListener { runCheck() }
        }
        column.addView(runButton)

        // These phones are never plugged into anything, so the only way the report reaches a
        // laptop is the user sending it.
        shareButton = Button(this).apply {
            text = getString(R.string.check_share)
            isEnabled = false
            setOnClickListener { share() }
        }
        column.addView(shareButton)

        log = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextIsSelectable(true)
            setPadding(0, dp(18), 0, 0)
        }
        column.addView(log)

        scroller = ScrollView(this).apply {
            addView(column, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        setContentView(scroller, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }

    private fun runCheck() {
        runButton.isEnabled = false
        runButton.text = getString(R.string.check_running)
        shareButton.isEnabled = false
        log.text = ""

        worker.execute {
            DownloadCheck(this).run { line -> ui.post { append(line) } }
            ui.post {
                runButton.isEnabled = true
                runButton.text = getString(R.string.check_run)
                shareButton.isEnabled = true
            }
        }
    }

    private fun share() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.check_title))
            putExtra(Intent.EXTRA_TEXT, log.text.toString())
        }
        startActivity(Intent.createChooser(send, getString(R.string.check_share)))
    }

    private fun append(line: String) {
        log.append(if (log.length() == 0) line else "\n$line")
        scroller.post { scroller.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
