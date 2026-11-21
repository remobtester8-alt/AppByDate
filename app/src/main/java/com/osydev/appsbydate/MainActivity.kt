package com.osydev.appsbydate

import android.app.Activity
import android.app.AlertDialog
import rikka.shizuku.Shizuku
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
    private var batchAuto = false
    private var awaitingReturn = false
    private var batchPackages: List<String> = emptyList()

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

        deleteSelectedBtn.setOnClickListener { onDeleteSelectedPressed() }
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        findViewById<Button>(R.id.clear_selection).setOnClickListener {
            selected.clear()
            adapter.notifyDataSetChanged()
            updateSelectionBar()
        }
    }

    // Reload every time we come back (unless we're in the middle of a batch).
    override fun onResume() {
        super.onResume()
        if (batchActive) {
            // Came back from an uninstall dialog → open the next one.
            if (awaitingReturn) {
                awaitingReturn = false
                mainHandler.postDelayed({ uninstallNextInQueue() }, 450)
            }
            return
        }
        loadApps()
    }

    override fun onDestroy() {
        AutoUninstall.active = false
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
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

    // ---------- Bulk delete ----------

    /** "Delete selected" pressed: one confirmation, then remove everything ticked. */
    private fun onDeleteSelectedPressed() {
        if (selected.isEmpty() || bulkRunning || batchActive) return
        // 1) Accessibility auto-confirm (works on any phone, no SIM / ADB needed).
        if (AutoUninstall.isServiceEnabled(this)) {
            confirmSelection { startBatchUninstall(auto = true) }
            return
        }
        // 2) Shizuku, if it's running and allowed.
        if (shizukuState() == ShizukuState.READY) {
            confirmAndDeleteSilently()
            return
        }
        // 3) Nothing set up yet: offer to enable auto-delete, or confirm one by one.
        AlertDialog.Builder(this)
            .setTitle(R.string.auto_off_title)
            .setMessage(R.string.auto_off_msg)
            .setPositiveButton(R.string.enable_now) { _, _ -> openAccessibilitySettings() }
            .setNeutralButton(R.string.continue_anyway) { _, _ -> startBatchUninstall(auto = false) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmSelection(onConfirm: () -> Unit) {
        val apps = adapter.items.filter { it.packageName in selected }
        if (apps.isEmpty()) return
        val names = apps.joinToString("\n") { "• ${it.label}" }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.confirm_title, apps.size))
            .setMessage(names)
            .setPositiveButton(R.string.confirm_delete) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
        }
    }

    private fun showAutoHelp() {
        val status = getString(if (AutoUninstall.isServiceEnabled(this)) R.string.auto_on else R.string.auto_off)
        AlertDialog.Builder(this)
            .setTitle(R.string.auto_help)
            .setMessage(getString(R.string.auto_help_msg, status))
            .setPositiveButton(R.string.open_settings) { _, _ -> openAccessibilitySettings() }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private enum class ShizukuState { READY, NO_PERMISSION, OFF }

    private fun shizukuState(): ShizukuState = try {
        when {
            !Shizuku.pingBinder() -> ShizukuState.OFF
            Shizuku.isPreV11() -> ShizukuState.OFF
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuState.READY
            else -> ShizukuState.NO_PERMISSION
        }
    } catch (t: Throwable) {
        ShizukuState.OFF
    }

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != REQ_SHIZUKU) return@OnRequestPermissionResultListener
            mainHandler.post {
                val wanted = pendingDeleteAfterPermission
                pendingDeleteAfterPermission = false
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    if (wanted) confirmAndDeleteSilently()
                } else {
                    Toast.makeText(this, R.string.shizuku_denied, Toast.LENGTH_SHORT).show()
                }
            }
        }
    private var pendingDeleteAfterPermission = false
    private var bulkRunning = false

    private fun confirmAndDeleteSilently() {
        val apps = adapter.items.filter { it.packageName in selected }
        if (apps.isEmpty()) return
        val names = apps.joinToString("\n") { "• ${it.label}" }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.confirm_title, apps.size))
            .setMessage(names)
            .setPositiveButton(R.string.confirm_delete) { _, _ -> deleteSilently(apps) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteSilently(apps: List<AppEntry>) {
        bulkRunning = true
        progress.visibility = View.VISIBLE
        deleteSelectedBtn.isEnabled = false
        title = getString(R.string.deleting)
        executor.execute {
            var ok = 0
            var failed = 0
            for (app in apps) {
                val success = runCatching { shizukuUninstall(app.packageName) }.getOrDefault(false)
                if (success) ok++ else failed++
            }
            mainHandler.post {
                if (isFinishing) return@post
                bulkRunning = false
                deleteSelectedBtn.isEnabled = true
                Toast.makeText(this, getString(R.string.batch_result, ok, failed), Toast.LENGTH_LONG).show()
                loadApps()
            }
        }
    }

    /** Runs `pm uninstall <pkg>` with shell (ADB) rights through Shizuku. */
    private fun shizukuUninstall(pkg: String): Boolean {
        // Shizuku.newProcess is not public in API 13, so call it reflectively (common practice).
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        val process = method.invoke(null, arrayOf("pm", "uninstall", pkg), null, null) as Process
        val out = process.inputStream.bufferedReader().use { it.readText() }
        process.errorStream.bufferedReader().use { it.readText() }
        process.waitFor()
        return out.contains("Success")
    }

    private fun openShizukuInStore() {
        val id = "moe.shizuku.privileged.api"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id")))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$id")))
        }
    }

    private fun showShizukuHelp() {
        val status = getString(
            when (shizukuState()) {
                ShizukuState.READY -> R.string.status_ready
                ShizukuState.NO_PERMISSION -> R.string.status_no_permission
                ShizukuState.OFF -> R.string.status_off
            }
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.shizuku_help)
            .setMessage(getString(R.string.shizuku_help_msg, status))
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.get_shizuku) { _, _ -> openShizukuInStore() }
            .show()
    }

    /**
     * Opens the system uninstall dialog for each selected app, one after another.
     * With [auto] the accessibility service presses the confirm button by itself.
     * The next app is opened when this screen comes back to the front (onResume),
     * which works even on phones whose uninstaller doesn't return a result.
     */
    private fun startBatchUninstall(auto: Boolean) {
        if (selected.isEmpty()) return
        val order = adapter.items.map { it.packageName }.filter { it in selected }
        queue.clear()
        queue.addAll(order)
        batchPackages = order
        batchTotal = order.size
        batchActive = true
        AutoUninstall.clicks = 0
        AutoUninstall.lastInstallerPackage = null
        AutoUninstall.active = auto
        batchAuto = auto
        uninstallNextInQueue()
    }

    private fun uninstallNextInQueue() {
        if (!batchActive) return
        val pkg = queue.removeFirstOrNull()
        if (pkg == null) {
            finishBatch()
            return
        }
        if (!isInstalled(pkg)) { // already gone
            uninstallNextInQueue()
            return
        }
        val done = batchTotal - queue.size
        title = getString(R.string.deleting_progress, done, batchTotal)
        try {
            awaitingReturn = true
            startActivity(uninstallIntent(pkg))
        } catch (e: Exception) {
            awaitingReturn = false
            uninstallNextInQueue()
        }
    }

    private fun finishBatch() {
        batchActive = false
        awaitingReturn = false
        AutoUninstall.active = false
        val ok = batchPackages.count { !isInstalled(it) }
        val failed = batchPackages.size - ok
        Toast.makeText(this, getString(R.string.batch_result, ok, failed), Toast.LENGTH_LONG).show()
        if (batchAuto && ok == 0 && AutoUninstall.clicks == 0 && batchPackages.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.auto_no_click, AutoUninstall.lastInstallerPackage ?: "?"))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        loadApps()
    }

    @Suppress("DEPRECATION")
    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
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
        menu.add(0, MENU_AUTO, 4, R.string.auto_help)
        menu.add(0, MENU_SHIZUKU, 5, R.string.shizuku_help)
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
            MENU_AUTO -> showAutoHelp()
            MENU_SHIZUKU -> showShizukuHelp()
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
        private const val MENU_SORT = 1
        private const val MENU_SYSTEM = 2
        private const val MENU_REFRESH = 3
        private const val MENU_SELECT_ALL = 4
        private const val MENU_SHIZUKU = 5
        private const val MENU_AUTO = 6
        private const val REQ_SHIZUKU = 77
    }
}
