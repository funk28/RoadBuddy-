package com.roadbuddy.feature.reports

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

private val ScreenBackground = Color(0xFFF4F6F8)
private val PrimaryText = Color(0xFF171A20)
private val SecondaryText = Color(0xFF667184)
private val DividerColor = Color(0xFFE6E9EE)
private const val CurrentTripId = "t_9"

@Composable
fun ReportsRoute() {
    val reports = remember {
        mutableStateListOf<UserReport>().apply {
            addAll(StubReportsRepository.getReports())
        }
    }
    var selectedReportId by rememberSaveable { mutableStateOf<String?>(null) }
    var showAllReports by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    BackHandler(enabled = selectedReportId != null) {
        selectedReportId = null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val selectedReport = reports.firstOrNull { it.id == selectedReportId }
        if (selectedReport == null) {
            ReportsListScreen(
                reports = if (showAllReports) reports else reports.filter { it.tripId == CurrentTripId },
                showAllReports = showAllReports,
                onShowAllReportsChange = { showAllReports = it },
                onReportClick = { selectedReportId = it.id },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            ReportDetailScreen(
                report = selectedReport,
                onBack = { selectedReportId = null },
                onRemove = {
                    reports.removeAll { it.id == selectedReport.id }
                    selectedReportId = null
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar("Report removed")
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun ReportsListScreen(
    reports: List<UserReport>,
    showAllReports: Boolean,
    onShowAllReportsChange: (Boolean) -> Unit,
    onReportClick: (UserReport) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(ScreenBackground)
            .verticalScroll(rememberScrollState())
            .padding(start = 14.dp, top = 18.dp, end = 14.dp, bottom = 16.dp),
    ) {
        Text(
            text = "My reports",
            color = PrimaryText,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(17.dp))
        ReportScopeSelector(
            showAllReports = showAllReports,
            onSelectionChange = onShowAllReportsChange,
        )
        Spacer(Modifier.height(10.dp))

        if (reports.isEmpty()) {
            Text(
                text = "No reports to show.",
                color = SecondaryText,
                fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 18.dp),
            )
        } else {
            reports.forEach { report ->
                ReportCard(
                    report = report,
                    onClick = { onReportClick(report) },
                )
                Spacer(Modifier.height(9.dp))
            }
        }

        Spacer(Modifier.height(2.dp))
        Text(
            text = "Reports tab is one tap from any screen.",
            color = Color(0xFF8A94A5),
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun ReportScopeSelector(
    showAllReports: Boolean,
    onSelectionChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(Color(0xFFE5E8ED))
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScopeOption(
            label = "This trip",
            selected = !showAllReports,
            onClick = { onSelectionChange(false) },
            modifier = Modifier.weight(1f),
        )
        ScopeOption(
            label = "All reports",
            selected = showAllReports,
            onClick = { onSelectionChange(true) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ScopeOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .then(if (selected) Modifier.shadow(1.dp, RoundedCornerShape(10.dp)) else Modifier)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color.White else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) Color(0xFF20232A) else SecondaryText,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
private fun ReportCard(
    report: UserReport,
    onClick: () -> Unit,
) {
    val severityColor = report.severity.color()
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(13.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFE0E4E9)),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 9.dp, end = 7.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${report.type.titleCase()} · ${report.severity.titleCase()}",
                    color = PrimaryText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(severityColor),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = "${report.locationName} · ${report.capturedTimeLabel()}",
                        color = SecondaryText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(3.dp))
                ReportStatusBadge(report)
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "View report details",
                tint = Color(0xFF9AA7B7),
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun ReportStatusBadge(report: UserReport) {
    val (label, background, foreground) = when (report.badge) {
        ReportBadge.SUBMITTED -> Triple(
            "Submitted · live for drivers",
            Color(0xFFFDEBEC),
            Color(0xFFC63832),
        )
        ReportBadge.MATCHED -> Triple(
            "Matched existing · sighting added (${report.sightingCount})",
            Color(0xFFE5F5EA),
            Color(0xFF228B45),
        )
        ReportBadge.EXPIRED -> Triple(
            "Expired · no longer detected",
            Color(0xFFEDF0F3),
            Color(0xFF687486),
        )
        ReportBadge.PENDING_UPLOAD -> Triple(
            "Waiting to send",
            Color(0xFFFFF2D9),
            Color(0xFF9A6410),
        )
    }
    Text(
        text = label,
        color = foreground,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun ReportDetailScreen(
    report: UserReport,
    onBack: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(Color.White)
            .verticalScroll(rememberScrollState())
            .padding(start = 14.dp, top = 16.dp, end = 14.dp, bottom = 20.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back to reports",
                tint = PrimaryText,
                modifier = Modifier
                    .size(24.dp)
                    .clickable(role = Role.Button, onClick = onBack),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "${report.type.titleCase()} · ${report.severity.titleCase()}",
                color = PrimaryText,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(18.dp))
        CapturedFrame(
            timestamp = report.capturedTimeLabel(),
            modifier = Modifier
                .fillMaxWidth()
                .height(142.dp),
        )
        Spacer(Modifier.height(7.dp))

        DetailRow("Type", report.type.titleCase())
        DetailRow(
            label = "Severity",
            value = buildString {
                append(report.severity.titleCase())
                report.laneCoverage?.let {
                    append(" · ")
                    append(it.replace('-', ' '))
                }
            },
            valueColor = report.severity.detailColor(),
        )
        DetailRow(
            label = "Location",
            value = report.locationName.replace(" near ", " & "),
        )
        DetailRow(
            label = "Status",
            value = report.detailStatus(),
            valueColor = report.detailStatusColor(),
        )
        DetailRow("Confirmations", report.confirmations.toString())

        Spacer(Modifier.height(11.dp))
        OutlinedButton(
            onClick = onRemove,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(13.dp),
            border = BorderStroke(1.5.dp, Color(0xFFCBD3E0)),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            Text(
                text = "Not a hazard? Remove",
                color = Color(0xFF183456),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun CapturedFrame(
    timestamp: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(13.dp))
            .background(Color(0xFF5F6368)),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFFC8D8E7), Color(0xFF77818B)),
                ),
            )

            val road = Path().apply {
                moveTo(size.width * 0.37f, size.height * 0.22f)
                lineTo(size.width * 0.63f, size.height * 0.22f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(road, Color(0xFF5E6267))
            drawLine(
                color = Color(0xFF34383D),
                start = Offset(size.width * 0.37f, size.height * 0.22f),
                end = Offset(0f, size.height),
                strokeWidth = 2.dp.toPx(),
            )
            drawLine(
                color = Color(0xFF34383D),
                start = Offset(size.width * 0.63f, size.height * 0.22f),
                end = Offset(size.width, size.height),
                strokeWidth = 2.dp.toPx(),
            )
            drawOval(
                color = Color(0xFFFFA51E),
                topLeft = Offset(size.width * 0.47f, size.height * 0.69f),
                size = Size(size.width * 0.26f, size.height * 0.17f),
                style = Stroke(width = 3.dp.toPx()),
            )
        }
        Text(
            text = "Captured frame · ${timestamp}",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 10.dp, top = 8.dp),
        )
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    valueColor: Color = Color(0xFF252930),
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(41.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = SecondaryText,
                fontSize = 14.sp,
            )
            Text(
                text = value,
                color = valueColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(DividerColor),
        )
    }
}

private fun ReportSeverity.color(): Color = when (this) {
    ReportSeverity.MINOR -> Color(0xFF9AA7B7)
    ReportSeverity.MODERATE -> Color(0xFFFFA31A)
    ReportSeverity.SEVERE -> Color(0xFFE84A50)
}

private fun ReportSeverity.detailColor(): Color = when (this) {
    ReportSeverity.MINOR -> Color(0xFF6D7A8C)
    ReportSeverity.MODERATE -> Color(0xFFB36A00)
    ReportSeverity.SEVERE -> Color(0xFFD8342F)
}

private fun UserReport.detailStatus(): String = when (badge) {
    ReportBadge.SUBMITTED,
    ReportBadge.MATCHED,
    -> "Live · ${confirmations + 1} drivers alerted"
    ReportBadge.EXPIRED -> "Expired · no longer detected"
    ReportBadge.PENDING_UPLOAD -> "Waiting to send"
}

private fun UserReport.detailStatusColor(): Color = when (badge) {
    ReportBadge.SUBMITTED,
    ReportBadge.MATCHED,
    -> Color(0xFF228B45)
    ReportBadge.EXPIRED -> Color(0xFF687486)
    ReportBadge.PENDING_UPLOAD -> Color(0xFF9A6410)
}

private fun UserReport.capturedTimeLabel(): String {
    val zone = ZoneId.systemDefault()
    val capturedAt = Instant.parse(capturedAt).atZone(zone)
    val today = LocalDate.now(zone)
    return when (capturedAt.toLocalDate()) {
        today -> capturedAt.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
        today.minusDays(1) -> "Yesterday"
        else -> capturedAt.format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))
    }
}

private fun String.titleCase(): String =
    replace('_', ' ').replaceFirstChar { it.titlecase(Locale.getDefault()) }

private fun ReportSeverity.titleCase(): String = apiValue.titleCase()
