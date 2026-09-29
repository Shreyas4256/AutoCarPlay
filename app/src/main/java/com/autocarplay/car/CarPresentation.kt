package com.autocarplay.car

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import com.autocarplay.R

/**
 * Hosts [CarContent] on a private virtual display whose output is the Android Auto map
 * surface, so normal Android views can be drawn on the car screen.
 */
class CarPresentation(
    context: Context,
    display: Display,
    private val callbacks: CarContent.Callbacks,
) : Presentation(context, display, R.style.Theme_AutoCarPlay_Car) {

    var content: CarContent? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setCancelable(false)
        setContentView(R.layout.car_presentation)
        content = CarContent(findViewById(R.id.car_root), callbacks, directTouch = false)
    }
}
