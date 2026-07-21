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
import com.lifeos.app.data.Block

@Composable
fun DayScreen(blocks: List<Block>) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        items(blocks.sortedBy { it.start_time }) { block ->
            ListItem(
                headlineContent = { Text(block.title) },
                supportingContent = {
                    Text("${block.start_time}–${block.end_time} · ${block.status}")
                },
            )
            Divider()
        }
    }
}
