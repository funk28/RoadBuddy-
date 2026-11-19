package com.roadbuddy.feature.hazards

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.io.IOException

private val MapColor = Color(0xFFEEF1EC)
private val RoadColor = Color.White
private val ParkColor = Color(0xFFD8E9D1)
private val InkColor = Color(0xFF191D24)
private val SecondaryTextColor = Color(0xFF5D687A)
private val OutlineColor = Color(0xFFD8DFE8)
private val SevereColor = Color(0xFFED454B)
private val ModerateColor = Color(0xFFFFA522)
private val MinorColor = Color(0xFF2D9B6F)
private val SelectedFilterColor = Color(0xFF102D4C)
private val LinkColor = Color(0xFF1E6BE3)

private enum class HazardsFilter(val label: String, val filters: HazardFilters) {
    ALL("All", HazardFilters()),
    MINE("Mine", HazardFilters(mine = true)),
    SEVERE("Severe only", HazardFilters(severity = HazardSeverity.SEVERE)),
}

private fun HazardFilters.matches(hazard: Hazard): Boolean =
    (!mine || hazard.reportedByMe) && (severity == null || hazard.severity == severity)

@Composable
fun HazardsRoute(repository: HazardsRepository) {
    var hazards by remember { mutableStateOf(emptyList<Hazard>()) }
    var selectedFilter by remember { mutableStateOf(HazardsFilter.ALL) }
    var selectedHazard by remember { mutableStateOf<Hazard?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var hasLoaded by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var retryCount by remember { mutableIntStateOf(0) }
    var pendingUpdates by remember { mutableStateOf(emptySet<String>()) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(repository, selectedFilter, retryCount) {
        isLoading = true
        loadFailed = false
        try {
            hazards = repository.getHazards(selectedFilter.filters)
        } catch (_: IOException) {
            loadFailed = true
        } finally {
            isLoading = false
            hasLoaded = true
        }
    }

    fun applyChange(hazard: Hazard, change: HazardChange) {
        if (hazard.id in pendingUpdates) return
        selectedHazard = null
        coroutineScope.launch {
            val previousHazard = hazards.firstOrNull { it.id == hazard.id }
            val previousIndex = hazards.indexOfFirst { it.id == hazard.id }
            pendingUpdates = pendingUpdates + hazard.id
            hazards = when (change) {
                is HazardChange.SetSeverity -> hazards.mapNotNull {
                    val updated = if (it.id == hazard.id) it.copy(severity = change.severity) else it
                    updated.takeIf { selectedFilter.filters.matches(it) }
                }
                HazardChange.MarkRemoved -> hazards.filterNot { it.id == hazard.id }
            }
            try {
                val update = repository.updateHazard(hazard.id, change)
                if (update.id != hazard.id) throw IOException("Hazard update returned a different id")
                hazards = if (update.isRemoved) {
                    hazards.filterNot { it.id == update.id }
                } else {
                    hazards.mapNotNull {
                        val updated = if (it.id == update.id) it.copy(severity = update.severity) else it
                        updated.takeIf { selectedFilter.filters.matches(it) }
                    }
                }
            } catch (_: IOException) {
                val restored = hazards.filterNot { it.id == hazard.id }.toMutableList()
                if (previousHazard != null && selectedFilter.filters.matches(previousHazard)) {
                    restored.add(previousIndex.coerceIn(0, restored.size), previousHazard)
                }
                hazards = restored
                snackbarHostState.showSnackbar("Couldn't save change")
            } finally {
                pendingUpdates = pendingUpdates - hazard.id
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MapColor),
    ) {
        MapBackdrop(
            hazards = hazards,
            onHazardClick = { selectedHazard = it },
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 18.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HazardsFilter.entries.forEach { filter ->
                FilterPill(
                    label = filter.label,
                    selected = selectedFilter == filter,
                    onClick = { selectedFilter = filter },
                )
            }
        }

        if (loadFailed) {
            LoadErrorBanner(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(start = 16.dp, top = 94.dp, end = 16.dp),
                onRetry = { retryCount++ },
            )
        }

        HazardsBottomSheet(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.35f),
            hazards = hazards,
            isLoading = isLoading,
            hasLoaded = hasLoaded,
            onHazardClick = { selectedHazard = it },
        )

        Surface(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = maxWidth * 0.08f, y = maxHeight * 0.16f)
                .size(44.dp),
            shape = CircleShape,
            color = Color(0xFF0875E1),
            border = androidx.compose.foundation.BorderStroke(3.dp, Color.White),
            shadowElevation = 4.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("S", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    selectedHazard?.let { hazard ->
        HazardActionsDialog(
            hazard = hazards.firstOrNull { it.id == hazard.id } ?: hazard,
            isSaving = hazard.id in pendingUpdates,
            onDismiss = { selectedHazard = null },
            onSaveSeverity = { severity ->
                applyChange(hazard, HazardChange.SetSeverity(severity))
            },
            onMarkNotHazard = { applyChange(hazard, HazardChange.MarkRemoved) },
        )
    }
}

@Composable
private fun FilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = CircleShape
    Text(
        text = label,
        color = if (selected) Color.White else InkColor,
        fontSize = 14.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) SelectedFilterColor else Color.White)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 17.dp, vertical = 9.dp)
            .semantics { contentDescription = "$label filter" },
    )
}

