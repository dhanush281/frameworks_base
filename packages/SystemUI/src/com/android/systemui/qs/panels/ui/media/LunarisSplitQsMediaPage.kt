/*
 * Copyright (C) 2026 Lunaris AOSP
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(ExperimentalMaterial3Api::class)

package com.android.systemui.qs.panels.ui.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.Expandable as ExpandableContainer
import com.android.compose.animation.rememberExpandableController
import com.android.settingslib.media.MediaOutputConstants
import com.android.systemui.common.shared.model.Icon as IconModel
import com.android.systemui.common.shared.model.asImageBitmap
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.common.ui.compose.PagerDots
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.media.dialog.MediaOutputDialogReceiver
import com.android.systemui.media.remedia.domain.model.MediaSessionModel
import com.android.systemui.media.remedia.motionart.FadingLunarisMotionArtVideo
import com.android.systemui.media.remedia.shared.model.MediaSessionState
import com.android.systemui.media.remedia.ui.compose.MediaUiBehavior
import com.android.systemui.media.remedia.ui.viewmodel.MediaCarouselVisibility
import com.android.systemui.media.remedia.ui.viewmodel.MediaViewModel
import com.android.systemui.qs.panels.shared.model.AxMediaSurface
import com.android.systemui.qs.panels.ui.compose.AxTileDefaults
import com.android.systemui.qs.panels.ui.compose.LocalLunarisQsCosTileBackground
import com.android.systemui.qs.panels.ui.compose.lunarisQsCosTileBorder
import com.android.systemui.qs.panels.ui.viewmodel.AxMediaViewModel
import com.android.systemui.res.R
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.AudioStreamSliderViewModel
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.SliderState
import kotlin.math.roundToInt

private val SplitMediaCardShape = RoundedCornerShape(32.dp)
private val SplitMediaArtShape = RoundedCornerShape(22.dp)

@Composable
internal fun LunarisSplitQsMediaPage(
    viewModel: AxMediaViewModel,
    mediaViewModelFactory: MediaViewModel.Factory?,
    volumeSliderViewModel: AudioStreamSliderViewModel?,
    visible: Boolean,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    val sessions = viewModel.visibleSessions(AxMediaSurface.CONTROL)
    val currentSession = viewModel.currentSession(AxMediaSurface.CONTROL)
    val lastMediaPackage by viewModel.lastMediaPackage.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel, currentSession?.key) {
        viewModel.synchronizeSession(currentSession?.key)
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .widthIn(max = 360.dp)
                    .heightIn(min = 396.dp, max = 468.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (mediaViewModelFactory != null && sessions.size > 1) {
                val context = LocalContext.current
                val behavior =
                    remember(viewModel) {
                        MediaUiBehavior(
                            isCarouselDismissible = false,
                            isCarouselScrollingEnabled = true,
                            carouselVisibility = MediaCarouselVisibility.WhenAnyCardIsActive,
                            isCarouselScrollFalseTouch = { viewModel.isSwipeFalseTouch() },
                        )
                    }
                val carouselViewModel: MediaViewModel =
                    rememberViewModel(traceName = "SplitQsMediaCarousel") {
                        mediaViewModelFactory.create(context, behavior.carouselVisibility)
                    }
                AxMediaCarousel(
                    viewModel = carouselViewModel,
                    behavior = behavior,
                    onDismissed = {},
                    modifier = Modifier.fillMaxSize(),
                    cardFilter = { viewModel.isSessionVisible(it.key, AxMediaSurface.CONTROL) },
                    carouselShape = SplitMediaCardShape,
                    cardContent = { card, cardModifier, isCurrentCard ->
                        val session = viewModel.sessionForKey(card.key)
                        SplitQsMediaCard(
                            session = session,
                            lastMediaPackage = lastMediaPackage,
                            viewModel = viewModel,
                            volumeSliderViewModel = volumeSliderViewModel,
                            motionArtVisible = visible && isCurrentCard,
                            motionArtAllowedToPlay = playing,
                            hasMultipleSessions = true,
                            modifier = cardModifier.fillMaxSize(),
                        )
                    },
                    pagerIndicator = { pagerState ->
                        PagerDots(
                            pagerState = pagerState,
                            activeColor = Color.White,
                            nonActiveColor = Color.White.copy(alpha = 0.36f),
                            dotSize = 6.dp,
                            spaceSize = 6.dp,
                            modifier =
                                Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
                        )
                    },
                )
            } else {
                SplitQsMediaCard(
                    session = currentSession,
                    lastMediaPackage = lastMediaPackage,
                    viewModel = viewModel,
                    volumeSliderViewModel = volumeSliderViewModel,
                    motionArtVisible = visible,
                    motionArtAllowedToPlay = playing,
                    hasMultipleSessions = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun SplitQsMediaCard(
    session: MediaSessionModel?,
    lastMediaPackage: String?,
    viewModel: AxMediaViewModel,
    volumeSliderViewModel: AudioStreamSliderViewModel?,
    motionArtVisible: Boolean,
    motionArtAllowedToPlay: Boolean,
    hasMultipleSessions: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val motionArtEnabled by viewModel.mediaMotionArt.collectAsStateWithLifecycle()
    val isShadeInteracting by viewModel.isShadeInteracting.collectAsStateWithLifecycle()
    val keyguardState by viewModel.currentKeyguardState.collectAsStateWithLifecycle()

    val baseTileBg = AxTileDefaults.backgroundColor()
    val scheme = session?.colorScheme
    val cardBackground by
        animateColorAsState(
            targetValue =
                if (scheme != null) {
                    lerp(baseTileBg, scheme.background.copy(alpha = 0.68f), 0.32f)
                } else {
                    baseTileBg
                },
            label = "SplitMediaCardBg",
        )
    val foreground = MaterialTheme.colorScheme.onSurface
    val accent by
        animateColorAsState(
            targetValue = scheme?.primary ?: MaterialTheme.colorScheme.primary,
            label = "SplitMediaCardAccent",
        )
    val onAccent by
        animateColorAsState(
            targetValue = scheme?.onPrimary ?: MaterialTheme.colorScheme.onPrimary,
            label = "SplitMediaCardOnAccent",
        )
    val colors =
        AxMediaColors(
            primary = accent,
            onPrimary = onAccent,
            background = cardBackground,
            foreground = foreground,
        )

    val motionArtActive =
        motionArtVisible &&
            session?.state == MediaSessionState.Playing &&
            keyguardState != KeyguardState.OFF &&
            keyguardState != KeyguardState.DOZING &&
            keyguardState != KeyguardState.AOD
    val motionArtPlaying = motionArtActive && motionArtAllowedToPlay && !isShadeInteracting
    val motionArt =
        rememberLunarisMotionArtFile(
            artist = session?.subtitle,
            title = session?.title,
            enabled = motionArtEnabled && motionArtActive,
        )

    ExpandableContainer(
        controller =
            rememberExpandableController(color = { cardBackground }, shape = SplitMediaCardShape),
        modifier = modifier.clip(SplitMediaCardShape),
        onClick = null,
        onClickLabel = null,
        defaultMinSize = false,
        useModifierBasedImplementation = true,
    ) { expandable ->
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .lunarisQsCosTileBorder(
                        shape = SplitMediaCardShape,
                        enabled = LocalLunarisQsCosTileBackground.current,
                    )
                    .border(
                        width = 0.75.dp,
                        color = foreground.copy(alpha = 0.14f),
                        shape = SplitMediaCardShape,
                    )
                    .padding(
                        start = 22.dp,
                        end = 22.dp,
                        top = 18.dp,
                        bottom = if (hasMultipleSessions) 30.dp else 20.dp,
                    )
        ) {
            if (session != null) {
                Box(
                    modifier =
                        Modifier.align(Alignment.TopEnd)
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(foreground.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center,
                ) {
                    MediaAppIcon(
                        session = session,
                        size = 22.dp,
                        tint = Color.Unspecified,
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier =
                        Modifier.fillMaxWidth()
                            .weight(1f)
                            .combinedClickable(
                                enabled = session != null || lastMediaPackage != null,
                                onClick = {
                                    if (session != null) {
                                        viewModel.openSession(session, expandable)
                                    } else {
                                        viewModel.openLastMediaApp(expandable)
                                    }
                                },
                            ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (session != null) {
                        SplitMediaArtworkHero(
                            session = session,
                            motionArt = motionArt,
                            motionArtPlaying = motionArtPlaying,
                            foreground = foreground,
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = session.title,
                            color = foreground,
                            style =
                                MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 18.sp,
                                ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().basicMarquee(),
                        )
                        if (session.subtitle.isNotBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = session.subtitle,
                                color = foreground.copy(alpha = 0.68f),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        MaterialIcon(
                            imageVector = Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = foreground.copy(alpha = 0.34f),
                            modifier = Modifier.size(92.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.lunaris_qs_media_not_playing),
                            color = foreground.copy(alpha = 0.72f),
                            style =
                                MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 18.sp,
                                ),
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                MediaSeekBar(
                    session = session,
                    viewModel = viewModel,
                    colors =
                        colors.copy(
                            primary = if (session != null) foreground else foreground.copy(alpha = 0.3f),
                            foreground =
                                if (session != null) foreground else foreground.copy(alpha = 0.45f),
                        ),
                    dense = true,
                    interactive = session != null,
                    showTimestamps = true,
                    timestampsBelow = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(8.dp))

                CompositionLocalProvider(LocalMediaButtonSquish provides true) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val actionTint =
                            if (session != null) foreground else foreground.copy(alpha = 0.36f)
                        CoreMediaAction(
                            action = session?.leftAction,
                            imageVector = Icons.Filled.SkipPrevious,
                            descriptionRes = R.string.controls_media_button_prev,
                            viewModel = viewModel,
                            width = 48.dp,
                            iconSize = 28.dp,
                            tint = actionTint,
                            interactive = session != null,
                        )
                        CoreMediaAction(
                            action = session?.playPauseAction,
                            imageVector = playPauseIcon(session),
                            descriptionRes = playPauseDescription(session),
                            viewModel = viewModel,
                            width = 56.dp,
                            iconSize = 36.dp,
                            tint = actionTint,
                            interactive = session != null,
                        )
                        CoreMediaAction(
                            action = session?.rightAction,
                            imageVector = Icons.Filled.SkipNext,
                            descriptionRes = R.string.controls_media_button_next,
                            viewModel = viewModel,
                            width = 48.dp,
                            iconSize = 28.dp,
                            tint = actionTint,
                            interactive = session != null,
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                if (volumeSliderViewModel != null) {
                    SplitMediaVolumeRow(
                        viewModel = volumeSliderViewModel,
                        foreground = foreground,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                }

                SplitMediaOutputPill(
                    session = session,
                    viewModel = viewModel,
                    foreground = foreground,
                    onFallbackClick = { launchSystemMediaOutputDialog(context, lastMediaPackage) },
                )
            }
        }
    }
}

@Composable
private fun SplitMediaArtworkHero(
    session: MediaSessionModel,
    motionArt: java.io.File?,
    motionArtPlaying: Boolean,
    foreground: Color,
) {
    val artwork = session.background
    Box(
        modifier =
            Modifier.size(136.dp)
                .clip(SplitMediaArtShape)
                .background(foreground.copy(alpha = 0.12f))
                .border(0.75.dp, foreground.copy(alpha = 0.16f), SplitMediaArtShape),
        contentAlignment = Alignment.Center,
    ) {
        when (artwork) {
            is IconModel.Loaded -> {
                val bitmap = remember(artwork) { artwork.asImageBitmap() }
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            is IconModel.Resource -> {
                Icon(
                    icon = artwork,
                    tint = Color.Unspecified,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            null -> {
                MaterialIcon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = foreground.copy(alpha = 0.45f),
                    modifier = Modifier.size(56.dp),
                )
            }
        }
        FadingLunarisMotionArtVideo(
            file = motionArt,
            crop = true,
            isPlaying = motionArtPlaying,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun SplitMediaVolumeRow(
    viewModel: AudioStreamSliderViewModel,
    foreground: Color,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.slider.collectAsStateWithLifecycle(SliderState.Empty)
    val interactionSource = remember { MutableInteractionSource() }
    val isDragging by interactionSource.collectIsDraggedAsState()
    var localValue by remember { mutableFloatStateOf(state.value) }
    var lastHapticStep by remember { mutableStateOf(state.value.roundToInt()) }
    val view = LocalView.current

    LaunchedEffect(state.value, isDragging) {
        if (!isDragging) {
            localValue = state.value
            lastHapticStep = state.value.roundToInt()
        }
    }

    val animatedValue by
        animateFloatAsState(
            targetValue = if (isDragging) localValue else state.value,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "SplitMediaVolumeValue",
        )
    val sliderValue = if (isDragging) localValue else animatedValue
    val range = state.valueRange
    val activeRange =
        if (range.endInclusive > range.start) range else 0f..1f

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MaterialIcon(
            imageVector = Icons.Filled.VolumeDown,
            contentDescription = null,
            tint = foreground.copy(alpha = 0.65f),
            modifier = Modifier.size(18.dp),
        )
        Slider(
            value = sliderValue.coerceIn(activeRange.start, activeRange.endInclusive),
            onValueChange = { newValue ->
                localValue = newValue
                val step = newValue.roundToInt()
                if (step != lastHapticStep) {
                    lastHapticStep = step
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                viewModel.onValueChanged(state, newValue)
            },
            onValueChangeFinished = { viewModel.onValueChangeFinished() },
            valueRange = activeRange,
            enabled = state.isEnabled && range.endInclusive > range.start,
            interactionSource = interactionSource,
            colors =
                SliderDefaults.colors(
                    thumbColor = foreground,
                    activeTrackColor = foreground.copy(alpha = 0.88f),
                    inactiveTrackColor = foreground.copy(alpha = 0.22f),
                    disabledThumbColor = foreground.copy(alpha = 0.4f),
                    disabledActiveTrackColor = foreground.copy(alpha = 0.3f),
                    disabledInactiveTrackColor = foreground.copy(alpha = 0.14f),
                ),
            thumb = {
                Box(
                    Modifier.size(14.dp)
                        .clip(CircleShape)
                        .background(foreground)
                )
            },
            track = { sliderState ->
                val fraction =
                    ((sliderState.value - sliderState.valueRange.start) /
                            (sliderState.valueRange.endInclusive - sliderState.valueRange.start)
                                .coerceAtLeast(1e-4f))
                        .coerceIn(0f, 1f)
                Box(
                    modifier =
                        Modifier.fillMaxWidth()
                            .height(5.dp)
                            .clip(CircleShape)
                            .background(foreground.copy(alpha = 0.22f))
                ) {
                    Box(
                        modifier =
                            Modifier.fillMaxWidth(fraction)
                                .height(5.dp)
                                .clip(CircleShape)
                                .background(foreground.copy(alpha = 0.88f))
                    )
                }
            },
            modifier =
                Modifier.weight(1f)
                    .height(24.dp)
                    .pointerInput(view) {
                        awaitEachGesture {
                            awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                            try {
                                var pressed = true
                                while (pressed) {
                                    val event = awaitPointerEvent(PointerEventPass.Final)
                                    pressed = event.changes.any { it.pressed }
                                }
                            } finally {
                                view.parent?.requestDisallowInterceptTouchEvent(false)
                            }
                        }
                    },
        )
        MaterialIcon(
            imageVector = Icons.Filled.VolumeUp,
            contentDescription = null,
            tint = foreground.copy(alpha = 0.65f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun SplitMediaOutputPill(
    session: MediaSessionModel?,
    viewModel: AxMediaViewModel,
    foreground: Color,
    onFallbackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label =
        session?.outputDevice?.name?.toString()?.takeUnless { it.isBlank() || it == "null" }
            ?: stringResource(R.string.ax_dynamic_bar_media_output)
    val interactionSource = remember { MutableInteractionSource() }

    ExpandableContainer(
        controller =
            rememberExpandableController(color = { Color.Transparent }, shape = CircleShape),
        modifier = modifier,
        defaultMinSize = false,
        useModifierBasedImplementation = true,
    ) { expandable ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier =
                Modifier.clip(CircleShape)
                    .background(foreground.copy(alpha = 0.12f))
                    .border(0.75.dp, foreground.copy(alpha = 0.16f), CircleShape)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = ripple(),
                    ) {
                        if (session != null) {
                            viewModel.openOutput(session.outputDevice, expandable)
                        } else {
                            onFallbackClick()
                        }
                    }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            if (session != null) {
                Icon(
                    icon = session.outputDevice.icon,
                    tint = foreground.copy(alpha = 0.9f),
                    modifier = Modifier.size(16.dp),
                )
            } else {
                MaterialIcon(
                    imageVector = Icons.Filled.Headset,
                    contentDescription = null,
                    tint = foreground.copy(alpha = 0.9f),
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                color = foreground.copy(alpha = 0.9f),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun launchSystemMediaOutputDialog(context: Context, packageName: String?) {
    context.sendBroadcast(
        Intent(MediaOutputConstants.ACTION_LAUNCH_MEDIA_OUTPUT_DIALOG).apply {
            if (!packageName.isNullOrBlank()) {
                putExtra(MediaOutputConstants.EXTRA_PACKAGE_NAME, packageName)
            }
            component =
                ComponentName(
                    "com.android.systemui",
                    MediaOutputDialogReceiver::class.java.name,
                )
        }
    )
}
