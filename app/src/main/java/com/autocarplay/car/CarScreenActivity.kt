package com.autocarplay.car

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.autocarplay.R
import com.autocarplay.core.CarHub

/**
 * Full-screen activity that Android Auto opens on the car display while parked (declared with
 * the CAR_LAUNCHER category). Unlike Car App Library apps, these "parked apps" are listed by
 * Android Auto when sideloaded, as long as "Unknown sources" is on in its developer settings.
 * Touches arrive as normal touch events.
 */
class CarScreenActivity : ComponentActivity() {

    private lateinit var controller: CarController
    private var content: CarContent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.car_presentation)

        val root = findViewById<View>(R.id.car_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        controller = CarController(this)
        val carContent = CarContent(root, controller, directTouch = true)
        content = carContent
        controller.attach(carContent)
        CarHub.attach(controller)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!controller.handleBack()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onDestroy() {
        content?.let {
            controller.detach(it)
            it.release()
        }
        content = null
        controller.release()
        super.onDestroy()
    }
}
