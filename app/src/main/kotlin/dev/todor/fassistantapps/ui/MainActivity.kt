package dev.todor.fassistantapps.ui

import android.app.Activity
import android.content.Intent
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
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var status: TextView
    private lateinit var refreshButton: Button

    private var shown: List<CatalogueEntry> = emptyList()

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
            row(shown[position])
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
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // What is installed can have changed while this screen was away, and rereading it costs
        // nothing — unlike a refresh, which spends one of sixty requests an hour.
        if (shown.isNotEmpty()) adapter.notifyDataSetChanged()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }

    private fun refresh() {
        refreshButton.isEnabled = false
        status.text = getString(R.string.catalogue_loading)

        worker.execute {
            val result = Catalogue.load(this)
            ui.post {
                shown = result.entries
                adapter.notifyDataSetChanged()
                status.text = describe(result)
                refreshButton.isEnabled = true
            }
        }
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

    private fun row(entry: CatalogueEntry): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }

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

        return column
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
