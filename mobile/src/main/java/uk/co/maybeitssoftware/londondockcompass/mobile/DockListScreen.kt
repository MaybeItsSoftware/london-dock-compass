package uk.co.maybeitssoftware.londondockcompass.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import uk.co.maybeitssoftware.londondockcompass.domain.Destination
import uk.co.maybeitssoftware.londondockcompass.domain.RankedDock
import uk.co.maybeitssoftware.londondockcompass.domain.RideMode
import uk.co.maybeitssoftware.londondockcompass.domain.formatDistance
import uk.co.maybeitssoftware.londondockcompass.mobile.theme.MicroLabel
import uk.co.maybeitssoftware.londondockcompass.mobile.theme.Status

/** 8px for cards and panels, 6px for chips — the one radius scale, no values in between. */
private val CardRadius = RoundedCornerShape(8.dp)

@Composable
fun DockListScreen(
    state: DockListUiState,
    onSelectMode: (RideMode) -> Unit,
    onToggleFavourite: (Int) -> Unit,
    onPinDestination: (RankedDock) -> Unit,
    onClearDestination: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Header(state = state, onSelectMode = onSelectMode)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        val message = state.statusMessage
        if (message != null) {
            StatusPanel(message)
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            state.destination?.let { destination ->
                item(key = "destination") {
                    SectionLabel("RIDING TO")
                    DestinationCard(
                        destination = destination,
                        ranked = (state.docks + state.savedDocks)
                            .firstOrNull { it.id == destination.dockId },
                        mode = state.mode,
                        onClear = onClearDestination
                    )
                }
            }

            item(key = "nearby-label") { SectionLabel("NEAREST") }
            items(state.docks, key = { "near-${it.id}" }) { dock ->
                DockRow(
                    dock = dock,
                    mode = state.mode,
                    isFavourite = dock.id in state.favourites,
                    isDestination = state.destination?.dockId == dock.id,
                    onToggleFavourite = { onToggleFavourite(dock.id) },
                    onPin = { onPinDestination(dock) }
                )
            }

            if (state.savedDocks.isNotEmpty()) {
                item(key = "saved-label") { SectionLabel("SAVED") }
                items(state.savedDocks, key = { "saved-${it.id}" }) { dock ->
                    DockRow(
                        dock = dock,
                        mode = state.mode,
                        isFavourite = dock.id in state.favourites,
                        isDestination = state.destination?.dockId == dock.id,
                        onToggleFavourite = { onToggleFavourite(dock.id) },
                        onPin = { onPinDestination(dock) }
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(state: DockListUiState, onSelectMode: (RideMode) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "London Dock Compass",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            FreshnessLabel(state)
        }
        Spacer(Modifier.height(10.dp))
        // The mode chips are the whole idea: they change what "nearest" means.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RideMode.entries.forEach { mode ->
                ModeChip(
                    mode = mode,
                    selected = mode == state.mode,
                    onClick = { onSelectMode(mode) }
                )
            }
        }
    }
}

@Composable
private fun ModeChip(mode: RideMode, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            // 44dp of hit target around a chip that stays its painted size.
            .height(44.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "${mode.label}${if (selected) ", selected" else ""}" },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .background(
                    if (selected) scheme.onBackground else Color.Transparent,
                    CircleShape
                )
                .border(
                    1.dp,
                    if (selected) scheme.onBackground else scheme.outline,
                    CircleShape
                )
                .padding(horizontal = 14.dp, vertical = 7.dp)
        ) {
            Text(
                text = mode.label,
                style = MicroLabel,
                color = if (selected) scheme.background else scheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MicroLabel,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

@Composable
private fun DockRow(
    dock: RankedDock,
    mode: RideMode,
    isFavourite: Boolean,
    isDestination: Boolean,
    onToggleFavourite: () -> Unit,
    onPin: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val countColour = Status.forCount(dock.count)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(scheme.surface, CardRadius)
            .border(1.dp, if (isDestination) Status.Azure else scheme.outlineVariant, CardRadius)
            .clickable(onClick = onPin)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = dock.describe(mode)
            }
    ) {
        // The count leads: it is the thing that decides whether this dock is any use.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(46.dp)
        ) {
            Text(
                text = dock.count?.toString() ?: "–",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = countColour
            )
            Text(
                text = if (dock.count == null) "NO DATA" else mode.label,
                style = MicroLabel,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = dock.name,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
                maxLines = 2
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatDistance(dock.distanceMetres),
                    style = MicroLabel,
                    color = scheme.onSurfaceVariant
                )
                if (!dock.isUsable) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = mode.exhaustedLabel,
                        style = MicroLabel,
                        color = Status.Raspberry
                    )
                }
                if (isDestination) {
                    Spacer(Modifier.width(6.dp))
                    Text(text = "DESTINATION", style = MicroLabel, color = Status.Azure)
                }
            }
        }

        IconAction(
            favourite = isFavourite,
            name = dock.name,
            onClick = onToggleFavourite
        )
    }
}

