package com.amurot.notchdrop.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.amurot.notchdrop.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

// NotchDrop's design system: outlined surfaces, stroke icons in tinted tiles, and springy, haptic press feedback.

/** Stroke icons (24dp, 1.5 stroke, round caps) shared with the Linkerly icon set. */
object NdIcons {
    @Composable private fun res(@DrawableRes id: Int) = ImageVector.vectorResource(id)

    val Laptop @Composable get() = res(R.drawable.ic_laptop_outlined)
    val Phone @Composable get() = res(R.drawable.ic_phone_outlined)
    val Image @Composable get() = res(R.drawable.ic_image_outlined)
    val Folder @Composable get() = res(R.drawable.ic_folder_outlined)
    val Paste @Composable get() = res(R.drawable.ic_paste_outlined)
    val Copy @Composable get() = res(R.drawable.ic_copy_outlined)
    val Settings @Composable get() = res(R.drawable.ic_settings_outlined)
    val ChevronRight @Composable get() = res(R.drawable.ic_chevron_right_outlined)
    val Back @Composable get() = res(R.drawable.ic_arrow_back_outlined)
    val Up @Composable get() = res(R.drawable.ic_arrow_up_outlined)
    val Down @Composable get() = res(R.drawable.ic_arrow_down_outlined)
    val Check @Composable get() = res(R.drawable.ic_check_circle_outlined)
    val Warning @Composable get() = res(R.drawable.ic_warning_outlined)
    val Edit @Composable get() = res(R.drawable.ic_edit_outlined)
    val Open @Composable get() = res(R.drawable.ic_open_outlined)
    val Code @Composable get() = res(R.drawable.ic_code_outlined)
    val Notification @Composable get() = res(R.drawable.ic_notification_outlined)
    val Shield @Composable get() = res(R.drawable.ic_shield_outlined)
    val Close @Composable get() = res(R.drawable.ic_close_outlined)
    val Info @Composable get() = res(R.drawable.ic_info_outlined)
    val Delete @Composable get() = res(R.drawable.ic_delete_outlined)
    val QrScan @Composable get() = res(R.drawable.ic_qr_scan_outlined)
    val Refresh @Composable get() = res(R.drawable.ic_refresh_outlined)
    val Link @Composable get() = res(R.drawable.ic_link_outlined)
    val Battery @Composable get() = res(R.drawable.ic_battery_outlined)
    val Sun @Composable get() = res(R.drawable.ic_sun_outlined)
    val Moon @Composable get() = res(R.drawable.ic_moon_outlined)
    val Auto @Composable get() = res(R.drawable.ic_contrast_outlined)
    val Search @Composable get() = res(R.drawable.ic_search_outlined)
    val Share @Composable get() = res(R.drawable.ic_share_outlined)
    val Send @Composable get() = res(R.drawable.ic_send_outlined)
    val Sweep @Composable get() = res(R.drawable.ic_sweep_outlined)
    val Amurot @Composable get() = res(R.drawable.ic_amurot_logo)
    val Camera @Composable get() = res(R.drawable.ic_camera_outlined)
    val Cast @Composable get() = res(R.drawable.ic_cast_outlined)
    val Pointer @Composable get() = res(R.drawable.ic_pointer_outlined)
}

object NdSprings {
    /** Quick, slightly bouncy press and release. */
    fun <T> snappy() = spring<T>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)

    /** Settles without overshoot; for layout and colour. */
    fun <T> smooth() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
}

object NdShapes {
    val tile = RoundedCornerShape(12.dp)
    val control = RoundedCornerShape(16.dp)
    val card = RoundedCornerShape(22.dp)
    val sheet = RoundedCornerShape(30.dp)
}

@Composable
fun NdIcon(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 24.dp, tint: Color = Palette.text, contentDescription: String? = null) {
    Icon(icon, contentDescription, modifier.size(size), tint = tint)
}

/** An icon in a tinted rounded tile, the leading element of rows and section headers. */
@Composable
fun NdIconTile(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) { NdIcon(icon, size = size * 0.55f, tint = tint) }
}

// MARK: Press feedback

private val confirmHaptic =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS

