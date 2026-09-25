package uk.co.maybeitssoftware.londondockcompass.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.HorizontalPageIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PageIndicatorState
import androidx.wear.compose.material.Text
import kotlinx.coroutines.launch
import uk.co.maybeitssoftware.londondockcompass.R
import uk.co.maybeitssoftware.londondockcompass.data.DockSource
import uk.co.maybeitssoftware.londondockcompass.domain.Availability
import uk.co.maybeitssoftware.londondockcompass.domain.DestinationHealth
import uk.co.maybeitssoftware.londondockcompass.domain.Purpose
import uk.co.maybeitssoftware.londondockcompass.domain.RankedDock
import uk.co.maybeitssoftware.londondockcompass.domain.formatDistance
import uk.co.maybeitssoftware.londondockcompass.domain.isStaleAt
import uk.co.maybeitssoftware.londondockcompass.domain.normaliseDegrees
import uk.co.maybeitssoftware.londondockcompass.theme.Brand
import uk.co.maybeitssoftware.londondockcompass.presentation.theme.MicroLabel
import uk.co.maybeitssoftware.londondockcompass.presentation.theme.Palette

/** One swipeable card. The pinned destination, when there is one, always leads. */
private sealed interface Page {
    val ranked: RankedDock?
    val key: Int

    data class Pinned(val state: DestinationState) : Page {
        override val ranked get() = state.ranked
        override val key get() = PINNED_KEY
    }

    data class Nearby(override val ranked: RankedDock) : Page {
        override val key get() = ranked.id
    }

    /** A saved dock you are nowhere near — home, or work, or wherever you are heading. */
    data class Saved(override val ranked: RankedDock) : Page {
        override val key get() = ranked.id
    }
}

private const val PINNED_KEY = -1

@Composable
fun CompassScreen(
    state: CompassUiState,
    /**
     * Read lazily, never as a value.
     *
     * The magnetometer lands fifty samples a second. Taking `heading` as a `Float` parameter meant
     * every one of them invalidated this whole subtree — pager, cards, text, semantics — because
     * the read happened during composition at the call site. As a lambda the read moves to the
     * draw phase, where a rotation costs a layer invalidation and nothing else.
     */
    heading: () -> Float,
    accuracy: CompassAccuracy,
    isAmbient: Boolean,
    onToggleFavourite: (Int) -> Unit,
    onPinDestination: (RankedDock, Purpose) -> Unit,
    onSwitchToAlternative: () -> Unit,
    onClearDestination: () -> Unit,
    onTargetChanged: (RankedDock?) -> Unit
) {
    val message = state.statusMessage
    if (message != null && state.destination?.ranked == null) {
        // Reporting the target is a side effect, so it waits for composition to finish rather than
        // writing to the caller's state mid-pass.
        LaunchedEffect(Unit) { onTargetChanged(null) }
        StatusScreen(message, showSpinnerHint = state.isRefreshing)
        return
    }

    // Destination first, then what is around you, then the saved docks you are heading for.
    val pages = remember(state.destination, state.docks, state.savedDocks) {
        buildList {
            state.destination?.let { add(Page.Pinned(it)) }
            state.docks.forEach { add(Page.Nearby(it)) }
            state.savedDocks.forEach { add(Page.Saved(it)) }
        }
    }
    if (pages.isEmpty()) {
        LaunchedEffect(Unit) { onTargetChanged(null) }
        StatusScreen("No docks nearby", showSpinnerHint = state.isRefreshing)
        return
    }

    // Keyed by dock id, so a dock that shuffles down the distance ranking as you ride takes its
    // page with it instead of swapping the card out from under your eyes.
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val current = pages.getOrNull(pagerState.currentPage)

    LaunchedEffect(current?.key, current?.ranked?.distanceMetres) {
        onTargetChanged(current?.ranked)
    }

    var showActions by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            // The list can shrink between the pager reading its count and rendering a page, so
            // every lookup tolerates an index that has just gone stale.
            key = { index -> pages.getOrNull(index)?.key ?: index },
            modifier = Modifier
                .fillMaxSize()
                .rotaryPager(pagerState)
        ) { index ->
            when (val page = pages.getOrNull(index) ?: return@HorizontalPager) {
                is Page.Pinned -> PinnedPage(
                    state = page.state,
                    heading = heading,
                    isAmbient = isAmbient,
                    onOpenActions = { showActions = true }
                )

                is Page.Nearby -> DockPage(
                    dock = page.ranked,
                    heading = heading,
                    isFavourite = page.ranked.id in state.favourites,
                    isAmbient = isAmbient,
                    onOpenActions = { showActions = true }
                )

                is Page.Saved -> DockCard(
                    dock = page.ranked,
                    heading = heading,
                    isAmbient = isAmbient,
                    eyebrow = "★ SAVED",
                    eyebrowColor = Palette.Amber,
                    onOpenActions = { showActions = true }
                )
            }
        }

        if (!isAmbient) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 2.dp)
            ) {
                if (accuracy == CompassAccuracy.NEEDS_CALIBRATION) {
                    CalibrationHint()
                } else {
                    FreshnessLabel(state)
                }
                Spacer(modifier = Modifier.height(2.dp))
            }

            HorizontalPageIndicator(
                pageIndicatorState = pagerState.asIndicatorState(),
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        if (showActions) {
            current?.ranked?.let { target ->
                val pinnedState = state.destination
                val onPinnedPage = current is Page.Pinned
                DockActions(
                    dock = target,
                    isFavourite = target.id in state.favourites,
                    isDestination = pinnedState?.destination?.dockId == target.id,
                    alternative = pinnedState?.alternative?.takeIf { onPinnedPage },
                    onSwitch = { onSwitchToAlternative(); showActions = false },
                    onFavourite = { onToggleFavourite(target.id); showActions = false },
                    onPin = { purpose -> onPinDestination(target, purpose); showActions = false },
                    onUnpin = { onClearDestination(); showActions = false },
                    onDismiss = { showActions = false }
                )
            }
        }
    }
}

