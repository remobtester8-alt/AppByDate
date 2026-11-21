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
import android.widget.CheckBox
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
    private lateinit var selectionBar: View
    private lateinit var deleteSelectedBtn: Button
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

    /** Package names the user ticked. */
    private val selected = LinkedHashSet<String>()

    /** Uninstall queue for "delete selected" — one system dialog per app. */
    private val queue = ArrayDeque<String>()
    private var batchTotal = 0
    private var batchActive = false

    private val dateFormat = SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.ENGLISH)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        listView = findViewById(R.id.list)
        progress = findViewById(R.id.progress)
        emptyView = findViewById(R.id.empty)
        selectionBar = findViewById(R.id.selection_bar)
        deleteSelectedBtn = findViewById(R.id.delete_selected)
        listView.adapter = adapter

        // Tap a row to tick / untick it.
        listView.setOnItemClickListener { _, _, position, _ ->
            val pkg = adapter.items[position].packageName
            if (!selected.add(pkg)) selected.remove(pkg)
            adapter.notifyDataSetChanged()
            updateSelectionBar()
        }

        deleteSelectedBtn.setOnClickListener { startBatchUninstall() }
        findViewById<Button>(R.id.clear_selection).setOnClickListener {
            selected.clear()
            adapter.notifyDataSetChanged()
            updateSelectionBar()
        }
    }

    // Reload every time we come back (unless we're in the middle of a batch).
    override fun onResume() {
        super.onResume()
        if (!batchActive) loadApps()
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
                // Drop ticks for apps that no longer exist.
                val installed = result.mapTo(HashSet()) { it.packageName }
                selected.retainAll(installed)
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
        // Hidden apps (e.g. system apps after toggling off) shouldn't stay ticked.
        val visible = list.mapTo(HashSet()) { it.packageName }
        selected.retainAll(visible)
        adapter.items = list
        adapter.notifyDataSetChanged()
        emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        title = getString(R.string.title_count, list.size)
        updateSelectionBar()
    }

    private fun updateSelectionBar() {
        if (selected.isEmpty()) {
            selectionBar.visibility = View.GONE
        } else {
            selectionBar.visibility = View.VISIBLE
            deleteSelectedBtn.text = getString(R.string.delete_selected, selected.size)
        }
    }

    // ---------- Uninstall ----------

    @Suppress("DEPRECATION")
    private fun uninstallIntent(pkg: String): Intent =
        Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:$pkg"))
            .putExtra(Intent.EXTRA_RETURN_RESULT, true)

    /** Single app (the red button on a row). */
    private fun uninstall(app: AppEntry) {
        try {
            startActivity(uninstallIntent(app.packageName))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.cant_delete, Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, R.string.cant_delete, Toast.LENGTH_SHORT).show()
        }
    }

    /** Several apps: open the system uninstall dialog for each one, one after another. */
    private fun startBatchUninstall() {
        if (selected.isEmpty()) return
        // Keep the on-screen order.
        val order = adapter.items.map { it.packageName }.filter { it in selected }
        queue.clear()
        queue.addAll(order)
        batchTotal = order.size
        batchActive = true
        uninstallNextInQueue()
    }

    @Suppress("DEPRECATION")
    private fun uninstallNextInQueue() {
        val pkg = queue.removeFirstOrNull()
        if (pkg == null) {
            batchActive = false
            Toast.makeText(this, R.string.batch_done, Toast.LENGTH_SHORT).show()
            loadApps()
            return
        }
        val done = batchTotal - queue.size
        title = getString(R.string.deleting_progress, done, batchTotal)
        try {
            startActivityForResult(uninstallIntent(pkg), REQ_UNINSTALL)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.cant_delete, Toast.LENGTH_SHORT).show()
            uninstallNextInQueue()
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_UNINSTALL && batchActive) {
            // Whether the user confirmed or cancelled this one, move on to the next.
            mainHandler.postDelayed({ uninstallNextInQueue() }, 300)
        }
    }

    // ---------- Menu ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_SORT, 0, sortLabel()).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, MENU_SELECT_ALL, 1, R.string.select_all)
        menu.add(0, MENU_SYSTEM, 2, R.string.show_system).apply {
            isCheckable = true
            isChecked = showSystem
        }
        menu.add(0, MENU_REFRESH, 3, R.string.refresh)
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
            MENU_SELECT_ALL -> {
                adapter.items.forEach { selected.add(it.packageName) }
                adapter.notifyDataSetChanged()
                updateSelectionBar()
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
        val check: CheckBox = v.findViewById(R.id.check)
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

            h.check.isChecked = app.packageName in selected
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
        private const val REQ_UNINSTALL = 42
        private const val MENU_SORT = 1
        private const val MENU_SYSTEM = 2
        private const val MENU_REFRESH = 3
        private const val MENU_SELECT_ALL = 4
    }
}
