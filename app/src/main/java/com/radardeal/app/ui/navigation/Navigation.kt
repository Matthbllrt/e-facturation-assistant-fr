package com.radardeal.app.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.radardeal.app.ui.detail.ListingDetailScreen
import com.radardeal.app.ui.favorites.FavoritesScreen
import com.radardeal.app.ui.login.VintedLoginScreen
import com.radardeal.app.ui.onboarding.OnboardingScreen
import com.radardeal.app.ui.radar.RadarScreen
import com.radardeal.app.ui.settings.SettingsScreen
import com.radardeal.app.ui.theme.RadarColors
import com.radardeal.app.ui.watches.WatchEditorScreen
import com.radardeal.app.ui.watches.WatchesScreen

/** A listing to open as soon as the app is ready, coming from a notification tap. */
data class PendingListing(val watchId: Long, val itemId: String)

@Composable
fun RadarDealNavigation(
    startWithOnboarding: Boolean,
    pendingListing: PendingListing?,
    onPendingListingHandled: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val currentTab = BottomTab.fromRoute(currentRoute)

    // A notification tap can arrive before or after the UI exists; handle it either way.
    LaunchedEffect(pendingListing) {
        val target = pendingListing ?: return@LaunchedEffect
        navController.navigate(Routes.listingDetail(target.watchId, target.itemId)) {
            launchSingleTop = true
        }
        onPendingListingHandled()
    }

    Scaffold(
        containerColor = RadarColors.Background,
        contentColor = RadarColors.TextPrimary,
        bottomBar = {
            if (currentTab != null) {
                RadarBottomBar(current = currentTab) { tab ->
                    navController.navigate(tab.route) {
                        popUpTo(Routes.RADAR) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(RadarColors.Background)
                .padding(bottom = if (currentTab != null) padding.calculateBottomPadding() else 0.dp),
        ) {
            NavHost(
                navController = navController,
                startDestination = if (startWithOnboarding) Routes.ONBOARDING else Routes.RADAR,
                enterTransition = { fadeIn(tween(180)) },
                exitTransition = { fadeOut(tween(140)) },
                popEnterTransition = { fadeIn(tween(180)) },
                popExitTransition = { fadeOut(tween(140)) },
            ) {
                composable(Routes.ONBOARDING) {
                    OnboardingScreen(
                        onFinished = {
                            navController.navigate(Routes.RADAR) {
                                popUpTo(Routes.ONBOARDING) { inclusive = true }
                            }
                        },
                    )
                }

                composable(Routes.RADAR) {
                    RadarScreen(
                        onOpenListing = { watchId, itemId ->
                            navController.navigate(Routes.listingDetail(watchId, itemId))
                        },
                        onCreateWatch = { navController.navigate(Routes.watchEditor()) },
                        onOpenLogin = { navController.navigate(Routes.VINTED_LOGIN) },
                        onOpenWatches = {
                            navController.navigate(Routes.WATCHES) { launchSingleTop = true }
                        },
                    )
                }

                composable(Routes.WATCHES) {
                    WatchesScreen(
                        onCreateWatch = { navController.navigate(Routes.watchEditor()) },
                        onEditWatch = { id -> navController.navigate(Routes.watchEditor(id)) },
                        onOpenLogin = { navController.navigate(Routes.VINTED_LOGIN) },
                    )
                }

                composable(Routes.FAVORITES) {
                    FavoritesScreen(
                        onOpenListing = { watchId, itemId ->
                            navController.navigate(Routes.listingDetail(watchId, itemId))
                        },
                    )
                }

                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        onOpenLogin = { navController.navigate(Routes.VINTED_LOGIN) },
                    )
                }

                composable(
                    route = "${Routes.WATCH_EDITOR}?${Routes.ARG_WATCH_ID}={${Routes.ARG_WATCH_ID}}",
                    arguments = listOf(
                        navArgument(Routes.ARG_WATCH_ID) {
                            type = NavType.LongType
                            defaultValue = -1L
                        },
                    ),
                    enterTransition = { slideInVertically(tween(240)) { it / 6 } + fadeIn(tween(240)) },
                    popExitTransition = { slideOutVertically(tween(200)) { it / 6 } + fadeOut(tween(200)) },
                ) { entry ->
                    val watchId = entry.arguments?.getLong(Routes.ARG_WATCH_ID) ?: -1L
                    WatchEditorScreen(
                        watchId = watchId.takeIf { it > 0L },
                        onDone = { navController.popBackStack() },
                    )
                }

                composable(
                    route = "${Routes.LISTING_DETAIL}/{${Routes.ARG_WATCH_ID}}/{${Routes.ARG_ITEM_ID}}",
                    arguments = listOf(
                        navArgument(Routes.ARG_WATCH_ID) { type = NavType.LongType },
                        navArgument(Routes.ARG_ITEM_ID) { type = NavType.StringType },
                    ),
                ) { entry ->
                    val watchId = entry.arguments?.getLong(Routes.ARG_WATCH_ID) ?: 0L
                    val itemId = entry.arguments?.getString(Routes.ARG_ITEM_ID).orEmpty()
                    ListingDetailScreen(
                        watchId = watchId,
                        itemId = itemId,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.VINTED_LOGIN) {
                    VintedLoginScreen(onDone = { navController.popBackStack() })
                }
            }
        }
    }
}

@Composable
private fun RadarBottomBar(current: BottomTab, onSelect: (BottomTab) -> Unit) {
    NavigationBar(
        containerColor = RadarColors.Surface,
        contentColor = RadarColors.TextSecondary,
        tonalElevation = 0.dp,
    ) {
        BottomTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == current,
                onClick = { if (tab != current) onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = RadarColors.Accent,
                    selectedTextColor = RadarColors.Accent,
                    unselectedIconColor = RadarColors.TextTertiary,
                    unselectedTextColor = RadarColors.TextTertiary,
                    indicatorColor = Color.Transparent,
                ),
            )
        }
    }
}
