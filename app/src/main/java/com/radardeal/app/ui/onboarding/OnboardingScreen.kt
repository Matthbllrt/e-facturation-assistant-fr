package com.radardeal.app.ui.onboarding

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.radardeal.app.ui.components.PrimaryButton
import com.radardeal.app.ui.components.QuietButton
import com.radardeal.app.ui.radarViewModel
import com.radardeal.app.ui.theme.LocalSpacing
import com.radardeal.app.ui.theme.RadarColors
import kotlinx.coroutines.launch

private data class OnboardingPage(
    val title: String,
    val body: String,
)

private val pages = listOf(
    OnboardingPage(
        title = "Ne rate plus une bonne annonce.",
        body = "RadarDeal surveille tes recherches pour toi.",
    ),
    OnboardingPage(
        title = "Crée tes radars.",
        body = "Marque, modèle, prix ou recherche Vinted complète.",
    ),
    OnboardingPage(
        title = "Sois alerté.",
        body = "Une nouvelle annonce apparaît ?\nRadarDeal te prévient.",
    ),
)

/** Three screens, shown once, on the very first launch. */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val spacing = LocalSpacing.current
    val viewModel = radarViewModel { graph -> OnboardingViewModel(graph.settingsStore) }
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    val finish = {
        viewModel.completeOnboarding()
        onFinished()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RadarColors.Background)
            .systemBarsPadding()
            .padding(horizontal = spacing.screen),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = spacing.sm),
            horizontalArrangement = Arrangement.End,
        ) {
            if (pagerState.currentPage < pages.lastIndex) {
                QuietButton(text = "Passer", onClick = finish)
            } else {
                Spacer(Modifier.height(48.dp))
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
        ) { page ->
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RadarSweepMark(modifier = Modifier.size(180.dp))

                Spacer(Modifier.height(spacing.xxl))

                Text(
                    text = pages[page].title,
                    style = MaterialTheme.typography.displayMedium,
                    color = RadarColors.TextPrimary,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(spacing.md))

                Text(
                    text = pages[page].body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = RadarColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = spacing.xl),
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(pages.size) { index ->
                val selected = index == pagerState.currentPage
                val width by animateFloatAsState(
                    targetValue = if (selected) 26f else 8f,
                    animationSpec = tween(220),
                    label = "pageIndicator",
                )
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .height(8.dp)
                        .width(width.dp)
                        .background(
                            if (selected) RadarColors.Accent else RadarColors.Outline,
                            CircleShape,
                        )
                )
            }
        }

        PrimaryButton(
            text = if (pagerState.currentPage == pages.lastIndex) "Commencer" else "Suivant",
            onClick = {
                if (pagerState.currentPage == pages.lastIndex) {
                    finish()
                } else {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                }
            },
            modifier = Modifier.padding(bottom = spacing.xl),
        )
    }
}

/** The animated radar used as the onboarding artwork — drawn, not an asset. */
@Composable
private fun RadarSweepMark(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "radarSweep")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3600, easing = LinearEasing)),
        label = "radarSweepAngle",
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)

            listOf(1f, 0.66f, 0.33f).forEachIndexed { index, factor ->
                drawCircle(
                    color = RadarColors.Accent.copy(alpha = 0.18f + index * 0.12f),
                    radius = radius * factor,
                    center = center,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }

            drawCircle(
                color = RadarColors.Accent,
                radius = 5.dp.toPx(),
                center = center,
            )
        }

        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .fillMaxSize()
                .rotate(angle)
        ) {
            val radius = size.minDimension / 2f
            drawArc(
                brush = Brush.sweepGradient(
                    0f to Color.Transparent,
                    0.14f to RadarColors.Accent.copy(alpha = 0.42f),
                    0.16f to Color.Transparent,
                ),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = true,
                topLeft = Offset(
                    (size.width - radius * 2) / 2f,
                    (size.height - radius * 2) / 2f,
                ),
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
            )
        }
    }
}
