package dev.monet.app.feature.lens

import android.app.Service
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.monet.app.MonetApp
import dev.monet.app.core.di.AppContainer
import dev.monet.app.core.settings.Settings
import dev.monet.app.core.settings.ThemeMode
import dev.monet.app.feature.lens.capture.realDisplaySize
import dev.monet.app.ui.theme.MonetTheme
import kotlinx.coroutines.CoroutineScope

/** What the Lens's parts share: the service's overlay windows, its coroutine scope and the app's services. */
internal class LensHost(
    val service: Service,
    val scope: CoroutineScope,
    private val owner: ServiceLifecycleOwner,
) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    val container: AppContainer get() = (service.application as MonetApp).container

    fun px(dp: Dp): Int = (dp.value * service.resources.displayMetrics.density).toInt()

    fun now(): Long = SystemClock.uptimeMillis()

    /** Display size in pixels with the system bars included, in the current rotation. */
    fun displaySize(): Pair<Int, Int> = realDisplaySize(service)

    fun statusBarPx(): Int = systemDimenPx("status_bar_height")

    fun navigationBarPx(): Int = systemDimenPx("navigation_bar_height")

    private fun systemDimenPx(name: String): Int {
        val id = service.resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) service.resources.getDimensionPixelSize(id) else 0
    }

    fun toast(message: String) = Toast.makeText(service, message, Toast.LENGTH_LONG).show()

    /**
     * Layout params for an overlay window placed in display pixels (system bars included), so its
     * coordinates match the pixels of a captured frame.
     */
    fun params(
        width: Int,
        height: Int,
        x: Int = 0,
        y: Int = 0,
        touchable: Boolean = true,
        focusable: Boolean = false,
    ): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    /**
     * A ComposeView that can live in a window owned by the service. With [followAppTheme] it uses
     * the theme chosen in Settings; without it, the light theme (for pieces drawn in page colors).
     */
    fun composeView(followAppTheme: Boolean = true, content: @Composable () -> Unit): ComposeView =
        ComposeView(service).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setContent {
                if (followAppTheme) {
                    val settings by container.settingsRepository.settings.collectAsState(initial = Settings())
                    val dark = when (settings.themeMode) {
                        ThemeMode.SYSTEM -> isSystemInDarkTheme()
                        ThemeMode.LIGHT -> false
                        ThemeMode.DARK -> true
                    }
                    MonetTheme(darkTheme = dark, content = content)
                } else {
                    MonetTheme(darkTheme = false, content = content)
                }
            }
        }

    /** Compose looks for these owners on the root view of its window; call this for a root that wraps a ComposeView. */
    fun attachOwners(root: View) {
        root.setViewTreeLifecycleOwner(owner)
        root.setViewTreeSavedStateRegistryOwner(owner)
        root.setViewTreeViewModelStoreOwner(owner)
    }

    fun add(view: View, params: WindowManager.LayoutParams) = windowManager.addView(view, params)

    fun update(view: View, params: WindowManager.LayoutParams) {
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    fun remove(view: View?) {
        view?.let { runCatching { windowManager.removeView(it) } }
    }
}
