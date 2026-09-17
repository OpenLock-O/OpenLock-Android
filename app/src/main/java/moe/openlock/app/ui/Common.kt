package moe.openlock.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import moe.openlock.app.data.SettingsStore
import moe.openlock.app.data.UiPrefs
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingToolbarDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import moe.openlock.app.ui.liquidglass.liquid.LiquidGlassNavigationBar
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The chrome preferences, read by the shared frame instead of being threaded
 * through every screen.
 *
 * [staticCompositionLocalOf] rather than [androidx.compose.runtime.compositionLocalOf]:
 * a change here is a settings change, which re-composes the whole tree anyway,
 * so the cheaper read is the right trade.
 */
val LocalUiPrefs = staticCompositionLocalOf { UiPrefs() }

/**
 * Scale the whole interface, dp and sp together.
 *
 * Scaling [Density.density] alone would move the layout without growing the text,
 * and scaling `fontScale` alone would overflow every card; both together is what
 * "界面缩放" means. At 1.0 the provider is skipped entirely so the no-op path
 * costs nothing.
 */
@Composable
fun InterfaceScale(scale: Float, content: @Composable () -> Unit) {
    if (scale == SettingsStore.SCALE_DEFAULT) {
        content()
        return
    }
    val current = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = current.density * scale,
            fontScale = current.fontScale * scale,
        ),
        content = content,
    )
}

/**
 * How the bottom bar is being presented, handed to the bar's own composable.
 *
 * @param backdrop the content's recorded pixels, or null when the bar is
 *   docked (nothing runs underneath it, so there is nothing to frost).
 */
data class BarStyle(val backdrop: LayerBackdrop?, val floating: Boolean, val glass: Boolean)

/** The frame every screen sits in: one top bar, optionally a bottom bar. */
@Composable
fun LockScaffold(
    title: String,
    subtitle: String = "",
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bar: (@Composable (BarStyle) -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val ui = LocalUiPrefs.current
    val floating = bar != null && ui.floatingBar

    // A docked bar reserves its height in the Scaffold, so the content stops
    // above it and no clearance padding is needed. A floating bar is an overlay
    // instead - which is the whole point, because the content has to run
    // *underneath* it for there to be anything to frost.
    if (!floating) {
        Scaffold(
            topBar = {
                SmallTopAppBar(
                    title = title,
                    subtitle = subtitle,
                    navigationIcon = { BackButton(onBack) },
                    actions = actions,
                )
            },
            bottomBar = bar?.let { slot ->
                { slot(BarStyle(backdrop = null, floating = false, glass = false)) }
            } ?: {},
            content = content,
        )
        return
    }

    /*
     * The backdrop records the content's rendered pixels; `textureBlur` then
     * samples them behind the bar. The backdrop is always recorded when the bar
     * floats - recording it is cheap, and recreating it on a settings toggle
     * would flash the bar.
     */
    val backdrop = rememberLayerBackdrop()

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                SmallTopAppBar(
                    title = title,
                    subtitle = subtitle,
                    navigationIcon = { BackButton(onBack) },
                    actions = actions,
                )
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop),
            ) {
                content(padding)
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            bar(BarStyle(backdrop = backdrop, floating = true, glass = ui.liquidGlass))
        }
    }
}

@Composable
private fun BackButton(onBack: (() -> Unit)?) {
    if (onBack == null) return
    IconButton(onClick = onBack) {
        Icon(
            imageVector = MiuixIcons.Back,
            contentDescription = "返回",
            tint = MiuixTheme.colorScheme.onBackground,
            modifier = Modifier.size(24.dp),
        )
    }
}

/*
 * Blur strengths, in dp as `textureBlur` expects - not pixels. The plain value
 * matches miuix's own example (25); the glass one goes further, which is the
 * point of the switch.
 */
private const val BarBlurRadiusDp = 25f
private const val GlassBlurRadiusDp = 40f

/**
 * Bottom clearance a scrolling page must leave for a floating bar.
 *
 * Applied as content padding rather than as a Scaffold inset, because the bar
 * deliberately overlaps the content. Sized to the bar plus its own bottom
 * inset and margin so the last row is not trapped underneath it.
 */
val FloatingBarClearance = PaddingValues(bottom = 96.dp)

/** Pages switch to this when the bar is docked and needs no clearance. */
val DockedBarClearance = PaddingValues(bottom = 12.dp)

/**
 * The bottom clearance a scrolling page needs, given how the bar is placed.
 *
 * A page cannot hardcode [FloatingBarClearance]: with the bar docked, the
 * Scaffold has already reserved its height and repeating the clearance here
 * would leave a bar-sized hole at the end of every list.
 */
@Composable
fun barClearance(): PaddingValues =
    if (LocalUiPrefs.current.floatingBar) FloatingBarClearance else DockedBarClearance

