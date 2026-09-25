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
import uk.co.maybeitssoftware.londondockcompass.data.riderPosition
import uk.co.maybeitssoftware.londondockcompass.domain.RankedDock
import uk.co.maybeitssoftware.londondockcompass.domain.formatDistance
import uk.co.maybeitssoftware.londondockcompass.domain.Availability
import uk.co.maybeitssoftware.londondockcompass.domain.nearestDocks

/**
 * The nearest usable dock, on the watch face.
 *
 * This is the surface that earns the app its place: a glance at the time also answers "is there a
 * bike near me", with no launching, no swiping and no waiting for a fix.
 *
 * The small slots have room for one figure, and it is bikes: from a watch face the question is
 * nearly always whether you can start a ride. The long slot, which has the room, carries all three.
 */
class NearestDockComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        render(
            type,
            availability = Availability(12, 3, 9, 7, 20, 0L),
            distanceMetres = 140,
            name = "Craven Street, Strand"
        )

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val nearest = nearestDock()
            ?: return render(request.complicationType, availability = null, distanceMetres = null, name = null)

        return render(
            type = request.complicationType,
            availability = nearest.dock.availability,
            distanceMetres = nearest.distanceMetres,
            name = nearest.name
        )
    }

    /** The nearest dock with a bike in it, falling back to the nearest one at all. */
    private suspend fun nearestDock(): RankedDock? {
        val here = riderPosition(this) ?: return null
        val snapshot = DockRepository(this).docksNear(here)
        val ranked = nearestDocks(here, snapshot.docks)
        return ranked.firstOrNull { (it.dock.availability?.bikes ?: 1) > 0 } ?: ranked.firstOrNull()
    }

    private fun render(
        type: ComplicationType,
        availability: Availability?,
        distanceMetres: Int?,
        name: String?
    ): ComplicationData? {
        val count = availability?.bikes
        val capacity = availability?.totalDocks
        val countText = count?.toString() ?: "–"
        val distanceText = distanceMetres?.let { formatDistance(it) } ?: "?"
        val allThree = availability?.let {
            "${it.bikes} bikes · ${it.eBikes} e · ${it.emptyDocks} spaces"
        }
        val spoken = when {
            availability == null || name == null -> "Dock availability unavailable"
            else -> "${availability.describeAll()} at $name, $distanceText away"
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
                text = plain("${allThree ?: "No live data"} · $distanceText"),
                contentDescription = plain(spoken)
            )
                .setTitle(plain(name ?: "Nearest dock"))
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
