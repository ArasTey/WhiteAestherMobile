package com.whitedns.whiteaesther.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.whitedns.whiteaesther.ui.theme.AetherTheme

/**
 * The one line the endpoint screen says about the key.
 *
 * It used to be two cards describing a two-step wizard, with a running commentary
 * underneath. A person arriving here is not following a sequence, they are being
 * told one thing -- usually that their own VPN needs to be on -- and everything
 * past that sentence was in the way of the button that acts on it.
 */
@Composable
internal fun KeyNotice(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(horizontal = 2.dp),
        style = AetherTheme.type.RowTitle,
        color = AetherTheme.colors.text,
    )
}