/** Fires a short haptic tick; `strong` for confirmations. */
@Composable
fun rememberHaptic(): (strong: Boolean) -> Unit {
    val view = LocalView.current
    return remember(view) {
        { strong ->
            view.performHapticFeedback(if (strong) confirmHaptic else HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }
}

/**
 * Clickable with an accent ripple, a spring squash while held, and a haptic tick on release.
 * The default press feedback is easy to miss on dark surfaces; this makes every tap visible and felt.
 */
@Composable
fun Modifier.ndClickable(
    enabled: Boolean = true,
    pressedScale: Float = 0.96f,
    shape: Shape = RectangleShape,
    rippleColor: Color = Palette.accent,
    role: Role = Role.Button,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) pressedScale else 1f, NdSprings.snappy(), label = "press")
    val haptic = rememberHaptic()
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(shape)
        .clickable(
            interactionSource = source,
            indication = ripple(color = rippleColor),
            enabled = enabled,
            role = role,
        ) {
            haptic(false)
            onClick()
        }
}

// MARK: Buttons

enum class NdButtonStyle { Filled, Tonal, Outlined, Text, Danger }

@Composable
fun NdButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: NdButtonStyle = NdButtonStyle.Filled,
    enabled: Boolean = true,
) {
    val (bg, fg, border) = when (style) {
        NdButtonStyle.Filled -> Triple<Brush?, Color, Color?>(Palette.gradient, Color.White, null)
        NdButtonStyle.Tonal -> Triple(SolidColor(Palette.accent.copy(alpha = 0.14f)), Palette.accent, null)
        NdButtonStyle.Outlined -> Triple(null, Palette.text, Palette.lineStrong)
        NdButtonStyle.Text -> Triple(null, Palette.accent, null)
        NdButtonStyle.Danger -> Triple(SolidColor(Palette.bad.copy(alpha = 0.14f)), Palette.bad, null)
    }
    Row(
        modifier
            .height(48.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .ndClickable(enabled, pressedScale = 0.94f, shape = NdShapes.control, rippleColor = fg, onClick = onClick)
            .then(if (bg != null) Modifier.background(bg) else Modifier)
            .then(if (border != null) Modifier.border(1.5.dp, border, NdShapes.control) else Modifier)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            NdIcon(icon, size = 20.dp, tint = fg)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun NdIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Palette.text) {
    Box(
        modifier
            .size(44.dp)
            .ndClickable(pressedScale = 0.88f, shape = CircleShape, onClick = onClick)
            .background(Palette.surface2.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center,
    ) { NdIcon(icon, size = 22.dp, tint = tint, contentDescription = contentDescription) }
}

// MARK: Surfaces

/** Outlined surface; pass [onClick] for a pressable card. */
@Composable
fun NdCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    border: BorderStroke? = BorderStroke(1.dp, Palette.line),
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.ndClickable(pressedScale = 0.975f, shape = NdShapes.card, onClick = onClick) else Modifier)
            .clip(NdShapes.card)
            .background(Palette.surface)
            .then(if (border != null) Modifier.border(border, NdShapes.card) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** A titled group of rows in one outlined card, like a settings section. */
@Composable
fun NdGroup(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title.uppercase(), color = Palette.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
            )
        }
        NdCard(padding = 0.dp) { content() }
    }
}

@Composable
fun NdDivider(inset: Dp = 66.dp) {
    Box(Modifier.padding(start = inset).fillMaxWidth().height(1.dp).background(Palette.line))
}

/** A row inside a [NdGroup]: tinted icon tile, title and description, and a trailing control or chevron. */
@Composable
fun NdRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    tint: Color = Palette.accent,
    titleColor: Color = Palette.text,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.ndClickable(pressedScale = 0.98f, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            NdIconTile(icon, tint)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = titleColor, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (description != null) {
                Text(description, color = Palette.dim, fontSize = 13.sp, lineHeight = 17.sp)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        } else if (onClick != null) {
            NdIcon(NdIcons.ChevronRight, size = 18.dp, tint = Palette.dim)
        }
    }
}

// MARK: Controls

/** Custom switch: spring-driven thumb that stretches while pressed, accent track when on. */
@Composable
fun NdSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val view = LocalView.current
    val track by animateColorAsState(if (checked) Palette.accent else Palette.surface2, NdSprings.smooth(), label = "track")
    val thumbX by animateDpAsState(if (checked) 22.dp else 4.dp, NdSprings.snappy(), label = "thumbX")
    val thumbW by animateDpAsState(if (pressed) 26.dp else 22.dp, NdSprings.snappy(), label = "thumbW")
    Box(
        modifier
            .size(width = 48.dp, height = 30.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .clip(CircleShape)
            .background(track)
            .border(1.dp, if (checked) Color.Transparent else Palette.lineStrong, CircleShape)
            .clickable(source, indication = null, enabled = enabled, role = Role.Switch) {
                view.performHapticFeedback(confirmHaptic)
                onCheckedChange(!checked)
            },
    ) {
        Box(
            Modifier
                // Grows toward the centre so the stretch reads as anticipation of the flip.
                .offset { IntOffset((if (checked) thumbX - (thumbW - 22.dp) else thumbX).roundToPx(), 4.dp.roundToPx()) }
                .size(width = thumbW, height = 22.dp)
                .clip(CircleShape)
                .background(if (checked) Color.White else Palette.dim),
        )
    }
}