/**
 * The app's bottom bar.
 *
 * Only the two tab roots carry it; a pushed screen (a lock, the scanner) is a
 * place you return from, so it gets a back arrow instead. Showing the bar there
 * too would suggest you can switch tabs from a detail view and lose your place.
 *
 * Floating and docked are the same two tabs, so only the container changes:
 * the liquid-glass pill (or the plain frosted pill when glass is off) sits
 * over the content, while [NavigationBar] is the plain bar that the content
 * stops above.
 *
 * The frost follows the recipe in miuix's own example app rather than an
 * invented one:
 *
 *  - with the blur on, the bar's own fill is transparent and the tint is
 *    stacked *over* the blurred backdrop as a blend entry, so the content shows
 *    through instead of being covered by a flat translucent fill;
 *  - with the blur off (or the shader unsupported), the bar falls back to a
 *    solid fill, which is a working bar rather than a broken effect.
 *
 * The liquid-glass pill itself is ported from the sample app into
 * `ui.liquidglass` (the library does not publish it); see its header for the
 * exact upstream revision.
 */
@Composable
fun AppNavigationBar(
    selected: Screen,
    onSelect: (Screen) -> Unit,
    style: BarStyle,
) {
    val backdrop = style.backdrop
    val blurOn = backdrop != null && LocalUiPrefs.current.blur
    val dark = MiuixTheme.colorScheme.background.luminance() < 0.5f

    val tabs = listOf(
        Triple(Screen.Locks, "门锁", MiuixIcons.Lock),
        Triple(Screen.Settings, "设置", MiuixIcons.Settings),
    )

    if (!style.floating) {
        // The docked bar is drawn by the Scaffold itself, so blurring the
        // content behind it would be pointless compositing.
        NavigationBar {
            tabs.forEach { (screen, label, icon) ->
                NavigationBarItem(
                    selected = selected == screen,
                    onClick = { onSelect(screen) },
                    icon = icon,
                    label = label,
                )
            }
        }
        return
    }

    if (style.glass) {
        // Liquid-glass pill, ported from the miuix sample app: icon plus label
        // per tab, a refraction lens over the blurred backdrop, and a press
        // indicator that follows the finger. The sample's own theme switch is
        // replaced inside the port by a palette-luminance check, so this just
        // passes the app's tabs through.
        val items = tabs.map { (_, label, icon) -> NavigationItem(label, icon) }
        val current = tabs.indexOfFirst { (screen, _, _) -> screen == selected }.coerceAtLeast(0)
        LiquidGlassNavigationBar(
            items = items,
            selectedIndex = current,
            onItemClick = { index -> onSelect(tabs[index].first) },
            backdrop = backdrop,
            isBlurActive = blurOn,
        )
        return
    }

    val shape = RoundedCornerShape(FloatingToolbarDefaults.CornerRadius)
    val barModifier = if (blurOn && backdrop != null) {
        Modifier.textureBlur(
            backdrop = backdrop,
            shape = shape,
            blurRadius = BarBlurRadiusDp,
            colors = BlurDefaults.blurColors(
                blendColors = listOf(
                    BlendColorEntry(
                        color = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f),
                    ),
                ),
            ),
        )
    } else {
        Modifier
    }

    FloatingNavigationBar(
        modifier = barModifier,
        color = if (blurOn) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer,
        cornerRadius = FloatingToolbarDefaults.CornerRadius,
        showDivider = false,
        shadowElevation = 0.dp,
    ) {
        tabs.forEach { (screen, label, icon) ->
            LabeledFloatingBarItem(
                selected = selected == screen,
                onClick = { onSelect(screen) },
                icon = icon,
                label = label,
            )
        }
    }
}


/**
 * A floating-bar tab with icon *and* label.
 *
 * This exists because miuix's own [FloatingNavigationBarItem] deliberately draws
 * the icon only - its `label` parameter feeds the accessibility tree, never the
 * screen. The pill therefore ships without any text, which is wrong for an app
 * whose two tabs ("门锁" / "设置") are told apart by name. The visuals follow
 * the official item (26dp icon, bold label when selected), so it reads as the
 * same component, only with the missing line restored.
 *
 * The tint logic mirrors what the official item does internally (it reads the
 * theme's item colors in unpublished code): selected tabs use the accent,
 * unselected ones the dimmed surface content. This is spelled out here instead
 * of reusing the library helper because 0.9.4-rc01 does not publish
 * `NavigationBarItemColors` - it only exists on upstream main.
 */
