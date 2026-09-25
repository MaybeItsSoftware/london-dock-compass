package uk.co.maybeitssoftware.londondockcompass.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import uk.co.maybeitssoftware.londondockcompass.domain.Purpose
import uk.co.maybeitssoftware.londondockcompass.domain.TIGHT_THRESHOLD
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.co.maybeitssoftware.londondockcompass.domain.Destination
import uk.co.maybeitssoftware.londondockcompass.domain.RankedDock
import uk.co.maybeitssoftware.londondockcompass.domain.RideMode
import uk.co.maybeitssoftware.londondockcompass.domain.formatDistance
import uk.co.maybeitssoftware.londondockcompass.mobile.theme.MicroLabel
import uk.co.maybeitssoftware.londondockcompass.mobile.theme.Status

/** 8px for cards and panels, 6px for chips — the one radius scale, no values in between. */
private val CardRadius = RoundedCornerShape(8.dp)
private val ControlRadius = RoundedCornerShape(6.dp)

@Composable
fun DockListScreen(
    state: DockListUiState,
    onSelectSort: (RideMode?) -> Unit,
    onQueryChanged: (String) -> Unit,
    onToggleFavourite: (Int) -> Unit,
    onPinDestination: (RankedDock, Purpose) -> Unit,
    onSwitchToAlternative: () -> Unit,
    onClearDestination: () -> Unit
) {
    // The dock whose pin sheet is open. A tap only asks; the sheet says what the pin is for.
    var pinning by remember { mutableStateOf<RankedDock?>(null) }
    pinning?.let { dock ->
        PinSheet(
            dock = dock,
            isDestination = state.destination?.dockId == dock.id,
            onPin = { purpose -> onPinDestination(dock, purpose); pinning = null },
            onUnpin = { onClearDestination(); pinning = null },
            onDismiss = { pinning = null }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Back leaves search before it leaves the app.
        BackHandler(enabled = state.isSearching) { onQueryChanged("") }
        Header(state = state, onSelectSort = onSelectSort, onQueryChanged = onQueryChanged)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (state.isSearching) {
            SearchResults(
                state = state,
                onToggleFavourite = onToggleFavourite,
                onPin = { pinning = it }
            )
            return@Column
        }

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
                    SectionLabel(
                        when (destination.purpose) {
                            Purpose.PICK_UP -> "GETTING A BIKE AT"
                            Purpose.DROP_OFF -> "RIDING TO"
                        }
                    )
                    DestinationCard(
                        destination = destination,
                        ranked = state.destinationDock,
                        alternative = state.alternative,
                        onSwitch = onSwitchToAlternative,
                        onClear = onClearDestination
                    )
                }
            }

            item(key = "nearby-label") {
                SectionLabel(
                    when (val sort = state.sort) {
                        null -> "NEAREST"
                        else -> "NEAREST WITH ${sort.label}"
                    }
                )
            }
            items(state.docks, key = { "near-${it.id}" }) { dock ->
                DockRow(
                    dock = dock,
                    isFavourite = dock.id in state.favourites,
                    isDestination = state.destination?.dockId == dock.id,
                    onToggleFavourite = { onToggleFavourite(dock.id) },
                    onPin = { pinning = dock }
                )
            }

            if (state.savedDocks.isNotEmpty()) {
                item(key = "saved-label") { SectionLabel("SAVED") }
                items(state.savedDocks, key = { "saved-${it.id}" }) { dock ->
                    DockRow(
                        dock = dock,
                        isFavourite = dock.id in state.favourites,
                        isDestination = state.destination?.dockId == dock.id,
                        onToggleFavourite = { onToggleFavourite(dock.id) },
                        onPin = { pinning = dock }
                    )
                }
            }
        }
    }
}

/**
 * Every dock in London that matches, for pinning somewhere you are not yet near.
 *
 * Tapping a row pins it, exactly as on the nearest list — and the pin follows you to the watch.
 */
