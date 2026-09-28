package com.autocarplay.mirror

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.autocarplay.R

/** Invisible activity that shows Android's "Start recording or casting?" prompt. */
class MirrorPermissionActivity : ComponentActivity() {

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            MirrorService.start(this, result.resultCode, data)
            Toast.makeText(this, R.string.mirror_started_hint, Toast.LENGTH_LONG).show()
        } else {
            MirrorManager.consentPending = false
            Toast.makeText(this, R.string.mirror_denied, Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (MirrorManager.isActive) {
            finish()
            return
        }
        if (savedInstanceState == null) {
            val manager = getSystemService(MediaProjectionManager::class.java)
            if (manager == null) {
                finish()
                return
            }
            consent.launch(manager.createScreenCaptureIntent())
        }
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, MirrorPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Opens the consent prompt on the phone. Works from the car session too. */
        fun launch(context: Context): Boolean {
            MirrorManager.consentPending = true
            return try {
                context.startActivity(intent(context), ActivityOptions.makeBasic().toBundle())
                true
            } catch (e: Exception) {
                MirrorManager.consentPending = false
                false
            }
        }
    }
}
