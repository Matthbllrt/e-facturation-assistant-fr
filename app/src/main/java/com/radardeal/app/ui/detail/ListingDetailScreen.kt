package com.radardeal.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radardeal.app.core.Formatters
import com.radardeal.app.domain.model.DealRating
import com.radardeal.app.ui.components.BadgePill
import com.radardeal.app.ui.components.ListingImage
import com.radardeal.app.ui.components.PrimaryButton
import com.radardeal.app.ui.components.RdCard
import com.radardeal.app.ui.components.SectionHeader
import com.radardeal.app.ui.radar.openExternally
import com.radardeal.app.ui.radarViewModel
import com.radardeal.app.ui.theme.LocalSpacing
import com.radardeal.app.ui.theme.RadarColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PhotoShape = RoundedCornerShape(24.dp)

/** Full view of one listing: photo, price, deal context and the recorded price history. */
@Composable
fun ListingDetailScreen(
    watchId: Long,
    itemId: String,
    onBack: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val context = LocalContext.current
    val viewModel = radarViewModel(key = "detail-$watchId-$itemId") { graph ->
        ListingDetailViewModel(
            watchId = watchId,
            itemId = itemId,
            listingRepository = graph.listingRepository,
            watchRepository = graph.watchRepository,
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listing = state.listing

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RadarColors.Background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = spacing.sm, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Rounded.ArrowBack,
                    contentDescription = "Retour",
                    tint = RadarColors.TextPrimary,
                )
            }
            Spacer(Modifier.weight(1f))
            if (listing != null) {
                IconButton(onClick = viewModel::toggleFavorite) {
                    Icon(
                        imageVector = if (listing.isFavorite) {
                            Icons.Rounded.Favorite
                        } else {
                            Icons.Rounded.FavoriteBorder
                        },
                        contentDescription = "Favori",
                        tint = if (listing.isFavorite) RadarColors.Danger else RadarColors.TextPrimary,
                    )
                }
            }
        }

        if (listing == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (state.isLoading) "Chargement…" else "Cette annonce n'est plus enregistrée.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = RadarColors.TextSecondary,
                )
            }
            return@Column
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screen),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(PhotoShape)
                    .background(RadarColors.SurfaceElevated),
            ) {
                ListingImage(url = listing.imageUrl, modifier = Modifier.fillMaxSize())
            }

            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                if (listing.isNew) BadgePill(text = "NOUVEAU")
                if (listing.hasPriceDrop) {
                    BadgePill(
                        text = "PRIX ↓",
                        contentColor = RadarColors.PriceDrop,
                        background = RadarColors.PriceDropSoft,
                    )
                }
                if (state.rating != DealRating.NONE) {
                    BadgePill(text = state.rating.label)
                }
            }

            Text(
                text = listing.displayTitle,
                style = MaterialTheme.typography.headlineLarge,
                color = RadarColors.TextPrimary,
            )

            listing.subtitle().takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = RadarColors.TextSecondary,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                Text(
                    text = Formatters.price(listing.price, listing.currency),
                    style = MaterialTheme.typography.displayMedium,
                    color = RadarColors.TextPrimary,
                )
                listing.previousPrice?.takeIf { listing.hasPriceDrop }?.let { previous ->
                    Column {
                        Text(
                            text = Formatters.price(previous, listing.currency),
                            style = MaterialTheme.typography.bodyMedium,
                            color = RadarColors.TextTertiary,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough,
                        )
                        listing.priceDropRatio?.let { ratio ->
                            Text(
                                text = "-${(ratio * 100).toInt()} %",
                                style = MaterialTheme.typography.titleMedium,
                                color = RadarColors.PriceDrop,
                            )
                        }
                    }
                }
            }

            state.discountLabel?.let { label ->
                RdCard(
                    modifier = Modifier.fillMaxWidth(),
                    color = RadarColors.AccentSoft,
                    border = null,
                ) {
                    Column(
                        modifier = Modifier.padding(spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(spacing.xs),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.titleLarge,
                            color = RadarColors.Accent,
                        )
                        Text(
                            text = "Comparé aux ${state.comparableCount} annonces détectées par " +
                                "la veille « ${state.watchName} ». Ce n'est pas un prix de marché : " +
                                "c'est la médiane de ce que RadarDeal a vu passer.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = RadarColors.TextSecondary,
                        )
                    }
                }
            }

            SectionHeader(title = "Historique")

            RdCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    DetailRow("Veille", state.watchName)
                    DetailRow("Première détection", absoluteTime(listing.firstSeenAt))
                    DetailRow("Vue pour la dernière fois", Formatters.relativeTime(listing.lastSeenAt))

                    if (state.priceHistory.size > 1) {
                        Spacer(Modifier.height(spacing.xs))
                        Text(
                            text = "Prix relevés",
                            style = MaterialTheme.typography.titleMedium,
                            color = RadarColors.TextPrimary,
                        )
                        state.priceHistory.asReversed().take(8).forEach { point ->
                            DetailRow(
                                label = absoluteTime(point.recordedAt),
                                value = Formatters.price(point.price, listing.currency),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(spacing.sm))
        }

        Column(
            modifier = Modifier
                .padding(horizontal = spacing.screen)
                .padding(top = spacing.md, bottom = spacing.lg)
                .navigationBarsPadding(),
        ) {
            PrimaryButton(
                text = "Voir sur Vinted",
                onClick = { openExternally(context, listing.itemUrl) },
                icon = Icons.Rounded.OpenInNew,
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = RadarColors.TextSecondary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = RadarColors.TextPrimary,
        )
    }
}

private fun absoluteTime(timestamp: Long): String = runCatching {
    SimpleDateFormat("d MMM yyyy · HH:mm", Locale.FRANCE).format(Date(timestamp))
}.getOrDefault("—")