@Composable
private fun SearchResults(
    state: DockListUiState,
    onToggleFavourite: (Int) -> Unit,
    onPin: (RankedDock) -> Unit
) {
    if (state.searchResults.isEmpty()) {
        StatusPanel("No docks match “${state.query.trim()}”")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(key = "results-label") { SectionLabel("TAP TO RIDE HERE") }
        items(state.searchResults, key = { "found-${it.id}" }) { dock ->
            DockRow(
                dock = dock,
                isFavourite = dock.id in state.favourites,
                isDestination = state.destination?.dockId == dock.id,
                showDistance = state.hasPosition,
                onToggleFavourite = { onToggleFavourite(dock.id) },
                onPin = { onPin(dock) }
            )
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChanged: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(scheme.surface, ControlRadius)
            .border(1.dp, scheme.outline, ControlRadius)
            .padding(start = 12.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    text = "Find a dock anywhere",
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp)
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChanged,
                singleLine = true,
                // 16sp or larger: anything smaller and some keyboards zoom the page on focus.
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 16.sp,
                    color = scheme.onSurface
                ),
                cursorBrush = SolidColor(Status.Azure),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Search
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Search docks by name" }
            )
        }
        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clickable { onQueryChanged("") }
                    .semantics { contentDescription = "Clear search" },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(18.dp)
                        .clearAndSetSemantics { }
                )
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

@Composable
private fun Header(
    state: DockListUiState,
    onSelectSort: (RideMode?) -> Unit,
    onQueryChanged: (String) -> Unit
) {
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
        // Every row shows all three figures, so these only reorder: plain distance, or docks that
        // have what you are after first. A full dock is nearest of all and no use for parking.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (listOf(null) + RideMode.entries).forEach { sort ->
                SortChip(
                    label = sort?.label ?: "NEAREST",
                    selected = sort == state.sort,
                    onClick = { onSelectSort(sort) }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        SearchField(query = state.query, onQueryChanged = onQueryChanged)
    }
}

@Composable
private fun SortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            // 44dp of hit target around a chip that stays its painted size.
            .height(44.dp)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "Sort by ${label.lowercase()}${if (selected) ", selected" else ""}"
            },
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
                text = label,
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
    isFavourite: Boolean,
    isDestination: Boolean,
    onToggleFavourite: () -> Unit,
    onPin: () -> Unit,
    showDistance: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    val availability = dock.dock.availability

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(scheme.surface, CardRadius)
            .border(1.dp, if (isDestination) Status.Azure else scheme.outlineVariant, CardRadius)
            .clickable(onClick = onPin)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = dock.describe(showDistance)
            }
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = dock.name,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
                maxLines = 2
            )
            Spacer(Modifier.height(6.dp))
            // All three figures, always: whether you want a bike or a space, it is already here.
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (availability == null) {
                    Text(text = "NO LIVE DATA", style = MicroLabel, color = scheme.onSurfaceVariant)
                } else {
                    Count(availability.bikes, "BIKES")
                    Count(availability.eBikes, "E-BIKES")
                    Count(availability.emptyDocks, "SPACES")
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showDistance) {
                    Text(
                        text = formatDistance(dock.distanceMetres),
                        style = MicroLabel,
                        color = scheme.onSurfaceVariant
                    )
                }
                if (isDestination) {
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
private fun Count(count: Int, label: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Status.forCount(count)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MicroLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 3.dp)
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
    alternative: RankedDock?,
    onSwitch: () -> Unit,
    onClear: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    // Judged on the one figure its purpose depends on, whatever the list is sorted by: bikes when
    // you are walking there to take one, spaces when you are riding there to leave one.
    val purpose = destination.purpose
    val count = ranked?.count
    val colour = Status.forCount(count)
    val unit = purpose.mode

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(scheme.surface, CardRadius)
            .border(1.dp, colour, CardRadius)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = when (count) {
                    null -> "${destination.name}, ${unit.unit} unknown"
                    else -> "${destination.name}, ${unit.describe(count)}"
                }
            }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(56.dp)
        ) {
            Text(
                text = count?.toString() ?: "–",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = colour
            )
            Text(text = unit.label, style = MicroLabel, color = scheme.onSurfaceVariant, maxLines = 1)
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
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = formatDistance(it.distanceMetres),
                        style = MicroLabel,
                        color = scheme.onSurfaceVariant
                    )
                    when {
                        count == null -> Unit
                        count <= 0 -> Text(purpose.exhaustedLabel, style = MicroLabel, color = Status.Raspberry)
                        count < TIGHT_THRESHOLD -> Text(purpose.lowLabel, style = MicroLabel, color = Status.Amber)
                    }
                }
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

    if (alternative != null) {
        Spacer(Modifier.height(8.dp))
        AlternativeRow(alternative = alternative, purpose = purpose, onSwitch = onSwitch)
    }

    Spacer(Modifier.height(4.dp))
    Text(
        text = "Watched while the app is open · synced to your watch",
        style = MicroLabel,
        color = scheme.onSurfaceVariant
    )
}

