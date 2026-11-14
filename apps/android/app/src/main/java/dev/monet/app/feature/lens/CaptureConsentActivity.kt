package dev.monet.app.feature.lens

import android.content.Context
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Invisible activity that shows Android's screen-capture prompt on behalf of the Lens. The consent
 * dialog can only be launched from an Activity, and the bubble lives in a service; this lets the
 * user resume capture (after a lock screen, for example) without going back to the app.
 */
class CaptureConsentActivity : ComponentActivity() {
    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) startLens(this, result.resultCode, data)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            consent.launch(manager.createScreenCaptureIntent())
        }
    }
}
