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

package com.android.systemui.qs.panels.ui.compose

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.android.compose.animation.scene.ContentScope
import com.android.systemui.brightness.ui.viewmodel.BrightnessSliderViewModel
import com.android.systemui.qs.composefragment.viewmodel.QSFragmentComposeViewModel
import com.android.systemui.qs.panels.shared.model.AxQsLayout
import com.android.systemui.qs.panels.ui.media.LunarisSplitQsMediaPage
import com.android.systemui.qs.panels.ui.viewmodel.AxMediaViewModel
import com.android.systemui.qs.panels.ui.viewmodel.AxQsViewModel
import com.android.systemui.qs.panels.ui.viewmodel.toolbar.ToolbarViewModel
import com.android.systemui.res.R
import com.android.systemui.shade.ui.composable.ShadeHeader
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.AudioStreamSliderViewModel
import kotlin.math.abs
import kotlinx.coroutines.launch

internal enum class LunarisSplitQsPage(val index: Int, val editLayout: AxQsLayout?) {
    MAIN(0, AxQsLayout.QS),
    MEDIA(1, null),
    NETWORK(2, AxQsLayout.QS_NETWORK),
    CUSTOM(3, AxQsLayout.QS_CUSTOM);

    companion object {
        fun fromIndex(index: Int): LunarisSplitQsPage =
            entries.getOrElse(index.coerceIn(0, entries.lastIndex)) { MAIN }
    }
}