/**
 * The way out of a failing destination: the nearest dock that would not fail you the same way,
 * one tap to switch to it.
 */
@Composable
private fun AlternativeRow(alternative: RankedDock, purpose: Purpose, onSwitch: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val figure = alternative.dock.availability?.let { purpose.mode.describe(purpose.countIn(it)) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(Status.Amber.copy(alpha = 0.10f), CardRadius)
            .border(1.dp, Status.Amber.copy(alpha = 0.40f), CardRadius)
            .clickable(onClick = onSwitch)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Switch to ${alternative.name}" +
                    listOfNotNull(figure, "${formatDistance(alternative.distanceMetres)} away")
                        .joinToString(prefix = ", ")
            }
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "TRY INSTEAD", style = MicroLabel, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Text(
                text = alternative.name,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
                maxLines = 1
            )
            Text(
                text = listOfNotNull(figure, formatDistance(alternative.distanceMetres))
                    .joinToString(" · ")
                    .uppercase(),
                style = MicroLabel,
                color = scheme.onSurfaceVariant
            )
        }
        Text(text = "SWITCH", style = MicroLabel, color = scheme.onSurface)
    }
}

/**
 * What a pin is for. The same dock is a place to get a bike if you are on foot and a place to
 * leave one if you are riding, and each watches a different figure — so the app asks rather than
 * guessing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinSheet(
    dock: RankedDock,
    isDestination: Boolean,
    onPin: (Purpose) -> Unit,
    onUnpin: () -> Unit,
    onDismiss: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
        containerColor = scheme.surface,
        tonalElevation = 0.dp
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
        ) {
            Text(text = dock.name, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
            dock.dock.availability?.let {
                Text(text = it.describeAll(), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            SheetOption(
                title = Purpose.PICK_UP.action,
                detail = "On foot. Warns you if it runs out of bikes, and finds the nearest one that has them.",
                onClick = { onPin(Purpose.PICK_UP) }
            )
            SheetOption(
                title = Purpose.DROP_OFF.action,
                detail = "Riding. Warns you if it fills up, and finds the nearest dock to it with spaces.",
                onClick = { onPin(Purpose.DROP_OFF) }
            )
            if (isDestination) {
                SheetOption(title = "Unpin", detail = null, onClick = onUnpin)
            }
        }
    }
}

@Composable
private fun SheetOption(title: String, detail: String?, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .border(1.dp, scheme.outlineVariant, CardRadius)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = scheme.onSurface)
        if (detail != null) {
            Spacer(Modifier.height(2.dp))
            Text(text = detail, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
    }
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
internal fun RankedDock.describe(withDistance: Boolean = true): String {
    val availability = dock.availability?.describeAll() ?: "availability unknown"
    val distance = if (withDistance) " ${formatDistance(distanceMetres)} away," else ""
    return "$name,$distance $availability"
}
