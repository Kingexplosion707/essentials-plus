package com.sameerasw.essentials.plus

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.sameerasw.essentials.R
import com.sameerasw.essentials.data.repository.SettingsRepository
import com.sameerasw.essentials.ui.core.cards.IconToggleItem
import com.sameerasw.essentials.ui.core.containers.RoundedCardContainer
import com.sameerasw.essentials.utils.HapticUtil

/** Island settings → Lyrics (Essentials Plus): how Spicy Lyrics' item takes the island. */
@Composable
fun LyricsTakeoverSection() {
    val context = LocalContext.current
    val view = LocalView.current
    val settings = remember { SettingsRepository(context) }
    var enabled by remember { mutableStateOf(settings.getBoolean(LyricsTakeover.KEY_ENABLED, true)) }
    var onRight by remember { mutableStateOf(settings.getString(LyricsTakeover.KEY_SIDE, LyricsTakeover.SIDE_RIGHT) != LyricsTakeover.SIDE_LEFT) }
    var clock by remember { mutableStateOf(settings.getBoolean(LyricsTakeover.KEY_CLOCK, true)) }

    Text(
        "Lyrics (Essentials Plus)",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp),
    )
    RoundedCardContainer(spacing = 2.dp, cornerRadius = 24.dp) {
        IconToggleItem(
            iconRes = R.drawable.rounded_music_note_24,
            title = "Lyrics take a side of the island",
            description = "While Spicy Lyrics is showing lyrics, they get one side of the camera to themselves and the other icons step aside. Calls and notifications still come first.",
            isChecked = enabled,
            onCheckedChange = {
                HapticUtil.performVirtualKeyHaptic(view)
                enabled = it
                settings.putBoolean(LyricsTakeover.KEY_ENABLED, it)
            },
        )
        IconToggleItem(
            iconRes = R.drawable.rounded_swap_vert_24,
            title = "Lyrics on the right of the camera",
            description = if (onRight) "Lyrics on the right, the clock on the left." else "Lyrics on the left, the clock on the right.",
            isChecked = onRight,
            enabled = enabled,
            onCheckedChange = {
                HapticUtil.performVirtualKeyHaptic(view)
                onRight = it
                settings.putString(LyricsTakeover.KEY_SIDE, if (it) LyricsTakeover.SIDE_RIGHT else LyricsTakeover.SIDE_LEFT)
            },
        )
        IconToggleItem(
            iconRes = R.drawable.rounded_schedule_24,
            title = "Keep the clock",
            description = "Show the time on the other side of the camera while the lyrics are up.",
            isChecked = clock,
            enabled = enabled,
            onCheckedChange = {
                HapticUtil.performVirtualKeyHaptic(view)
                clock = it
                settings.putBoolean(LyricsTakeover.KEY_CLOCK, it)
            },
        )
    }
}
