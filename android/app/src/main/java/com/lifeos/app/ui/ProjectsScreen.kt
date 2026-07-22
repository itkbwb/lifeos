package com.lifeos.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Divider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lifeos.app.data.ProjectStat

@Composable
fun ProjectsScreen(projects: List<ProjectStat>) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        items(projects.sortedByDescending { it.priority }) { project ->
            ListItem(
                headlineContent = { Text(project.name) },
                supportingContent = {
                    val scheduledH = project.scheduled_minutes / 60
                    val scheduledM = project.scheduled_minutes % 60
                    val completedH = project.completed_minutes / 60
                    val completedM = project.completed_minutes % 60
                    Text(
                        "Выполнено %dч %02dм из %dч %02dм за неделю"
                            .format(completedH, completedM, scheduledH, scheduledM)
                    )
                },
            )
            Divider()
        }
    }
}
