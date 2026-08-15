package com.radardeal.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.ImageNotSupported
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.radardeal.app.core.Formatters
import com.radardeal.app.domain.model.DealRating
import com.radardeal.app.domain.model.ListingCard
import com.radardeal.app.ui.theme.CardShape
import com.radardeal.app.ui.theme.LocalSpacing
import com.radardeal.app.ui.theme.RadarColors

/**
 * The large, photo-first card used on the Radar feed.
 *
 * The photo carries the card; text is reduced to what a buyer decides on in one second —
 * title, one metadata line, price, and at most two badges. Everything is defensive: a listing
 * with no photo, no price and no size still renders as a clean card.
 */
@Composable
fun ListingCardLarge(
    card: ListingCard,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenOnVinted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val listing = card.listing

    RdCard(modifier = modifier.fillMaxWidth(), onClick = onOpen) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.08f)
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(RadarColors.SurfaceElevated),
            ) {
                ListingImage(
                    url = listing.imageUrl,
                    modifier = Modifier.fillMaxSize(),
                )

                // Bottom scrim keeps the badges readable over any photo.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.55f to Color.Transparent,
                                1f to Color(0xCC070B10),
                            )
                        )
                )

                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    if (listing.isNew) {
                        BadgePill(text = "NOUVEAU")
                    }
                    if (listing.hasPriceDrop) {
                        BadgePill(
                            text = "PRIX ↓",
                            contentColor = RadarColors.PriceDrop,
                            background = RadarColors.PriceDropSoft,
                        )
                    }
                }

                FavoriteButton(
                    isFavorite = listing.isFavorite,
                    onClick = onToggleFavorite,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(spacing.md),
                )

                if (card.rating != DealRating.NONE) {
                    BadgePill(
                        text = card.rating.label,
                        contentColor = if (card.rating == DealRating.EXCELLENT) {
                            RadarColors.Accent
                        } else {
                            RadarColors.TextPrimary
                        },
                        background = Color(0xE60D151D),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(spacing.md),
                    )
                }
            }

            Column(
                modifier = Modifier.padding(spacing.lg),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(
                    text = listing.displayTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = RadarColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                listing.subtitle().takeIf { it.isNotBlank() }?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = RadarColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    modifier = Modifier.padding(top = spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Text(
                        text = Formatters.price(listing.price, listing.currency),
                        style = MaterialTheme.typography.headlineMedium,
                        color = RadarColors.TextPrimary,
                    )
                    listing.previousPrice?.takeIf { listing.hasPriceDrop }?.let { previous ->
                        Text(
                            text = Formatters.price(previous, listing.currency),
                            style = MaterialTheme.typography.bodyMedium,
                            color = RadarColors.TextTertiary,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough,
                        )
                    }
                }

                card.discountLabel()?.let { label ->
                    Text(
                        text = "↘ $label",
                        style = MaterialTheme.typography.titleMedium,
                        color = RadarColors.Accent,
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = spacing.sm),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = Formatters.relativeTime(listing.firstSeenAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = RadarColors.TextTertiary,
                        )
                        Text(
                            text = card.watchName,
                            style = MaterialTheme.typography.labelSmall,
                            color = RadarColors.TextTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    SecondaryButton(
                        text = "Voir sur Vinted",
                        onClick = onOpenOnVinted,
                        icon = Icons.Rounded.OpenInNew,
                        contentColor = RadarColors.Accent,
                    )
                }
            }
        }
    }
}

/** Denser variant used on the Favoris screen. */
@Composable
fun ListingRowCompact(
    card: ListingCard,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val listing = card.listing

    RdCard(modifier = modifier.fillMaxWidth(), onClick = onOpen) {
        Row(
            modifier = Modifier.padding(spacing.md),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(RadarColors.SurfaceElevated),
            ) {
                ListingImage(url = listing.imageUrl, modifier = Modifier.fillMaxSize())
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = listing.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = RadarColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                listing.subtitle().takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = RadarColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = Formatters.price(listing.price, listing.currency),
                    style = MaterialTheme.typography.titleLarge,
                    color = RadarColors.TextPrimary,
                )
            }

            FavoriteButton(isFavorite = listing.isFavorite, onClick = onToggleFavorite)
        }
    }
}

@Composable
private fun FavoriteButton(
    isFavorite: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(38.dp)
            .background(Color(0xB3070B10), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            contentDescription = if (isFavorite) "Retirer des favoris" else "Ajouter aux favoris",
            tint = if (isFavorite) RadarColors.Danger else RadarColors.TextPrimary,
            modifier = Modifier.size(19.dp),
        )
    }
}

/**
 * Photo slot. A missing or unreachable image degrades to a neutral placeholder rather than an
 * empty hole — Vinted photos expire when a listing is removed, and that must not look broken.
 */
@Composable
fun ListingImage(url: String?, modifier: Modifier = Modifier) {
    if (url.isNullOrBlank()) {
        ImagePlaceholder(modifier)
        return
    }

    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
        loading = { Box(modifier = Modifier.fillMaxSize().background(RadarColors.SurfaceElevated)) },
        error = { ImagePlaceholder(Modifier.fillMaxSize()) },
    )
}

@Composable
private fun ImagePlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(RadarColors.SurfaceElevated),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.ImageNotSupported,
            contentDescription = null,
            tint = RadarColors.TextTertiary,
            modifier = Modifier.size(28.dp),
        )
    }
}