@Composable
private fun MapBackdrop(
    hazards: List<Hazard>,
    onHazardClick: (Hazard) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(MapColor)
            val verticalRoadWidth = 12.dp.toPx()
            val horizontalRoadWidth = 12.dp.toPx()
            listOf(0.18f, 0.48f, 0.78f).forEach { fraction ->
                drawRect(
                    color = RoadColor,
                    topLeft = Offset(size.width * fraction - verticalRoadWidth / 2f, 0f),
                    size = Size(verticalRoadWidth, size.height),
                )
            }
            listOf(0.34f, 0.55f, 0.78f).forEach { fraction ->
                drawRect(
                    color = RoadColor,
                    topLeft = Offset(0f, size.height * fraction - horizontalRoadWidth / 2f),
                    size = Size(size.width, horizontalRoadWidth),
                )
            }
            drawRoundRect(
                color = ParkColor,
                topLeft = Offset(size.width * 0.56f, size.height * 0.25f),
                size = Size(size.width * 0.22f, size.height * 0.15f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(18.dp.toPx()),
            )
        }

        MapStreetLabel("RIVER RD", modifier = Modifier.offset(maxWidth * 0.30f, maxHeight * 0.31f))
        MapStreetLabel("PINE AVE", modifier = Modifier.offset(maxWidth * 0.22f, maxHeight * 0.58f))
        MapStreetLabel("OAK ST", modifier = Modifier.offset(maxWidth * 0.82f, maxHeight * 0.58f))
        MapStreetLabel("4TH ST", modifier = Modifier.offset(maxWidth * 0.31f, maxHeight * 0.72f))

        hazards.forEach { hazard ->
            val x = ((hazard.longitude.toFloat() + 86.923f) / 0.004f).coerceIn(0.12f, 0.88f)
            val y = (1f - (hazard.latitude.toFloat() - 40.4215f) / 0.003f).coerceIn(0.23f, 0.87f)
            HazardMapPin(
                hazard = hazard,
                modifier = Modifier.offset(
                    x = maxWidth * x - 17.dp,
                    y = maxHeight * y - 17.dp,
                ),
                onClick = { onHazardClick(hazard) },
            )
        }

    }
}

@Composable
private fun MapStreetLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        color = Color(0xFF7F8980),
        fontSize = 8.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun HazardMapPin(
    hazard: Hazard,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val markerColor = hazard.severity.color
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color.White)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "${hazard.severity.label} ${hazard.type}" },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(27.dp),
            shape = CircleShape,
            color = markerColor,
        ) {
            Box(contentAlignment = Alignment.Center) {
                androidx.compose.foundation.Canvas(
                    modifier = Modifier.size(15.dp),
                ) {
                    val triangle = Path().apply {
                        moveTo(size.width / 2f, 0f)
                        lineTo(size.width, size.height)
                        lineTo(0f, size.height)
                        close()
                    }
                    drawPath(triangle, color = Color.White)
                    drawPath(
                        triangle,
                        color = Color.White,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                    drawLine(
                        color = markerColor,
                        start = Offset(size.width / 2f, size.height * 0.34f),
                        end = Offset(size.width / 2f, size.height * 0.68f),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                    drawCircle(
                        color = markerColor,
                        radius = 0.8.dp.toPx(),
                        center = Offset(size.width / 2f, size.height * 0.82f),
                    )
                }
            }
        }
    }
}

