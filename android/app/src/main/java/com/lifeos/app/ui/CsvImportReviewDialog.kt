package com.lifeos.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lifeos.app.data.Project
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * One row of the CSV file being reviewed before import (chapter: import
 * review) - `approved` defaults true (checkmark), the user can uncheck rows
 * they don't want imported. `timeError` is set whenever [combinedTime] fails
 * to round-trip through [parseTimeField], which also force-clears `approved`
 * so a malformed row can't slip through silently - the user has to either
 * fix the text (re-enabling approval isn't automatic - they must re-check it)
 * or leave it rejected.
 */
data class CsvImportRow(
    val id: Int,
    val name: String,
    val date: String,
    val start: String,
    val end: String,
    val project: String,
    val approved: Boolean = true,
    val timeError: Boolean = false,
) {
    val combinedTime: String get() = "$date $start-$end"
}

private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

/**
 * Parses `project,date,start,end,title` CSV text (same columns
 * `/api/import/csv` expects) into editable rows. Deliberately simple -
 * unquoted comma-split, no RFC 4180 quoting support (matches the documented
 * format; a title containing a literal comma will parse wrong, same known
 * limitation noted in docs/IMPORTS.md).
 */
fun parseCsvRows(csvText: String): List<CsvImportRow> {
    val lines = csvText.lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) return emptyList()
    val header = lines.first().split(",").map { it.trim() }
    val projectIdx = header.indexOf("project")
    val dateIdx = header.indexOf("date")
    val startIdx = header.indexOf("start")
    val endIdx = header.indexOf("end")
    val titleIdx = header.indexOf("title")
    if (projectIdx < 0 || dateIdx < 0 || startIdx < 0 || endIdx < 0) return emptyList()

    return lines.drop(1).mapIndexedNotNull { index, line ->
        val cols = line.split(",").map { it.trim() }
        val project = cols.getOrNull(projectIdx) ?: return@mapIndexedNotNull null
        val date = cols.getOrNull(dateIdx) ?: return@mapIndexedNotNull null
        val start = cols.getOrNull(startIdx) ?: return@mapIndexedNotNull null
        val end = cols.getOrNull(endIdx) ?: return@mapIndexedNotNull null
        val title = titleIdx.takeIf { it >= 0 }?.let { cols.getOrNull(it) } ?: ""
        CsvImportRow(id = index, name = title, date = date, start = start, end = end, project = project)
    }
}

/** Parses a `"YYYY-MM-DD HH:MM-HH:MM"` combined time field back into
 * (date, start, end), or null if malformed or end<=start - mirrors the
 * server's own date/time parsing + `end must be after start` check
 * (`server/app/main.py::import_csv`), done client-side so the error surfaces
 * in the review row instead of only in the post-import error list. */
fun parseTimeField(text: String): Triple<String, String, String>? {
    val spaceIdx = text.indexOf(' ')
    if (spaceIdx < 0) return null
    val datePart = text.substring(0, spaceIdx)
    val rangePart = text.substring(spaceIdx + 1)
    val dashIdx = rangePart.indexOf('-')
    if (dashIdx < 0) return null
    val startPart = rangePart.substring(0, dashIdx)
    val endPart = rangePart.substring(dashIdx + 1)
    return try {
        val date = LocalDate.parse(datePart, DATE_FORMAT)
        val start = LocalTime.parse(startPart, TIME_FORMAT)
        val end = LocalTime.parse(endPart, TIME_FORMAT)
        if (end <= start) return null
        Triple(date.format(DATE_FORMAT), start.format(TIME_FORMAT), end.format(TIME_FORMAT))
    } catch (_: DateTimeParseException) {
        null
    }
}

/** Re-serializes approved rows back into the same CSV shape `/api/import/csv`
 * already accepts - no server changes needed for this import path, since all
 * editing happens before the existing endpoint is ever called. */
fun toCsv(rows: List<CsvImportRow>): String = buildString {
    appendLine("project,date,start,end,title")
    rows.forEach { row -> appendLine("${row.project},${row.date},${row.start},${row.end},${row.name}") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CsvImportReviewDialog(
    initialRows: List<CsvImportRow>,
    projects: List<Project>,
    onDismiss: () -> Unit,
    onConfirm: (List<CsvImportRow>) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val rows = remember(initialRows) { initialRows.toMutableStateList() }
        val approvedCount = rows.count { it.approved }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Проверка импорта · ${rows.size}") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Закрыть") }
                    },
                    actions = {
                        TextButton(
                            onClick = { onConfirm(rows.filter { it.approved }) },
                            enabled = approvedCount > 0,
                        ) { Text("Импортировать ($approvedCount)") }
                    },
                )
            },
        ) { padding ->
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
                items(rows, key = { it.id }) { row ->
                    CsvImportRowCard(
                        row = row,
                        projects = projects,
                        onChange = { updated ->
                            val idx = rows.indexOfFirst { it.id == row.id }
                            if (idx >= 0) rows[idx] = updated
                        },
                    )
                    Spacer(Modifier.padding(4.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CsvImportRowCard(
    row: CsvImportRow,
    projects: List<Project>,
    onChange: (CsvImportRow) -> Unit,
) {
    var timeText by remember(row.id) { mutableStateOf(row.combinedTime) }
    var projectExpanded by remember { mutableStateOf(false) }

    fun applyTime(text: String) {
        timeText = text
        val parsed = parseTimeField(text)
        onChange(
            if (parsed != null) {
                row.copy(date = parsed.first, start = parsed.second, end = parsed.third, timeError = false)
            } else {
                row.copy(timeError = true, approved = false)
            },
        )
    }

    val isNewProject = projects.none { it.name == row.project }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (row.approved) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
            },
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = row.name,
                        onValueChange = { onChange(row.copy(name = it)) },
                        label = { Text("Название") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.padding(4.dp))
                    OutlinedTextField(
                        value = timeText,
                        onValueChange = ::applyTime,
                        label = { Text("Время") },
                        isError = row.timeError,
                        supportingText = if (row.timeError) {
                            { Text("Формат: ГГГГ-ММ-ДД ЧЧ:ММ-ЧЧ:ММ") }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.padding(4.dp))
                    ExposedDropdownMenuBox(
                        expanded = projectExpanded,
                        onExpandedChange = { projectExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = row.project,
                            onValueChange = { onChange(row.copy(project = it)) },
                            label = { Text("Проект") },
                            supportingText = if (isNewProject && row.project.isNotBlank()) {
                                { Text("*новый") }
                            } else {
                                null
                            },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                        )
                        DropdownMenu(
                            expanded = projectExpanded && projects.isNotEmpty(),
                            onDismissRequest = { projectExpanded = false },
                        ) {
                            projects.forEach { project ->
                                DropdownMenuItem(
                                    text = { Text(project.name) },
                                    onClick = {
                                        onChange(row.copy(project = project.name))
                                        projectExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { onChange(row.copy(approved = !row.approved)) }) {
                    Icon(
                        imageVector = if (row.approved) Icons.Filled.Check else Icons.Filled.Close,
                        contentDescription = if (row.approved) "Одобрено" else "Отклонено",
                        tint = if (row.approved) {
                            Color(0xFF4CAF50)
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        }
    }
}
