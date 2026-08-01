package com.lifeos.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mikepenz.markdown.m3.Markdown

/**
 * Reusable full-screen Markdown notes editor (chapter: project/subtask notes) -
 * doesn't know whether it's editing a Project's or a Subtask's notes, the
 * caller owns persistence via [onSave]. Toggles between raw-text editing and
 * a rendered preview rather than showing both at once, to keep the small
 * phone screen from being split into two cramped panes.
 */
@Composable
fun NotesEditorDialog(
    title: String,
    initialNotes: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        NotesEditorContent(title = title, initialNotes = initialNotes, onClose = onDismiss, onSave = onSave)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesEditorContent(
    title: String,
    initialNotes: String,
    onClose: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initialNotes) }
    var previewMode by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Закрыть")
                    }
                },
                actions = {
                    IconButton(onClick = { previewMode = !previewMode }) {
                        Icon(
                            imageVector = if (previewMode) Icons.Filled.Edit else Icons.Filled.Visibility,
                            contentDescription = if (previewMode) "Редактировать" else "Просмотр",
                        )
                    }
                    TextButton(onClick = { onSave(text) }) { Text("Сохранить") }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            if (previewMode) {
                Markdown(
                    content = text,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                )
            } else {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxSize(),
                    placeholder = { Text("Markdown-заметка...") },
                )
            }
        }
    }
}
