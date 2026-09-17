package moe.openlock.app.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * A drawn side-on view of the bolt, so the state can be read without parsing a
 * word.
 *
 * The point of drawing it rather than shipping an image: the bolt is genuinely
 * animated between the two positions, so the drawing shows the *travel* - the
 * jamb, the gap, and the bolt crossing it - which is the thing that makes a lock
 * readable at a glance. An icon cannot show a position.
 *
 * `locked = null` means the app has not successfully talked to this lock yet, so
 * the bolt is drawn in the neutral position with a muted palette instead of
 * guessing at "unlocked".
 */
@Composable
fun LockStateIllustration(
    locked: Boolean?,
    state: String,
    modifier: Modifier = Modifier,
    height: Dp = 132.dp,
) {
    val engaged = locked == true
    val known = locked != null

    // A spring rather than a tween: the bolt is a physical object arriving at a
    // stop, and the slight overshoot is what sells that.
    val travel by animateFloatAsState(
        targetValue = if (engaged) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "boltTravel",
    )

    val structure = MiuixTheme.colorScheme.surfaceContainerHighest
    val body = if (known) {
        MiuixTheme.colorScheme.surfaceContainerHigh
    } else {
        MiuixTheme.colorScheme.surfaceContainer
    }
    val boltColor = when {
        !known -> MiuixTheme.colorScheme.disabledOnSurface
        engaged -> MiuixTheme.colorScheme.primary
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    val moving = state != "idle" && state != "unknown"
    // Read the theme before the Canvas: the draw lambda is not @Composable, so
    // anything it needs has to be resolved out here.
    val movingBar = MiuixTheme.colorScheme.primary.copy(alpha = 0.35f)

    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        val w = size.width
        val h = size.height
        val midY = h / 2f

        // Fractions of the canvas, so the drawing holds its proportions at any
        // size: the door jamb on the left, the lock body on the right, and the
        // gap between them that the bolt has to cross.
        fun x(frac: Float) = w * frac

        val jambLeft = x(0.06f)
        val jambRight = x(0.20f)
        val bodyLeft = x(0.52f)
        val bodyRight = x(0.94f)

        // The jamb and the body: static structure.
        drawRoundRect(
            color = structure,
            topLeft = Offset(jambLeft, h * 0.10f),
            size = Size(jambRight - jambLeft, h * 0.80f),
            cornerRadius = CornerRadius(h * 0.05f),
        )
        drawRoundRect(
            color = body,
            topLeft = Offset(bodyLeft, h * 0.26f),
            size = Size(bodyRight - bodyLeft, h * 0.48f),
            cornerRadius = CornerRadius(h * 0.08f),
        )

        // The bolt. Retracted it sits inside the body; extended it crosses the
        // gap and lands in the jamb, which is the whole difference between the
        // two states.
        val boltH = h * 0.11f
        val boltW = w * 0.26f
        val retracted = bodyLeft - w * 0.03f
        val extended = x(0.11f)
        val boltLeft = retracted + (extended - retracted) * travel

        drawRoundRect(
            color = boltColor,
            topLeft = Offset(boltLeft, midY - boltH / 2f),
            size = Size(boltW, boltH),
            cornerRadius = CornerRadius(boltH / 2f),
        )

        // A tick on the jamb marking where the bolt lands. Drawn so the empty
        // socket is visible while the bolt is away - it is what makes the
        // retracted position read as "there is somewhere for this to go".
        drawRoundRect(
            color = if (engaged) Color.Transparent else structure,
            topLeft = Offset(jambRight - w * 0.05f, midY - boltH * 0.7f),
            size = Size(w * 0.07f, boltH * 1.4f),
            cornerRadius = CornerRadius(boltH * 0.4f),
        )

        // While the mechanism is running, a soft bar under the drawing stands in
        // for a progress indicator without pretending to know how far along it is.
        if (moving) {
            drawRoundRect(
                color = movingBar,
                topLeft = Offset(x(0.06f), h * 0.94f),
                size = Size(x(0.88f), h * 0.035f),
                cornerRadius = CornerRadius(h * 0.02f),
            )
        }
    }
}

/** Caption block under [LockStateIllustration]: what the drawing is showing. */
@Composable
fun LockStateCaption(
    locked: Boolean?,
    battery: Int?,
    homed: Boolean?,
    lastAction: String?,
    modifier: Modifier = Modifier,
) {
    val (label, color) = when (locked) {
        true -> "已上锁" to MiuixTheme.colorScheme.onBackground
        false -> "已开锁" to MiuixTheme.colorScheme.primary
        null -> "状态未知" to MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StateDot(color)
                Text(text = label, color = color, style = MiuixTheme.textStyles.title4)
            }
            val detail = buildList {
                add("电量 ${batteryLabel(battery)}")
                if (homed == false) add("行程未标定")
                if (lastAction != null && lastAction != "ok") add("最近：$lastAction")
            }.joinToString(" · ")
            Muted(detail)
        }
    }
}

/** The same bolt, small, for a list row. */
@Composable
fun LockGlyph(locked: Boolean?, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    val engaged = locked == true
    val boltColor = when (locked) {
        null -> MiuixTheme.colorScheme.disabledOnSurface
        true -> MiuixTheme.colorScheme.primary
        false -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    val frame = MiuixTheme.colorScheme.surfaceContainerHighest
    Box(modifier = modifier.size(size)) {
        Canvas(modifier = Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val boltH = h * 0.22f
            // Locked: the bolt reaches the right-hand jamb. Unlocked: it has
            // withdrawn to the left of it.
            val jambW = w * 0.16f
            val boltW = if (engaged) w * 0.62f else w * 0.42f
            val boltLeft = if (engaged) w * 0.20f else w * 0.10f
            drawRoundRect(
                color = frame,
                topLeft = Offset(w - jambW, 0f),
                size = Size(jambW, h),
                cornerRadius = CornerRadius(w * 0.06f),
            )
            drawRoundRect(
                color = boltColor,
                topLeft = Offset(boltLeft, (h - boltH) / 2f),
                size = Size(boltW, boltH),
                cornerRadius = CornerRadius(boltH / 2f),
            )
        }
    }
}
