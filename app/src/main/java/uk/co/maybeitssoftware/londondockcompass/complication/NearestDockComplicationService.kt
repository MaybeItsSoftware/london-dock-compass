package uk.co.maybeitssoftware.londondockcompass.complication

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import androidx.core.graphics.drawable.IconCompat
import uk.co.maybeitssoftware.londondockcompass.R
import uk.co.maybeitssoftware.londondockcompass.data.DockRepository
import uk.co.maybeitssoftware.londondockcompass.data.RiderPreferences
import uk.co.maybeitssoftware.londondockcompass.data.riderPosition
import uk.co.maybeitssoftware.londondockcompass.domain.RankedDock
import uk.co.maybeitssoftware.londondockcompass.domain.RideMode
import uk.co.maybeitssoftware.londondockcompass.domain.rankDocks
import java.util.Locale

/**
 * The nearest usable dock, on the watch face.
 *
 * This is the surface that earns the app its place: a glance at the time also answers "is there a
 * bike near me", with no launching, no swiping and no waiting for a fix. It respects the mode set
 * in the app, so a rider looking for a space sees spaces.
 */
class NearestDockComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        render(type, mode = RideMode.HIRE, count = 12, distanceMetres = 140, name = "Craven Street, Strand")

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val mode = RiderPreferences(this).mode
        val nearest = nearestDock(mode)
            ?: return render(request.complicationType, mode, count = null, distanceMetres = null, name = null)

        return render(
            type = request.complicationType,
            mode = mode,
            count = nearest.count,
            distanceMetres = nearest.distanceMetres,
            name = nearest.name,
            capacity = nearest.dock.availability?.totalDocks
        )
    }

    private suspend fun nearestDock(mode: RideMode): RankedDock? {
        val here = riderPosition(this) ?: return null
        val snapshot = DockRepository(this).docksNear(here)
        val ranked = rankDocks(here, snapshot.docks, mode)
        // The nearest dock that can actually help, falling back to the nearest one at all.
        return ranked.firstOrNull { it.isUsable } ?: ranked.firstOrNull()
    }

    private fun render(
        type: ComplicationType,
        mode: RideMode,
        count: Int?,
        distanceMetres: Int?,
        name: String?,
        capacity: Int? = null
    ): ComplicationData? {
        val countText = count?.toString() ?: "–"
        val distanceText = distanceMetres?.let { formatDistance(it) } ?: "?"
        val spoken = when {
            count == null || name == null -> "Dock availability unavailable"
            else -> "${mode.describe(count)} at $name, $distanceText away"
        }

        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = plain(countText),
                contentDescription = plain(spoken)
            )
                .setTitle(plain(distanceText))
                .setMonochromaticImage(icon())
                .setTapAction(openApp())
                .build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = plain("${mode.describe(count ?: 0)} · $distanceText"),
                contentDescription = plain(spoken)
            )
                .setTitle(plain(name ?: mode.label))
                .setMonochromaticImage(icon())
                .setTapAction(openApp())
                .build()

            // The ranged form is the honest one: eleven bikes out of a rack of twelve reads very
            // differently from eleven out of sixty, and only the arc shows that.
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = (count ?: 0).toFloat(),
                min = 0f,
                max = (capacity ?: count?.coerceAtLeast(1) ?: 1).toFloat(),
                contentDescription = plain(spoken)
            )
                .setText(plain(countText))
                .setTitle(plain(distanceText))
                .setMonochromaticImage(icon())
                .setTapAction(openApp())
                .build()

            ComplicationType.MONOCHROMATIC_IMAGE -> MonochromaticImageComplicationData.Builder(
                monochromaticImage = icon(),
                contentDescription = plain(spoken)
            )
                .setTapAction(openApp())
                .build()

            else -> null
        }
    }

    private fun plain(text: String): ComplicationText = PlainComplicationText.Builder(text).build()

    private fun icon(): MonochromaticImage = MonochromaticImage.Builder(
        image = IconCompat.createWithResource(this, R.drawable.ic_dock).toIcon(this)
    ).build()

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent().apply {
            component = ComponentName(
                packageName,
                "uk.co.maybeitssoftware.londondockcompass.presentation.MainActivity"
            )
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}

internal fun formatDistance(metres: Int): String =
    if (metres < 1000) "${metres}m"
    else String.format(Locale.getDefault(), "%.1fkm", metres / 1000f)
