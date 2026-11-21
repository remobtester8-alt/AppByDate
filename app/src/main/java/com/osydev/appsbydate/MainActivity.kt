package com.osydev.appsbydate

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val installTime: Long,
    val updateTime: Long,
    val isSystem: Boolean,
)

class MainActivity : Activity() {

    private lateinit var listView: ListView
    private lateinit var progress: ProgressBar
    private lateinit var emptyView: TextView
    private val adapter = AppsAdapter()

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }
    private var oldestFirst: Boolean
        get() = prefs.getBoolean("oldest_first", true)
        set(v) = prefs.edit().putBoolean("oldest_first", v).apply()
    private var showSystem: Boolean
        get() = prefs.getBoolean("show_system", false)
        set(v) = prefs.edit().putBoolean("show_system", v).apply()

    private var allApps: List<AppEntry> = emptyList()
    private var loadToken = 0

    private val dateFormat = SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.ENGLISH)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        listView = findViewById(R.id.list)
        progress = findViewById(R.id.progress)
        emptyView = findViewById(R.id.empty)
        listView.adapter = adapter
    }

    // Reload every time we come back (e.g. after the uninstall dialog closes).
    override fun onResume() {
        super.onResume()
        loadApps()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    // ---------- Loading ----------

    private fun loadApps() {
        val token = ++loadToken
        if (allApps.isEmpty()) progress.visibility = View.VISIBLE
        executor.execute {
            val result = queryInstalledApps()
            mainHandler.post {
                if (token != loadToken || isFinishing) return@post
                allApps = result
                progress.visibility = View.GONE
                applyFilterAndSort()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun queryInstalledApps(): List<AppEntry> {
        val pm = packageManager
        val packages: List<PackageInfo> =
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            } else {
                pm.getInstalledPackages(0)
            }

        return packages.mapNotNull { pi ->
            val ai = pi.applicationInfo ?: return@mapNotNull null
            if (pi.packageName == packageName) return@mapNotNull null // hide this app itself
            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            AppEntry(
                packageName = pi.packageName,
                label = ai.loadLabel(pm).toString(),
                icon = runCatching { ai.loadIcon(pm) }.getOrNull(),
                installTime = pi.firstInstallTime,
                updateTime = pi.lastUpdateTime,
                isSystem = isSystem,
            )
        }
    }

    private fun applyFilterAndSort() {
        var list = if (showSystem) allApps else allApps.filter { !it.isSystem }
        list = list.sortedWith(compareBy<AppEntry> { it.installTime }.thenBy { it.label.lowercase() })
        if (!oldestFirst) list = list.reversed()
        adapter.items = list
        adapter.notifyDataSetChanged()
        emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        title = getString(R.string.title_count, list.size)
    }

    // ---------- Uninstall ----------

    private fun uninstall(app: AppEntry) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.cant_delete, Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, R.string.cant_delete, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Menu ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_SORT, 0, sortLabel()).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, MENU_SYSTEM, 1, R.string.show_system).apply {
            isCheckable = true
            isChecked = showSystem
        }
        menu.add(0, MENU_REFRESH, 2, R.string.refresh)
        return true
    }

    private fun sortLabel(): String =
        getString(if (oldestFirst) R.string.sort_oldest else R.string.sort_newest)

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_SORT -> {
                oldestFirst = !oldestFirst
                item.title = sortLabel()
                applyFilterAndSort()
                listView.setSelection(0)
            }
            MENU_SYSTEM -> {
                showSystem = !showSystem
                item.isChecked = showSystem
                applyFilterAndSort()
            }
            MENU_REFRESH -> loadApps()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ---------- Adapter ----------

    private class Holder(v: View) {
        val index: TextView = v.findViewById(R.id.index)
        val icon: ImageView = v.findViewById(R.id.icon)
        val name: TextView = v.findViewById(R.id.name)
        val date: TextView = v.findViewById(R.id.date)
        val updated: TextView = v.findViewById(R.id.updated)
        val delete: Button = v.findViewById(R.id.delete)
    }

    private inner class AppsAdapter : BaseAdapter() {
        var items: List<AppEntry> = emptyList()

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
                    .also { it.tag = Holder(it) }
            val h = view.tag as Holder
            val app = items[position]

            h.index.text = (position + 1).toString()
            h.icon.setImageDrawable(app.icon)
            h.name.text = if (app.isSystem) "${app.label}  (${getString(R.string.system_app)})" else app.label
            h.date.text = getString(R.string.installed_on, dateFormat.format(Date(app.installTime)))
            if (app.updateTime > app.installTime + 60_000) {
                h.updated.visibility = View.VISIBLE
                h.updated.text = getString(R.string.updated_on, dateFormat.format(Date(app.updateTime)))
            } else {
                h.updated.visibility = View.GONE
            }
            h.delete.setOnClickListener { uninstall(app) }
            return view
        }
    }

    companion object {
        private const val MENU_SORT = 1
        private const val MENU_SYSTEM = 2
        private const val MENU_REFRESH = 3
    }
}
