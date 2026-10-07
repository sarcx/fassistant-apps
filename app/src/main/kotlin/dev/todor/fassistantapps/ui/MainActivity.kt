package dev.todor.fassistantapps.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import dev.todor.fassistantapps.R
import dev.todor.fassistantapps.catalogue.Catalogue
import dev.todor.fassistantapps.catalogue.CatalogueEntry
import dev.todor.fassistantapps.catalogue.CatalogueResult
import dev.todor.fassistantapps.catalogue.Standing
import dev.todor.fassistantapps.install.Installer
import dev.todor.fassistantapps.install.Refused
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var status: TextView
    private lateinit var selfUpdate: LinearLayout
    private lateinit var refreshButton: Button

    private var shown: List<CatalogueEntry> = emptyList()

    // What each install has got to, by repository. A line stays after a failure so the row can say
    // why, and goes once the app it was installing turns up on the phone.
    private val progress = mutableMapOf<String, String>()
    private val installing = mutableSetOf<String>()

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
            row(shown[position])
    }

    // An install finishes after Android's confirmation has closed, so resuming is too early to see
    // it. Android announces the package itself, and that is the moment to reread.
    private val packageChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val changed = intent.data?.schemeSpecificPart
            shown.filter { it.manifest?.packageName == changed }.forEach { progress.remove(it.repo) }
            rereadInstalled()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        status = TextView(this).apply {
            text = getString(R.string.catalogue_loading)
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        column.addView(status)

        selfUpdate = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), dp(12))
            visibility = View.GONE
        }
        column.addView(selfUpdate)

        val list = ListView(this).apply { adapter = this@MainActivity.adapter }
        column.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        refreshButton = Button(this).apply {
            text = getString(R.string.catalogue_refresh)
            setOnClickListener { refresh() }
        }
        buttons.addView(refreshButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        buttons.addView(Button(this).apply {
            text = getString(R.string.check_title)
            setOnClickListener { startActivity(Intent(this@MainActivity, CheckActivity::class.java)) }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        column.addView(buttons)

        setContentView(column, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        registerReceiver(packageChanges, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        })
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // What is installed can have changed while this screen was away, and rereading it costs
        // nothing — unlike a refresh, which spends one of sixty requests an hour.
        if (shown.isNotEmpty()) rereadInstalled()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(packageChanges)
        worker.shutdownNow()
    }

    private fun refresh() {
        refreshButton.isEnabled = false
        status.text = getString(R.string.catalogue_loading)

        worker.execute {
            val result = Catalogue.load(this)
            ui.post {
                shown = result.entries
                status.text = describe(result)
                refreshButton.isEnabled = true
                redraw()
            }
        }
    }

    private fun rereadInstalled() {
        shown = shown.map { Catalogue.rereadInstalled(this, it) }
        redraw()
    }

    private fun redraw() {
        adapter.notifyDataSetChanged()
        renderSelfUpdate()
    }

    private fun describe(result: CatalogueResult): String {
        if (result.entries.isEmpty()) {
            return if (result.rateLimited) getString(R.string.catalogue_rate_limited_empty)
            else getString(R.string.catalogue_empty)
        }

        val count = resources.getQuantityString(R.plurals.catalogue_count, result.entries.size, result.entries.size)
        if (!result.fromCache) return getString(R.string.catalogue_live, count)

        val age = DateUtils.getRelativeTimeSpanString(result.fetchedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        return if (result.rateLimited) getString(R.string.catalogue_rate_limited, count, age)
        else getString(R.string.catalogue_cached, count, age, result.problem.orEmpty())
    }

    /**
     * This app is in its own list, because its repository carries the family topic like any other.
     * Its row can update it too; the banner exists so the update is not buried in the list.
     */
    private fun renderSelfUpdate() {
        selfUpdate.removeAllViews()
        val entry = shown.firstOrNull { it.manifest?.packageName == packageName }?.takeIf { it.standing == Standing.UPDATE }
        selfUpdate.visibility = if (entry == null) View.GONE else View.VISIBLE
        if (entry == null) return

        selfUpdate.addView(TextView(this).apply {
            text = progress[entry.repo]
                ?: getString(R.string.self_update_available, entry.manifest!!.versionName, entry.installed!!.versionName)
            setTypeface(null, Typeface.BOLD)
        })
        if (entry.repo in installing) return

        selfUpdate.addView(Button(this).apply {
            text = getString(R.string.self_update_button)
            setOnClickListener { install(entry) }
        })
    }

    /**
     * Installs run one after another on the worker, so tapping several rows queues them and Android
     * asks about each in turn.
     */
    private fun install(entry: CatalogueEntry) {
        installing += entry.repo
        progress[entry.repo] = getString(R.string.install_downloading, entry.manifest!!.versionName)
        redraw()

        worker.execute {
            val outcome = runCatching { Installer.handOver(this, Installer.fetch(this, entry)) }
            ui.post {
                installing -= entry.repo
                progress[entry.repo] = when (val problem = outcome.exceptionOrNull()) {
                    null -> getString(R.string.install_handed_over)
                    is Refused -> problem.message.orEmpty()
                    else -> getString(R.string.install_failed, problem.message ?: problem.javaClass.simpleName)
                }
                redraw()
            }
        }
    }

    private fun row(entry: CatalogueEntry): View {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(12), dp(12))
        }

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        column.addView(TextView(this).apply {
            text = entry.label
            setTypeface(null, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })

        column.addView(TextView(this).apply {
            text = detail(entry)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.START
        })

        progress[entry.repo]?.let { said ->
            column.addView(TextView(this).apply {
                text = said
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(null, Typeface.ITALIC)
            })
        }

        line.addView(column, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        action(entry)?.let { line.addView(it) }
        return line
    }

    /** The one thing a row offers, or nothing when there is nothing it can do. */
    private fun action(entry: CatalogueEntry): Button? {
        val (label, onTap) = when (entry.standing) {
            // Unidentified still gets Install: it cannot say whether the app is on the phone, but
            // installing over a copy that is already there is harmless.
            Standing.INSTALL, Standing.UNIDENTIFIED -> R.string.row_install to { install(entry) }
            Standing.UPDATE -> R.string.row_update to { install(entry) }
            Standing.CURRENT -> {
                val launch = entry.manifest!!.packageName!!
                    .takeIf { it != packageName }
                    ?.let { packageManager.getLaunchIntentForPackage(it) }
                    ?: return null
                R.string.row_open to { startActivity(launch) }
            }
            Standing.BROKEN -> return null
        }
        return Button(this).apply {
            text = getString(label)
            // A focusable button inside a list row swallows the row's focus handling.
            isFocusable = false
            isEnabled = entry.repo !in installing
            setOnClickListener { onTap() }
        }
    }

    private fun detail(entry: CatalogueEntry): String {
        val manifest = entry.manifest
        return when (entry.standing) {
            Standing.BROKEN -> getString(R.string.standing_broken, entry.repo, entry.problem.orEmpty())
            Standing.UNIDENTIFIED -> getString(R.string.standing_unidentified, manifest!!.versionName)
            Standing.INSTALL -> getString(R.string.standing_install, manifest!!.versionName)
            Standing.UPDATE -> getString(R.string.standing_update, entry.installed!!.versionName, manifest!!.versionName)
            Standing.CURRENT -> getString(R.string.standing_current, manifest!!.versionName)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
