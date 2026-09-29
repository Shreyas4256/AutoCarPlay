package com.autocarplay.car

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.car.app.CarContext
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat

internal fun CarContext.carIcon(@DrawableRes res: Int): CarIcon =
    CarIcon.Builder(IconCompat.createWithResource(this, res)).setTint(CarColor.DEFAULT).build()

internal fun bitmapIcon(bitmap: Bitmap): CarIcon =
    CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()

/** An action button with an icon and, optionally, a title. */
internal fun CarContext.carAction(
    @DrawableRes icon: Int,
    title: String? = null,
    onClick: () -> Unit,
): Action {
    val builder = Action.Builder()
        .setIcon(carIcon(icon))
        .setOnClickListener { onClick() }
    if (title != null) builder.setTitle(title)
    return builder.build()
}

/** How many rows the car allows in a list (at least 6 on every car). */
internal fun CarContext.listLimit(): Int = try {
    if (carAppApiLevel >= 2) {
        getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
    } else {
        6
    }
} catch (e: Exception) {
    6
}
