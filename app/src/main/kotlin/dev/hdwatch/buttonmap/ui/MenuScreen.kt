package dev.hdwatch.buttonmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hdwatch.buttonmap.HdApp
import dev.hdwatch.buttonmap.R
import dev.hdwatch.buttonmap.config.ProfileKind

/** Shared row look for every menu-style screen (title + menu rows, v2 look). */
@Composable
fun ScreenScaffold(
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val palette = LocalHdPalette.current
    val nav = LocalAppNav.current
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    listOf(palette.surface, palette.background, palette.edge),
                ),
            ),
    ) {
        // Edge-swipe back is not a thing on this watch (left-edge swipe exits
        // the app), so every config screen carries its own way out.
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                // Inside the round mask: the first attempt sat outside the
                // glass and was invisible.
                .padding(start = 52.dp, top = 20.dp)
                .size(34.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        nav.pop()
                        waitForUpOrCancellation()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("‹", color = palette.secondary.copy(alpha = 0.75f), fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Top band kept clear for the back affordance.
                .padding(start = 14.dp, end = 14.dp, top = 46.dp, bottom = 10.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MicroLabel(
                text = title,
                color = palette.primary.copy(alpha = 0.9f),
                modifier = Modifier.padding(top = 12.dp, bottom = 10.dp),
            )
            content()
            Spacer(Modifier.height(46.dp))
        }
    }
}

@Composable
fun MenuRow(
    label: String,
    hint: String = "",
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val palette = LocalHdPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    HdPanel(
        modifier = Modifier
            .widthIn(min = 132.dp, max = 168.dp)
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        shape = RoundedCornerShape(8.dp),
        pressed = pressed,
        interactionSource = interaction,
        onClick = onClick,
    ) {
        if (accent) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(vertical = 7.dp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(
                        Brush.verticalGradient(
                            listOf(palette.primary, palette.primary.copy(alpha = 0.15f)),
                        ),
                        RoundedCornerShape(1.dp),
                    ),
            )
        }
        Column(Modifier.padding(start = 13.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Text(
                label,
                color = if (pressed) palette.onPrimary else palette.text,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
            )
            if (hint.isNotEmpty()) {
                Text(
                    hint,
                    color = if (pressed) palette.onPrimary.copy(alpha = 0.7f) else palette.muted,
                    fontSize = 9.sp,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
fun MenuScreen() {
    val app = HdApp.instance
    val nav = LocalAppNav.current
    val actions = LocalPlatformActions.current
    val config by app.configRepo.config.collectAsState()
    val transport = app.transports.forSettings(config.settings)
    val status by transport.status.collectAsState()
    val repoStatus by app.configRepo.status.collectAsState()

    var showProfiles by remember { mutableStateOf(false) }

    ScreenScaffold(title = stringResource(R.string.scr_menu_title)) {
        MenuRow(
            label = stringResource(R.string.scr_menu_mode, config.activeProfile.name),
            hint = if (showProfiles) {
                stringResource(R.string.scr_menu_collapse)
            } else {
                stringResource(R.string.scr_menu_mode_hint, config.profiles.size)
            },
        ) { showProfiles = !showProfiles }
        if (showProfiles) {
            config.profiles.forEach { profile ->
                val kindLabel = stringResource(
                    if (profile.kind == ProfileKind.KEYS) R.string.scr_menu_kind_keys else R.string.scr_menu_kind_macro,
                )
                MenuRow(
                    label = if (profile.id == config.activeProfileId) "✓ ${profile.name}" else profile.name,
                    hint = kindLabel,
                    accent = profile.id == config.activeProfileId,
                ) { app.configRepo.setActiveProfile(profile.id) }
            }
        }
        MenuRow(
            stringResource(R.string.scr_menu_single),
            stringResource(R.string.scr_menu_single_hint),
        ) { nav.push(Route.MappingList) }
        MenuRow(
            stringResource(R.string.scr_menu_macros),
            stringResource(
                R.string.scr_menu_macro_count,
                config.activeProfile.macros.count { it.enabled },
                config.activeProfile.macros.size,
            ),
        ) { nav.push(Route.MacroList) }
        MenuRow(stringResource(R.string.scr_menu_bluetooth), status.label + " · " + status.detail) { nav.push(Route.Hid) }
        MenuRow(stringResource(R.string.scr_menu_settings)) { nav.push(Route.Settings) }
        MenuRow(stringResource(R.string.scr_menu_log)) { nav.push(Route.Log) }
        MenuRow(stringResource(R.string.scr_menu_reload), repoStatus) { app.configRepo.reload() }
        MenuRow(stringResource(R.string.scr_menu_import)) { actions.importFile() }
    }
}