@Composable
private fun IconAction(favourite: Boolean, name: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = if (favourite) "Remove $name from saved" else "Save $name"
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (favourite) Icons.Filled.Star else Icons.Outlined.StarBorder,
            contentDescription = null,
            tint = if (favourite) Status.Amber else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(20.dp)
                .clearAndSetSemantics { }
        )
    }
}

@Composable
private fun DestinationCard(
    destination: Destination,
    ranked: RankedDock?,
    mode: RideMode,
    onClear: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    // Spaces are what a destination is judged on, whatever mode the deck is in — you are going
    // there to end a journey.
    val spaces = ranked?.dock?.availability?.emptyDocks
    val colour = Status.forCount(spaces)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(scheme.surface, CardRadius)
            .border(1.dp, colour, CardRadius)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = when (spaces) {
                    null -> "Riding to ${destination.name}, spaces unknown"
                    else -> "Riding to ${destination.name}, $spaces spaces"
                }
            }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(46.dp)
        ) {
            Text(
                text = spaces?.toString() ?: "–",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = colour
            )
            Text(text = "SPACES", style = MicroLabel, color = scheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = destination.name,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
                maxLines = 2
            )
            ranked?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = formatDistance(it.distanceMetres),
                    style = MicroLabel,
                    color = scheme.onSurfaceVariant
                )
            }
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .clickable(onClick = onClear)
                .semantics { contentDescription = "Unpin ${destination.name}" },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.PushPin,
                contentDescription = null,
                tint = Status.Azure,
                modifier = Modifier
                    .size(20.dp)
                    .clearAndSetSemantics { }
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        text = "Pinned here and on your watch",
        style = MicroLabel,
        color = scheme.onSurfaceVariant
    )
}

/** Only speaks up when there is something to distrust. Silence means live and current. */
@Composable
private fun FreshnessLabel(state: DockListUiState) {
    val label = when (state.source) {
        uk.co.maybeitssoftware.londondockcompass.data.DockSource.BUNDLED -> "NO LIVE DATA"
        uk.co.maybeitssoftware.londondockcompass.data.DockSource.CACHED -> "CACHED"
        uk.co.maybeitssoftware.londondockcompass.data.DockSource.LIVE -> null
    } ?: return
    Text(text = label, style = MicroLabel, color = Status.Amber)
}

@Composable
private fun StatusPanel(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Spoken description of a row.
 *
 * The watch says which way to turn because it has a compass and you are moving. A phone list is
 * read standing still, so distance and availability are what matter.
 */
internal fun RankedDock.describe(mode: RideMode): String {
    val available = count
    val availability = when {
        available == null -> "availability unknown"
        available <= 0 -> mode.exhaustedLabel.lowercase()
        else -> mode.describe(available)
    }
    return "$name, ${formatDistance(distanceMetres)} away, $availability"
}