/**
 * The crown scrolls the deck.
 *
 * Wear users reach for the crown before they reach across the screen, and on a bike a rotation you
 * can feel beats a swipe you have to aim.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Modifier.rotaryPager(pagerState: PagerState): Modifier {
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    var accumulated by remember { mutableStateOf(0f) }

    // Focus is what makes the crown reach us at all, but requesting it before the node is placed
    // throws — and a missing crown is not worth crashing a ride over.
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    return this
        .onRotaryScrollEvent { event ->
            accumulated += event.verticalScrollPixels
            val step = when {
                accumulated > ROTARY_STEP_PIXELS -> 1
                accumulated < -ROTARY_STEP_PIXELS -> -1
                else -> 0
            }
            if (step != 0) {
                accumulated = 0f
                val target = (pagerState.currentPage + step)
                    .coerceIn(0, (pagerState.pageCount - 1).coerceAtLeast(0))
                scope.launch { pagerState.animateScrollToPage(target) }
            }
            true
        }
        .focusRequester(focusRequester)
        .focusable()
}

private const val ROTARY_STEP_PIXELS = 40f

@Composable
private fun PagerState.asIndicatorState(): PageIndicatorState {
    val pager = this
    return remember(pager) {
        object : PageIndicatorState {
            override val pageCount get() = pager.pageCount
            override val pageOffset get() = pager.currentPageOffsetFraction
            override val selectedPage get() = pager.currentPage
        }
    }
}

@Composable
private fun DockPage(
    dock: RankedDock,
    heading: () -> Float,
    isFavourite: Boolean,
    isAmbient: Boolean,
    onOpenActions: () -> Unit
) {
    DockCard(
        dock = dock,
        heading = heading,
        isAmbient = isAmbient,
        eyebrow = if (isFavourite) "★ SAVED" else null,
        eyebrowColor = Palette.Amber,
        onOpenActions = onOpenActions
    )
}

/**
 * The dock you are riding to.
 *
 * Distinguished from the passing docks by its eyebrow, which names what it is for and turns amber
 * and then raspberry as the figure that matters runs out — bikes for a pick-up, spaces for a
 * drop-off. Once it is failing, the card also names the nearest dock that would not.
 */
@Composable
private fun PinnedPage(
    state: DestinationState,
    heading: () -> Float,
    isAmbient: Boolean,
    onOpenActions: () -> Unit
) {
    val ranked = state.ranked
    if (ranked == null) {
        StatusScreen("Waiting for ${state.destination.name}", showSpinnerHint = true)
        return
    }
    DockCard(
        dock = ranked,
        heading = heading,
        isAmbient = isAmbient,
        eyebrow = run {
            val purpose = state.destination.purpose
            val base = when (purpose) {
                Purpose.PICK_UP -> "BIKE PICK-UP"
                Purpose.DROP_OFF -> "DESTINATION"
            }
            when (state.health) {
                DestinationHealth.CRITICAL -> "$base · ${purpose.exhaustedLabel}"
                DestinationHealth.TIGHT -> "$base · ${purpose.lowLabel}"
                else -> base
            }
        },
        footer = state.alternative?.let { "TRY ${it.name.shortName()} · ${formatDistance(it.distanceMetres)}" },
        eyebrowColor = when (state.health) {
            DestinationHealth.CRITICAL -> Palette.Raspberry
            DestinationHealth.TIGHT -> Palette.Amber
            else -> Palette.Azure
        },
        onOpenActions = onOpenActions
    )
}