@Composable
private fun LabeledFloatingBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val base = if (selected) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.onSurfaceContainer
    }
    // Pressed feedback matches the library's own alphas: selected content dims
    // to half, unselected content drops less, so a press is visible either way.
    val tint = when {
        !isPressed -> base
        selected -> base.copy(alpha = base.alpha * 0.5f)
        else -> base.copy(alpha = base.alpha * 0.6f)
    }

    Column(
        modifier = modifier
            .selectable(
                selected = selected,
                onClick = onClick,
                enabled = enabled,
                role = Role.Tab,
                interactionSource = interactionSource,
                indication = null,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Image(
            modifier = Modifier.size(26.dp),
            imageVector = icon,
            // Decorative: the label below names the item; avoids TalkBack double-read.
            contentDescription = null,
            colorFilter = ColorFilter.tint(tint),
        )
        Text(
            text = label,
            color = tint,
            textAlign = TextAlign.Center,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}


/** A settings-style row: title, optional summary, optional trailing content. */
@Composable
fun InfoRow(
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    endActions: @Composable (RowScope.() -> Unit)? = null,
) {
    BasicComponent(
        title = title,
        summary = summary,
        enabled = enabled,
        onClick = onClick,
        endActions = endActions,
    )
}

/** A heading for a group of rows, matching the miuix list style. */
@Composable
fun GroupTitle(text: String) {
    SmallTitle(text = text, modifier = Modifier.padding(top = 8.dp))
}

/**
 * A tappable row in a card of its own: a title, an optional summary, and a
 * chevron.
 *
 * The chevron is only drawn when there is somewhere to go. miuix's
 * [top.yukonga.miuix.kmp.preference.ArrowPreference] always draws one, which is
 * why a read-only value uses [InfoRow] instead - a chevron on a row that does
 * nothing is a promise the row does not keep.
 *
 * A disabled row gets the plain, non-tappable Card rather than a clickable one
 * that ignores taps: miuix's clickable Card has no `enabled` of its own, so the
 * only way not to show a press ripple is not to be clickable at all. The greyed
 * text comes from the row inside, which does have an `enabled`.
 */
@Composable
fun ClickableCard(
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val action = onClick?.takeIf { enabled }
    val cardModifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)

    if (action == null) {
        Card(modifier = cardModifier) {
            ClickableCardBody(title, summary, enabled, null, leading, trailing)
        }
    } else {
        Card(onClick = action, modifier = cardModifier) {
            ClickableCardBody(title, summary, enabled, action, leading, trailing)
        }
    }
}

/** The row inside [ClickableCard]. Split out so both Card overloads share it. */
@Composable
private fun ClickableCardBody(
    title: String,
    summary: String?,
    enabled: Boolean,
    onClick: (() -> Unit)?,
    leading: (@Composable () -> Unit)?,
    trailing: (@Composable () -> Unit)?,
) {
    BasicComponent(
        title = title,
        summary = summary,
        enabled = enabled,
        onClick = onClick,
        startAction = leading,
        endActions = {
            trailing?.invoke()
            if (onClick != null) {
                Icon(
                    imageVector = MiuixIcons.ChevronForward,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
    )
}

/** A dot in a colour, for the one-line state a row cannot say in words alone. */
@Composable
fun StateDot(color: Color, size: androidx.compose.ui.unit.Dp = 10.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
    )
}

/**
 * The lock's bolt state, as a coloured label.
 *
 * `locked = null` means the app has not successfully talked to the lock yet -
 * it is not the same as "unlocked", so it says so rather than guessing.
 */
@Composable
fun BoltStateText(locked: Boolean?, style: androidx.compose.ui.text.TextStyle? = null) {
    val (label, color) = when (locked) {
        true -> "已上锁" to MiuixTheme.colorScheme.onBackground
        false -> "已开锁" to MiuixTheme.colorScheme.primary
        null -> "状态未知" to MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Text(
        text = label,
        color = color,
        style = style ?: MiuixTheme.textStyles.main,
    )
}

/**
 * Battery, or an honest "unknown".
 *
 * `null` (the firmware does not report it) and a negative sentinel both mean the
 * app has no reading, and neither is the same as 0%.
 */
@Composable
fun BatteryText(percent: Int?, modifier: Modifier = Modifier) {
    Text(text = batteryLabel(percent), modifier = modifier, color = MiuixTheme.colorScheme.onBackground)
}

/** The text form of a battery reading, for use inside a larger line. */
fun batteryLabel(percent: Int?): String =
    if (percent == null || percent < 0) "未知" else "$percent%"

/** The bolt state as a word, for a summary line or a row's second line. */
fun boltLabel(locked: Boolean?): String = when (locked) {
    true -> "已上锁"
    false -> "已开锁"
    null -> "状态未知"
}

/** Small muted line, for caveats under a row. */
@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        style = MiuixTheme.textStyles.footnote1,
    )
}

/** A status dot with a label, used for the live connection state. */
@Composable
fun ConnChip(label: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = "●", color = color, style = MiuixTheme.textStyles.footnote1)
        Muted(label)
    }
}

/** Centred empty-state block. */
@Composable
fun EmptyState(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = title, style = MiuixTheme.textStyles.title4)
        Text(
            text = body,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.paragraph,
        )
    }
}
