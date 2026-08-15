package com.radardeal.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.ui.graphics.vector.ImageVector

/** Every screen RadarDeal can show. Routes are plain strings — no generated code, no surprises. */
object Routes {
    const val ONBOARDING = "onboarding"
    const val RADAR = "radar"
    const val WATCHES = "watches"
    const val FAVORITES = "favorites"
    const val SETTINGS = "settings"
    const val VINTED_LOGIN = "vinted_login"

    const val WATCH_EDITOR = "watch_editor"
    const val ARG_WATCH_ID = "watchId"
    fun watchEditor(watchId: Long? = null) =
        "$WATCH_EDITOR?$ARG_WATCH_ID=${watchId ?: -1L}"

    const val LISTING_DETAIL = "listing"
    const val ARG_ITEM_ID = "itemId"
    fun listingDetail(watchId: Long, itemId: String) =
        "$LISTING_DETAIL/$watchId/${java.net.URLEncoder.encode(itemId, "UTF-8")}"
}

/** The four bottom-navigation entries. Deliberately capped at four. */
enum class BottomTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    RADAR(Routes.RADAR, "Radar", Icons.Rounded.Radar),
    WATCHES(Routes.WATCHES, "Veilles", Icons.Rounded.Visibility),
    FAVORITES(Routes.FAVORITES, "Favoris", Icons.Rounded.Favorite),
    SETTINGS(Routes.SETTINGS, "Réglages", Icons.Rounded.Settings);

    companion object {
        fun fromRoute(route: String?): BottomTab? = entries.firstOrNull { it.route == route }
    }
}