private val SplitPageRailWidth = 42.dp
private val SplitPageContentEndPadding = 44.dp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ContentScope.LunarisSplitQsPager(
    pagerState: PagerState,
    viewModel: QSFragmentComposeViewModel,
    toolbarViewModel: ToolbarViewModel,
    axQsViewModel: AxQsViewModel,
    mediaViewModel: AxMediaViewModel,
    brightnessSliderViewModel: BrightnessSliderViewModel,
    volumeSliderViewModel: AudioStreamSliderViewModel?,
    mainScrollState: ScrollState,
    headerInOverlay: Boolean,
    topPadding: Dp,
    listening: () -> Boolean,
    holdExpandedContent: () -> Boolean,
    panelSwipeProgress: () -> Float,
    panelSwipeFromQs: () -> Boolean,
    expansionFromLeft: () -> Boolean,
    animationResetSequence: () -> Int,
    onEditPage: (AxQsLayout) -> Unit,
    onActiveScrollStateChanged: (ScrollState?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val networkScrollState = rememberScrollState()
    val customScrollState = rememberScrollState()
    val onScrollGestureStart = LocalQsScrollGestureStart.current
    val coroutineScope = rememberCoroutineScope()
    val view = LocalView.current
    val safeTopInset =
        maxOf(
            WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
            WindowInsets.displayCutout.asPaddingValues().calculateTopPadding(),
        )

    LaunchedEffect(pagerState, mainScrollState, networkScrollState, customScrollState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            val activeState =
                when (LunarisSplitQsPage.fromIndex(page)) {
                    LunarisSplitQsPage.MAIN -> mainScrollState
                    LunarisSplitQsPage.MEDIA -> null
                    LunarisSplitQsPage.NETWORK -> networkScrollState
                    LunarisSplitQsPage.CUSTOM -> customScrollState
                }
            onActiveScrollStateChanged(activeState)
        }
    }

    LaunchedEffect(viewModel.isQsVisibleAndAnyShadeExpanded) {
        if (!viewModel.isQsVisibleAndAnyShadeExpanded) {
            if (pagerState.currentPage != 0 || pagerState.currentPageOffsetFraction != 0f) {
                pagerState.scrollToPage(0)
            }
            networkScrollState.scrollTo(0)
            customScrollState.scrollTo(0)
        }
    }

    val flingBehavior =
        PagerDefaults.flingBehavior(
            state = pagerState,
            snapPositionalThreshold = 0.24f,
            snapAnimationSpec =
                spring(
                    dampingRatio = 0.86f,
                    stiffness = 420f,
                ),
        )

    Box(modifier = modifier.fillMaxSize()) {
        VerticalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            flingBehavior = flingBehavior,
            modifier = Modifier.fillMaxSize(),
        ) { pageIndex ->
            val page = LunarisSplitQsPage.fromIndex(pageIndex)
            val isPageVisible = {
                val exactPage = pagerState.currentPage + pagerState.currentPageOffsetFraction
                abs(exactPage - pageIndex) < 0.98f
            }
            val pageListening = { listening() && isPageVisible() }
            val pageModifier =
                Modifier.fillMaxSize().graphicsLayer {
                    val pageOffset =
                        (pagerState.currentPage - pageIndex) + pagerState.currentPageOffsetFraction
                    val absOffset = abs(pageOffset).coerceIn(0f, 1f)
                    val eased = FastOutSlowInEasing.transform(absOffset)
                    val scale = lerp(1f, 0.86f, eased)
                    scaleX = scale
                    scaleY = scale
                    alpha = (1f - eased * 1.15f).coerceIn(0f, 1f)
                    translationY = pageOffset * size.height * 0.06f
                    transformOrigin = TransformOrigin(0.46f, 0.5f)
                }

            when (page) {
                LunarisSplitQsPage.MAIN -> {
                    Box(
                        modifier =
                            pageModifier
                                .padding(top = topPadding)
                                .motionEventSpy { event ->
                                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                                        onScrollGestureStart?.invoke(mainScrollState)
                                    }
                                }
                    ) {
                        AxQsMixedGrid(
                            headerInOverlay = headerInOverlay,
                            viewModel = viewModel,
                            toolbarViewModel = toolbarViewModel,
                            axQsViewModel = axQsViewModel,
                            mediaViewModel = mediaViewModel,
                            qqs = false,
                            listening = pageListening,
                            brightnessSliderViewModel = brightnessSliderViewModel,
                            volumeSliderViewModel = volumeSliderViewModel,
                            scrollState = mainScrollState,
                            sharedLayout = false,
                            holdExpandedContent = holdExpandedContent,
                            panelSwipeProgress = panelSwipeProgress,
                            panelSwipeFromQs = panelSwipeFromQs,
                            expansionFromLeft = expansionFromLeft,
                            animationResetSequence = animationResetSequence,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                LunarisSplitQsPage.MEDIA -> {
                    val mediaPlaying =
                        viewModel.isQsVisibleAndAnyShadeExpanded &&
                            !viewModel.isEditing &&
                            pagerState.currentPage == LunarisSplitQsPage.MEDIA.index &&
                            !pagerState.isScrollInProgress &&
                            !axQsViewModel.isShadeBeingDragged
                    Box(
                        modifier =
                            pageModifier.padding(
                                start = axQsViewModel.spacing.sidePadding.dp,
                                end = SplitPageContentEndPadding,
                                top = safeTopInset + 16.dp,
                                bottom = 24.dp,
                            )
                    ) {
                        LunarisSplitQsMediaPage(
                            viewModel = mediaViewModel,
                            mediaViewModelFactory = viewModel.mediaViewModelFactory,
                            volumeSliderViewModel = volumeSliderViewModel,
                            visible = pageListening(),
                            playing = mediaPlaying,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                LunarisSplitQsPage.NETWORK -> {
                    Box(
                        modifier =
                            pageModifier.motionEventSpy { event ->
                                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                                    onScrollGestureStart?.invoke(networkScrollState)
                                }
                            }
                    ) {
                        AxQsMixedGrid(
                            headerInOverlay = true,
                            viewModel = viewModel,
                            toolbarViewModel = toolbarViewModel,
                            axQsViewModel = axQsViewModel,
                            mediaViewModel = mediaViewModel,
                            qqs = false,
                            listening = pageListening,
                            brightnessSliderViewModel = brightnessSliderViewModel,
                            volumeSliderViewModel = volumeSliderViewModel,
                            scrollState = networkScrollState,
                            sharedLayout = false,
                            holdExpandedContent = holdExpandedContent,
                            panelSwipeProgress = panelSwipeProgress,
                            panelSwipeFromQs = panelSwipeFromQs,
                            expansionFromLeft = expansionFromLeft,
                            animationResetSequence = animationResetSequence,
                            layoutOverride = AxQsLayout.QS_NETWORK,
                            centerVertically = true,
                            endPaddingOverride = SplitPageContentEndPadding,
                            modifier =
                                Modifier.fillMaxSize()
                                    .padding(top = safeTopInset + 48.dp, bottom = 16.dp),
                        )
                        SplitPageQuickEditButton(
                            onClick = { onEditPage(AxQsLayout.QS_NETWORK) },
                            modifier =
                                Modifier.align(Alignment.TopEnd)
                                    .padding(
                                        top = safeTopInset + 8.dp,
                                        end = axQsViewModel.spacing.sidePadding.dp,
                                    ),
                        )
                    }
                }
                LunarisSplitQsPage.CUSTOM -> {
                    Box(
                        modifier =
                            pageModifier.motionEventSpy { event ->
                                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                                    onScrollGestureStart?.invoke(customScrollState)
                                }
                            }
                    ) {
                        AxQsMixedGrid(
                            headerInOverlay = true,
                            viewModel = viewModel,
                            toolbarViewModel = toolbarViewModel,
                            axQsViewModel = axQsViewModel,
                            mediaViewModel = mediaViewModel,
                            qqs = false,
                            listening = pageListening,
                            brightnessSliderViewModel = brightnessSliderViewModel,
                            volumeSliderViewModel = volumeSliderViewModel,
                            scrollState = customScrollState,
                            sharedLayout = false,
                            holdExpandedContent = holdExpandedContent,
                            panelSwipeProgress = panelSwipeProgress,
                            panelSwipeFromQs = panelSwipeFromQs,
                            expansionFromLeft = expansionFromLeft,
                            animationResetSequence = animationResetSequence,
                            layoutOverride = AxQsLayout.QS_CUSTOM,
                            centerVertically = true,
                            endPaddingOverride = SplitPageContentEndPadding,
                            modifier =
                                Modifier.fillMaxSize()
                                    .padding(top = safeTopInset + 48.dp, bottom = 16.dp),
                        )
                        SplitPageQuickEditButton(
                            onClick = { onEditPage(AxQsLayout.QS_CUSTOM) },
                            modifier =
                                Modifier.align(Alignment.TopEnd)
                                    .padding(
                                        top = safeTopInset + 8.dp,
                                        end = axQsViewModel.spacing.sidePadding.dp,
                                    ),
                        )
                    }
                }
            }
        }

        LunarisSplitQsPageRail(
            pagerState = pagerState,
            onSelectPage = { targetIndex ->
                if (targetIndex != pagerState.targetPage) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                coroutineScope.launch {
                    pagerState.animateScrollToPage(
                        page = targetIndex,
                        animationSpec =
                            spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = 460f,
                            ),
                    )
                }
            },
            modifier =
                Modifier.align(Alignment.CenterEnd)
                    .padding(end = 4.dp),
        )
    }
}

@Composable
private fun SplitPageQuickEditButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val editDescription = stringResource(R.string.accessibility_quick_settings_edit)
    Box(
        modifier =
            modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(AxTileDefaults.backgroundColor())
                .clickable(role = Role.Button, onClick = onClick)
                .semantics {
                    contentDescription = editDescription
                    role = Role.Button
                },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Edit,
            contentDescription = null,
            tint = ShadeHeader.Colors.textColor,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun LunarisSplitQsPageRail(
    pagerState: PagerState,
    onSelectPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val pageLabels =
        listOf(
            stringResource(R.string.lunaris_qs_page_main),
            stringResource(R.string.lunaris_qs_page_media),
            stringResource(R.string.lunaris_qs_page_network),
            stringResource(R.string.lunaris_qs_page_custom),
        )
    var railHeightPx by remember { mutableIntStateOf(0) }
    val railWidth =
        if (pagerState.currentPage == 0 && !pagerState.isScrollInProgress) 20.dp
        else SplitPageRailWidth

    Column(
        modifier =
            modifier
                .width(railWidth)
                .onSizeChanged { railHeightPx = it.height }
                .pointerInput(pagerState, view) {
                    awaitEachGesture {
                        val down =
                            awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        try {
                            var lastTarget = -1
                            fun indexForY(y: Float): Int {
                                if (railHeightPx <= 0) return 0
                                val fraction = (y / railHeightPx.toFloat()).coerceIn(0f, 0.999f)
                                return (fraction * LunarisSplitQsPage.entries.size)
                                    .toInt()
                                    .coerceIn(0, LunarisSplitQsPage.entries.lastIndex)
                            }
                            var dragged = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) {
                                    if (!dragged) {
                                        onSelectPage(indexForY(down.position.y))
                                    }
                                    break
                                }
                                if (abs(change.position.y - down.position.y) > viewConfiguration.touchSlop) {
                                    dragged = true
                                }
                                if (dragged) {
                                    change.consume()
                                    val target = indexForY(change.position.y)
                                    if (target != lastTarget) {
                                        lastTarget = target
                                        onSelectPage(target)
                                    }
                                }
                            }
                        } finally {
                            view.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                }
                .graphicsLayer {
                    val exactPage = pagerState.currentPage + pagerState.currentPageOffsetFraction
                    val visibility = exactPage.coerceIn(0f, 1f)
                    alpha = lerp(0.42f, 1f, visibility)
                },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
    ) {
        LunarisSplitQsPage.entries.forEach { page ->
            val label = pageLabels[page.index]
            val isSelected = pagerState.currentPage == page.index
            Box(
                modifier =
                    Modifier.size(34.dp)
                        .graphicsLayer {
                            val exactPage =
                                pagerState.currentPage + pagerState.currentPageOffsetFraction
                            val distance = abs(exactPage - page.index).coerceIn(0f, 1f)
                            val focus = FastOutSlowInEasing.transform(1f - distance)
                            val onMain = (1f - exactPage.coerceIn(0f, 1f))
                            val scale = lerp(0.76f, 1.48f, focus)
                            scaleX = scale
                            scaleY = scale
                            alpha =
                                if (page.index == 0) {
                                    lerp(0.50f, 1f, focus) * lerp(1f, 0.25f, onMain)
                                } else {
                                    lerp(0.45f, 1f, focus)
                                }
                            translationX = lerp(0f, -5.dp.toPx(), focus)
                        }
                        .semantics {
                            contentDescription = label
                            role = Role.Tab
                            selected = isSelected
                        },
                contentAlignment = Alignment.Center,
            ) {
                when (page) {
                    LunarisSplitQsPage.MAIN ->
                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    LunarisSplitQsPage.MEDIA ->
                        Icon(
                            imageVector = Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    LunarisSplitQsPage.NETWORK ->
                        Icon(
                            imageVector = Icons.Filled.WifiTethering,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    LunarisSplitQsPage.CUSTOM ->
                        Box(
                            modifier =
                                Modifier.size(15.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                        )
                }
            }
        }
    }
}