@Composable
private fun DockCard(
    dock: RankedDock,
    heading: () -> Float,
    isAmbient: Boolean,
    eyebrow: String?,
    eyebrowColor: Color,
    onOpenActions: () -> Unit,
    /** One line under the counts, for the destination's suggested alternative. */
    footer: String? = null
) {
    val availability = dock.dock.availability
    // A dock with neither a bike to take nor a space to leave one is no use to anybody.
    val isDeadEnd = availability != null && availability.bikes <= 0 && availability.emptyDocks <= 0

    // The spoken direction has eight buckets, so it changes eight times per turn of the wrist —
    // not fifty times a second. derivedStateOf is what keeps the accessibility node (and this
    // composable) from churning on every sample for a string that has not changed.
    val spoken by remember(dock) {
        derivedStateOf { dock.describe(dock.bearingDegrees - heading()) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(enabled = !isAmbient, onClick = onOpenActions)
            .semantics { contentDescription = spoken },
        contentAlignment = Alignment.Center
    ) {
        CompassArrow(
            rotation = { dock.bearingDegrees - heading() },
            tint = when {
                isAmbient -> Palette.Chalk
                // A dock you cannot use still deserves an arrow, just not an inviting one.
                isDeadEnd -> Palette.Dim
                else -> null
            },
            dimmed = isDeadEnd
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 20.dp)
        ) {
            if (eyebrow != null && !isAmbient) {
                Text(text = eyebrow, style = MicroLabel, color = eyebrowColor)
                Spacer(modifier = Modifier.height(4.dp))
            }

            Text(
                text = formatDistance(dock.distanceMetres),
                style = MaterialTheme.typography.display1.copy(fontWeight = FontWeight.Bold),
                color = Palette.Chalk
            )

            if (!isAmbient) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = dock.name,
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    color = Palette.Muted,
                    maxLines = 2
                )
                Spacer(modifier = Modifier.height(6.dp))
                if (availability == null) {
                    Text(text = "NO LIVE DATA", style = MicroLabel, color = Palette.Muted)
                } else {
                    Counts(availability)
                }
                if (footer != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = footer, style = MicroLabel, color = Palette.Amber, maxLines = 1)
                }
            }
        }
    }
}

/**
 * The needle.
 *
 * Drawn pointing north so the only maths at the call site is "bearing minus heading" — the old
 * drawable pointed left and every rotation carried a ninety degree correction along with it.
 */
@Composable
private fun CompassArrow(rotation: () -> Float, tint: Color?, dimmed: Boolean) {
    Image(
        painter = painterResource(R.drawable.arrow),
        contentDescription = null,
        modifier = Modifier
            .fillMaxSize(0.92f)
            // graphicsLayer with a lambda reads the heading in the draw phase, so a new sample
            // invalidates the layer alone. Modifier.rotate() reads during composition and drags
            // recomposition and layout along with it.
            .graphicsLayer { rotationZ = rotation() }
            .alpha(if (dimmed) 0.35f else 1f)
            .clearAndSetSemantics { },
        colorFilter = tint?.let { ColorFilter.tint(it) }
    )
}

/**
 * Bikes, e-bikes and spaces side by side.
 *
 * All three at once, so whether you are after a bike or somewhere to leave one, the answer is
 * already on the card — no mode to remember to switch on a wrist while riding.
 */
@Composable
private fun Counts(availability: Availability) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Count(availability.bikes, "BIKES")
        Count(availability.eBikes, "E-BIKES")
        Count(availability.emptyDocks, "SPACES")
    }
}

@Composable
private fun Count(count: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.title3.copy(fontWeight = FontWeight.Bold),
            color = countColor(count)
        )
        Text(text = label, style = MicroLabel, color = Palette.Muted)
    }
}

