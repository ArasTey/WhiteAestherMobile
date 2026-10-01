package com.whitedns.whiteaesther.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.whitedns.whiteaesther.R
import com.whitedns.whiteaesther.ui.theme.AetherTheme

/**
 * A hairline that crosses the screen while something is happening.
 *
 * Everything that takes a person a while to finish -- a registration, an
 * endpoint search, a connect -- shows text that does not change, and text that
 * does not change reads as a button that did nothing. That is what the key
 * request looked like: pressed, one sentence appeared, and for a minute or two
 * nothing at all happened.
 *
 * Not a spinner and not a percentage. There is nothing honest to count here:
 * the engine will not say how many endpoints it has left to try, and a bar
 * that advances at a made-up rate is worse than no bar, because it looks like
 * progress that is not being made. This says only that the phone is still
 * working, which is the one thing a person genuinely cannot see from here.
 *
 * Full width and two pixels, which is what makes it read as the screen's own
 * state rather than as one more control among the cards. An inset version
 * under a card looks like a divider that has lost its place.
 */
@Composable
internal fun LoadingLine(
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.loading_line_work),
) {
    val transition = rememberInfiniteTransition(label = "loading-line")
    // -0.3 to 1.0 walks the segment from just off the left of the track to just
    // off the right, so it enters and leaves the frame instead of appearing and
    // vanishing at the edges.
    val travel by transition.animateFloat(
        initialValue = -0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "loading-line-travel",
    )
    val colors = AetherTheme.colors

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(colors.line)
            .semantics { contentDescription = label },
    ) {
        val track = maxWidth
        Box(
            Modifier
                // A third of the width: reads as movement at any screen size,
                // and stops short of looking like a bar filling up.
                .width(track / 3)
                .fillMaxHeight()
                .offset(x = track * travel)
                .background(colors.brand),
        )
    }
}