/** Custom segmented control with a sliding, springy selection pill. */
@Composable
fun NdSegmented(options: List<Pair<String, ImageVector?>>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier.fillMaxWidth().height(46.dp).clip(NdShapes.control).background(Palette.surface2).padding(4.dp),
    ) {
        val w = maxWidth / options.size
        val x by animateDpAsState(w * selected, NdSprings.snappy(), label = "segment")
        Box(Modifier.offset { IntOffset(x.roundToPx(), 0) }.width(w).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(Palette.surface)
            .border(1.dp, Palette.line, RoundedCornerShape(12.dp)))
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (label, icon) ->
                val color by animateColorAsState(if (i == selected) Palette.text else Palette.dim, label = "segText")
                Row(
                    Modifier.weight(1f).fillMaxHeight().ndClickable(pressedScale = 0.92f, shape = RoundedCornerShape(12.dp), role = Role.RadioButton) { onSelect(i) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (icon != null) {
                        NdIcon(icon, size = 17.dp, tint = color)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(label, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun NdTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    leading: ImageVector? = null,
    imeAction: ImeAction = ImeAction.Done,
    onDone: () -> Unit = {},
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val border by animateColorAsState(if (focused) Palette.accent else Palette.lineStrong, label = "fieldBorder")
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        interactionSource = source,
        textStyle = TextStyle(color = Palette.text, fontSize = 16.sp),
        cursorBrush = SolidColor(Palette.accent),
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }, onSearch = { onDone() }),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().clip(NdShapes.control).background(Palette.surface2)
                    .border(1.5.dp, border, NdShapes.control).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leading != null) {
                    NdIcon(leading, size = 20.dp, tint = Palette.dim)
                    Spacer(Modifier.width(10.dp))
                }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, color = Palette.dim, fontSize = 16.sp)
                    inner()
                }
            }
        },
    )
}

// MARK: Dialog

/**
 * Card dialog with a leading icon tile. It follows the predictive back gesture:
 * the card shrinks and slides toward the swipe edge before dismissing.
 */
@Composable
fun NdDialog(
    onDismiss: () -> Unit,
    title: String,
    icon: ImageVector? = null,
    tint: Color = Palette.accent,
    description: String? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
    buttons: @Composable RowScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val back = rememberPredictiveBack(onBack = onDismiss)
        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            NdCard(
                Modifier.widthIn(max = 420.dp).predictiveBackTransform(back),
                padding = 20.dp,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        NdIconTile(icon, tint, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(title, color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }
                if (description != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(description, color = Palette.dim, fontSize = 14.sp, lineHeight = 20.sp)
                }
                if (content != null) {
                    Spacer(Modifier.height(16.dp))
                    content()
                }
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), content = buttons)
            }
        }
    }
}

// MARK: Predictive back

/** Gesture progress (0–1) and the edge it started from, while a back swipe is in flight. */
class PredictiveBackState {
    val progress = Animatable(0f)
    var fromLeft = true
}

/** Intercepts back, animates [PredictiveBackState] with the gesture, then calls [onBack] on commit. */
@Composable
fun rememberPredictiveBack(enabled: Boolean = true, onBack: () -> Unit): PredictiveBackState {
    val state = remember { PredictiveBackState() }
    val scope = rememberCoroutineScope()
    PredictiveBackHandler(enabled) { events: Flow<BackEventCompat> ->
        try {
            events.collect { e ->
                state.fromLeft = e.swipeEdge == BackEventCompat.EDGE_LEFT
                state.progress.snapTo(e.progress)
            }
            onBack()
        } catch (e: CancellationException) {
            scope.launch { state.progress.animateTo(0f, NdSprings.snappy()) }
            throw e
        }
    }
    return state
}

/** Material-style predictive back transform: shrink, shift toward the swipe edge, and round the corners. */
fun Modifier.predictiveBackTransform(state: PredictiveBackState, shift: Dp = 16.dp): Modifier = graphicsLayer {
    val p = state.progress.value
    val s = 1f - 0.1f * p
    scaleX = s
    scaleY = s
    translationX = (if (state.fromLeft) 1f else -1f) * shift.toPx() * p
    transformOrigin = TransformOrigin(0.5f, 0.5f)
}