@Composable
private fun StatusScreen(message: String, showSpinnerHint: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Ink),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
                color = Palette.Chalk
            )
            if (showSpinnerHint) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = "SEARCHING", style = MicroLabel, color = Palette.Dim)
            }
        }
    }
}

/** Only speaks up when there is something to distrust. Silence means live and current. */
@Composable
private fun FreshnessLabel(state: CompassUiState) {
    val now = System.currentTimeMillis()
    val observed = state.docks.firstNotNullOfOrNull { it.dock.availability }
    val label = when {
        state.source == DockSource.BUNDLED -> "NO LIVE DATA"
        state.source == DockSource.CACHED -> "CACHED"
        observed?.isStaleAt(now) == true -> "${(now - observed.observedAtMillis) / 60_000} MIN OLD"
        else -> null
    } ?: return

    Text(text = label, style = MicroLabel, color = Palette.Amber)
}

@Composable
private fun CalibrationHint() {
    Text(text = "↻ FIGURE-8 TO CALIBRATE", style = MicroLabel, color = Palette.Amber)
}

@Composable
private fun DockActions(
    dock: RankedDock,
    isFavourite: Boolean,
    isDestination: Boolean,
    alternative: RankedDock?,
    onFavourite: () -> Unit,
    onPin: (Purpose) -> Unit,
    onSwitch: () -> Unit,
    onUnpin: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Scrim)
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        // Scrolls because a failing destination has four actions, and a small round screen does
        // not fit four chips and a title.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 28.dp)
        ) {
            Text(
                text = dock.name,
                style = MicroLabel,
                color = Palette.Muted,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
            if (alternative != null) {
                Chip(
                    label = { Text("Switch to ${alternative.name.shortName()}", maxLines = 1) },
                    onClick = onSwitch,
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (isDestination) {
                Chip(
                    label = { Text("Unpin") },
                    onClick = onUnpin,
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                // On foot for a bike, or on a bike for a space: each watches a different figure.
                Purpose.entries.forEach { purpose ->
                    Chip(
                        label = { Text(purpose.action) },
                        onClick = { onPin(purpose) },
                        colors = ChipDefaults.primaryChipColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Chip(
                label = { Text(if (isFavourite) "Saved ★" else "Save dock") },
                onClick = onFavourite,
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** Dock names are long and a watch is narrow; the street is the part that locates it. */
private fun String.shortName(): String = substringBefore(',').trim()

/**
 * The way in, and — when the platform has stopped listening — the way out.
 *
 * Two states get here. A flat denial is recoverable by asking again. An *approximate* grant is the
 * trap: the rider tapped a button that said yes, the app cannot use what it got, and after a second
 * refusal the system silently ignores further requests. Both states therefore carry a route into
 * app settings, because a screen whose only button does nothing is worse than no screen at all.
 */
@Composable
fun PermissionScreen(
    access: LocationAccess,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val approximate = access == LocationAccess.APPROXIMATE
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Ink),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            if (approximate) {
                Text(text = "APPROXIMATE ONLY", style = MicroLabel, color = Palette.Amber)
            }
            Text(
                text = if (approximate) {
                    "An arrow needs precise location. Approximate puts you somewhere in the borough."
                } else {
                    "Location needed to find nearby docks"
                },
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.body2,
                color = Palette.Chalk
            )
            Chip(
                label = { Text(if (approximate) "Use precise" else "Allow") },
                onClick = onRequest,
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth()
            )
            Chip(
                label = { Text("Open settings") },
                onClick = onOpenSettings,
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private fun countColor(count: Int?): Color = Color(Brand.availabilityColour(count))

/**
 * Spoken description of a dock.
 *
 * Screen reader users get the direction relative to the way they are facing — "ahead and to your
 * right" — because an absolute bearing is no use to anyone who cannot see the arrow.
 */
internal fun RankedDock.describe(relativeBearing: Float): String {
    val availability = dock.availability?.describeAll() ?: "availability unknown"
    return "$name, ${formatDistance(distanceMetres)} ${relativeBearing.asSpokenDirection()}, $availability"
}

internal fun Float.asSpokenDirection(): String {
    val normalised = normaliseDegrees(this)
    return when (((normalised + 22.5f) / 45f).toInt() % 8) {
        0 -> "straight ahead"
        1 -> "ahead and to your right"
        2 -> "to your right"
        3 -> "behind you on the right"
        4 -> "behind you"
        5 -> "behind you on the left"
        6 -> "to your left"
        else -> "ahead and to your left"
    }
}