@Composable
private fun HazardsBottomSheet(
    modifier: Modifier,
    hazards: List<Hazard>,
    isLoading: Boolean,
    hasLoaded: Boolean,
    onHazardClick: (Hazard) -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = Color.White,
        shadowElevation = 12.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 9.dp, bottom = 10.dp)
                    .size(width = 38.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE0E4EA)),
            )
            Text(
                text = if (hazards.size == 1) {
                    "1 hazard on or near your routes"
                } else {
                    "${hazards.size} hazards on or near your routes"
                },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                color = InkColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )

            when {
                isLoading && hazards.isEmpty() -> {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        HazardCardPlaceholder()
                        HazardCardPlaceholder()
                    }
                }
                hazards.isEmpty() && hasLoaded -> {
                    Text(
                        text = "No hazards match this filter.",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = SecondaryTextColor,
                        fontSize = 14.sp,
                    )
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (isLoading) {
                            item(key = "loading") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Text("Updating hazards…", color = SecondaryTextColor, fontSize = 12.sp)
                                }
                            }
                        }
                        items(hazards, key = { it.id }) { hazard ->
                            HazardCard(hazard = hazard, onClick = { onHazardClick(hazard) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HazardCardPlaceholder() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, OutlineColor),
    ) {
        Column(
            modifier = Modifier.padding(start = 40.dp, top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(
                Modifier
                    .width(176.dp)
                    .height(13.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFE9EDF2)),
            )
            Box(
                Modifier
                    .width(128.dp)
                    .height(11.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFF0F2F5)),
            )
        }
    }
}

@Composable
private fun HazardCard(hazard: Hazard, onClick: () -> Unit) {
    val accent = hazard.severity.color
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "${hazard.type}, ${hazard.severity.label}, edit hazard" },
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, OutlineColor),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, end = 10.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
            Spacer(Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${hazard.type.toUiName()} · ${hazard.severity.label} · ${hazard.distanceMeters.toMiles()} mi",
                    color = InkColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = listOfNotNull(
                        hazard.locationName ?: "Nearby road",
                        if (hazard.reportedByMe) "reported by you" else null,
                    ).joinToString(" · "),
                    color = SecondaryTextColor,
                    fontSize = 13.sp,
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (hazard.severity == HazardSeverity.SEVERE) {
                        "${hazard.sightingCount} ${if (hazard.sightingCount == 1) "driver" else "drivers"} alerted"
                    } else {
                        "${hazard.sightingCount} ${if (hazard.sightingCount == 1) "confirmation" else "confirmations"}"
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(accent.copy(alpha = 0.13f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    color = if (hazard.severity == HazardSeverity.SEVERE) {
                        Color(0xFFB32D35)
                    } else {
                        Color(0xFF875500)
                    },
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            androidx.compose.material3.Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = Color(0xFF96A2B3),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun LoadErrorBanner(
    modifier: Modifier,
    onRetry: () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.White,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 3.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF1C7C7)),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Couldn't load hazards",
                modifier = Modifier.weight(1f),
                color = Color(0xFFB3261E),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            TextButton(onClick = onRetry) {
                Text("Retry", color = LinkColor, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun HazardActionsDialog(
    hazard: Hazard,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSaveSeverity: (HazardSeverity) -> Unit,
    onMarkNotHazard: () -> Unit,
) {
    var selectedSeverity by remember(hazard.id, hazard.severity) {
        mutableStateOf(hazard.severity)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(hazard.type.toUiName()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "${hazard.locationName ?: "Nearby road"} · ${hazard.distanceMeters.toMiles()} mi",
                    color = SecondaryTextColor,
                    fontSize = 14.sp,
                )
                Text("Edit severity", color = InkColor, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    HazardSeverity.entries.forEach { severity ->
                        FilterChip(
                            selected = selectedSeverity == severity,
                            onClick = { selectedSeverity = severity },
                            enabled = !isSaving,
                            label = { Text(severity.label, fontSize = 12.sp) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSaveSeverity(selectedSeverity) },
                enabled = selectedSeverity != hazard.severity && !isSaving,
            ) {
                Text("Save change")
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onMarkNotHazard, enabled = !isSaving) {
                    Text("Not a hazard", color = Color(0xFFB3261E))
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
    )
}

private val HazardSeverity.color: Color
    get() = when (this) {
        HazardSeverity.MINOR -> MinorColor
        HazardSeverity.MODERATE -> ModerateColor
        HazardSeverity.SEVERE -> SevereColor
    }

private fun String.toUiName(): String =
    replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

private fun Double.toMiles(): String =
    String.format(java.util.Locale.US, "%.1f", this / 1609.344)
