package uk.co.maybeitssoftware.londondockcompass.tile

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import uk.co.maybeitssoftware.londondockcompass.data.DockRepository
import uk.co.maybeitssoftware.londondockcompass.data.RiderPreferences
import uk.co.maybeitssoftware.londondockcompass.data.riderPosition
import uk.co.maybeitssoftware.londondockcompass.domain.RankedDock
import uk.co.maybeitssoftware.londondockcompass.domain.RideMode
import uk.co.maybeitssoftware.londondockcompass.domain.TIGHT_THRESHOLD
import uk.co.maybeitssoftware.londondockcompass.domain.rankDocks

/**
 * The three nearest docks, one swipe from the watch face.
 *
 * A tile is the cheapest glance a watch offers — no launch, no fix to wait for, no arrow to line
 * up. For the common case, "which of the docks around me has what I need", it answers the question
 * outright and the app only gets opened when you actually need pointing.
 */
class NearbyDocksTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> = future {
        val mode = RiderPreferences(this).mode
        val docks = nearbyDocks(mode)
        val device = requestParams.deviceConfiguration

        TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            // The counts move constantly; a minute is as stale as this should ever get.
            .setFreshnessIntervalMillis(FRESHNESS_MILLIS)
            .setTileTimeline(
                TimelineBuilders.Timeline.fromLayoutElement(layout(mode, docks, device))
            )
            .build()
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> = future {
        ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build()
    }

    private suspend fun nearbyDocks(mode: RideMode): List<RankedDock> {
        val here = riderPosition(this) ?: return emptyList()
        val snapshot = DockRepository(this).docksNear(here)
        return rankDocks(here, snapshot.docks, mode, limit = ROWS)
    }

    private fun layout(
        mode: RideMode,
        docks: List<RankedDock>,
        device: DeviceParameters
    ): LayoutElementBuilders.LayoutElement {
        val content = LayoutElementBuilders.Column.Builder()
            .setWidth(androidx.wear.protolayout.DimensionBuilders.expand())
            // Anywhere on the tile opens the compass; there is nothing else here to tap.
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder().setClickable(openApp()).build()
            )

        if (docks.isEmpty()) {
            content.addContent(
                Text.Builder(this, "No docks nearby")
                    .setTypography(Typography.TYPOGRAPHY_BODY2)
                    .setColor(argb(CHALK))
                    .build()
            )
        } else {
            docks.take(ROWS).forEach { dock -> content.addContent(row(dock, mode)) }
        }

        return PrimaryLayout.Builder(device)
            .setResponsiveContentInsetEnabled(true)
            .setPrimaryLabelTextContent(
                Text.Builder(this, mode.label)
                    .setTypography(Typography.TYPOGRAPHY_CAPTION3)
                    .setColor(argb(RASPBERRY))
                    .build()
            )
            .setContent(content.build())
            .build()
    }

    /** One dock: how many of the thing you want, how far, and which dock it is. */
    private fun row(dock: RankedDock, mode: RideMode): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Row.Builder()
            .setWidth(androidx.wear.protolayout.DimensionBuilders.expand())
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(
                Text.Builder(this, dock.count?.toString() ?: "–")
                    .setTypography(Typography.TYPOGRAPHY_TITLE3)
                    .setColor(argb(countColour(dock.count)))
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Spacer.Builder()
                    .setWidth(androidx.wear.protolayout.DimensionBuilders.dp(6f))
                    .build()
            )
            .addContent(
                Text.Builder(this, "${dock.distanceMetres}m  ${dock.name.shorten()}")
                    .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                    .setColor(argb(MUTED))
                    .setMaxLines(1)
                    .build()
            )
            .build()

    private fun openApp(): ModifiersBuilders.Clickable =
        ModifiersBuilders.Clickable.Builder()
            .setId("open_compass")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(MAIN_ACTIVITY)
                            .build()
                    )
                    .build()
            )
            .build()

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> =
        CallbackToFutureAdapter.getFuture { completer ->
            val job = scope.launch {
                runCatching { block() }
                    .onSuccess(completer::set)
                    .onFailure(completer::setException)
            }
            completer.addCancellationListener({ job.cancel() }, Runnable::run)
            "dock tile"
        }

    private companion object {
        const val RESOURCES_VERSION = "1"
        const val FRESHNESS_MILLIS = 60_000L
        const val ROWS = 3
        const val MAIN_ACTIVITY =
            "uk.co.maybeitssoftware.londondockcompass.presentation.MainActivity"

        // Tiles are drawn outside Compose, so the palette is repeated as raw ARGB here.
        const val RASPBERRY = 0xFFD62246.toInt()
        const val EMERALD = 0xFF4CC38E.toInt()
        const val AMBER = 0xFFFFBF00.toInt()
        const val CHALK = 0xFFFAF8F4.toInt()
        const val MUTED = 0xFFB6B3BF.toInt()

        fun countColour(count: Int?): Int = when {
            count == null -> MUTED
            count <= 0 -> RASPBERRY
            count < TIGHT_THRESHOLD -> AMBER
            else -> EMERALD
        }

        /** Dock names are long and tiles are narrow; the street is the part that locates it. */
        fun String.shorten(): String = substringBefore(',').take(18)
    }
}
