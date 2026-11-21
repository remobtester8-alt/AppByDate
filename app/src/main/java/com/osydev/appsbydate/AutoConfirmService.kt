package com.osydev.appsbydate

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Shared state between the screen and the accessibility service.
 * The service ONLY clicks anything while [active] is true, i.e. while the user
 * has started a "delete selected" batch from this app.
 */
object AutoUninstall {
    @Volatile var active = false
    @Volatile var lastInstallerPackage: String? = null
    @Volatile var clicks = 0

    fun isServiceEnabled(context: Context): Boolean {
        val me = ComponentName(context, AutoConfirmService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }
}

/**
 * Presses the confirm button of the system "uninstall app?" dialog automatically,
 * so a batch of selected apps can be removed without tapping OK for each one.
 */
class AutoConfirmService : AccessibilityService() {

    private var lastClickAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!AutoUninstall.active || event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (!looksLikeInstaller(pkg)) return
        AutoUninstall.lastInstallerPackage = pkg

        // Don't double-click the same dialog.
        val now = SystemClock.uptimeMillis()
        if (now - lastClickAt < 700) return

        val root = rootInActiveWindow ?: return
        val button = findConfirmButton(root) ?: return
        if (clickNodeOrParent(button)) {
            lastClickAt = now
            AutoUninstall.clicks++
        }
    }

    override fun onInterrupt() {}

    private fun looksLikeInstaller(pkg: String): Boolean {
        val p = pkg.lowercase()
        return "packageinstaller" in p || p.endsWith(".installer") || "permissioncontroller" in p
    }

    private fun findConfirmButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 1) Standard AlertDialog positive button (AOSP / Google / many OEMs).
        for (id in POSITIVE_IDS) {
            root.findAccessibilityNodeInfosByViewId(id)
                ?.firstOrNull { it.isEnabled && it.isVisibleToUser }
                ?.let { return it }
        }
        // 2) Match by button text (exact match, so "Cancel"/"إلغاء" never matches).
        for (text in CONFIRM_TEXTS) {
            root.findAccessibilityNodeInfosByText(text)
                ?.firstOrNull { node ->
                    val t = node.text?.toString()?.trim() ?: return@firstOrNull false
                    t.equals(text, ignoreCase = true) && node.isEnabled && node.isVisibleToUser
                }
                ?.let { return it }
        }
        return null
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 5) {
            if (n.isClickable) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent
            depth++
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    companion object {
        private val POSITIVE_IDS = listOf(
            "android:id/button1",
            "com.android.packageinstaller:id/ok_button",
            "com.google.android.packageinstaller:id/ok_button",
            "com.miui.packageinstaller:id/ok_button",
            "com.miui.packageinstaller:id/confirm_button",
            "com.miui.packageinstaller:id/uninstall_button",
            "miuix:id/button1",
        )
        private val CONFIRM_TEXTS = listOf(
            "OK", "Ok", "Uninstall", "UNINSTALL", "Delete", "Remove", "Confirm",
            "موافق", "حسناً", "حسنا", "إلغاء التثبيت", "الغاء التثبيت", "إزالة التثبيت", "حذف", "إزالة", "تأكيد", "نعم",
        )
    }
}
