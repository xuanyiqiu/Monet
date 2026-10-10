package dev.monet.app.feature.lens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

fun overlaySettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

/** Notifications are optional (the foreground service still runs), but the user should see why Monet is active. */
fun needsNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

fun startLens(context: Context) {
    ContextCompat.startForegroundService(context, Intent(context, LensService::class.java))
}

fun stopLens(context: Context) {
    context.stopService(Intent(context, LensService::class.java))
}
