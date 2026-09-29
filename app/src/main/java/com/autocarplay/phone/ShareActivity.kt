package com.autocarplay.phone

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.autocarplay.R
import com.autocarplay.core.CarHub
import com.autocarplay.core.LinkStore
import com.autocarplay.core.Sources

/** Receives links shared from other apps (YouTube, Chrome, ...) and sends them to the car. */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val title = Sources.cleanSharedTitle(intent?.getStringExtra(Intent.EXTRA_SUBJECT))
        val request = Sources.requestFor(text, title)
        if (request == null) {
            Toast.makeText(this, R.string.share_no_link, Toast.LENGTH_LONG).show()
        } else {
            LinkStore(this).add(request.title, request.uri)
            val shown = CarHub.open(request)
            Toast.makeText(this, if (shown) R.string.sent_to_car else R.string.queued_for_car, Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
