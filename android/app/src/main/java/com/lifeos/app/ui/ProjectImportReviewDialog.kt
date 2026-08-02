package com.lifeos.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lifeos.app.data.ImportProjectPayload
import com.lifeos.app.data.ImportSubtaskPayload
import com.lifeos.app.data.Project
import com.lifeos.app.data.Subtask
import com.google.gson.Gson

/**
 * A checklist node parsed from an import file, mid-review (chapter: project
 * import review) - `localId` is a review-session-only counter (imported
 * files carry no ids), `alreadyExists` marks a node this app matched by
 * (title, parent) against the target project's CURRENT subtasks - purely
 * cosmetic, the server is the authoritative dedup source of truth (see
 * `import_project`'s own (title, parent) matching); this client check can
 * drift from the server's if either side's matching rule changes without
 * the other. A node with no matching parent can never itself match either
 * (nothing in the DB could already have a not-yet-created id as its
 * parent_id) - see [buildImportTaskTree]'s sentinel handling.
 */
data class ImportTaskNode(
    val localId: Int,
    val title: String,
    val done: Boolean,
    val children: List<ImportTaskNode>,
    val accepted: Boolean = true,
    val alreadyExists: Boolean = false,
)

private const val NEVER_MATCHES_PARENT_ID = Int.MIN_VALUE

/** Builds the review tree from parsed payload nodes, marking `alreadyExists`
 * by walking [existingSubtasks] (title, parent) - same rule as the server's
 * dedup, checked here only for preview purposes. */
fun buildImportTaskTree(items: List<ImportSubtaskPayload>, existingSubtasks: List<Subtask>): List<ImportTaskNode> {
    val existingByParentTitle: Map<Int?, Map<String, Subtask>> =
        existingSubtasks.groupBy { it.parent_id }.mapValues { (_, siblings) -> siblings.associateBy { it.title } }
    var counter = 0
    fun walk(nodes: List<ImportSubtaskPayload>, parentId: Int?): List<ImportTaskNode> = nodes.map { item ->
        val existing = existingByParentTitle[parentId]?.get(item.title)
        val childParentId = existing?.id ?: NEVER_MATCHES_PARENT_ID
        ImportTaskNode(
            localId = counter++,
            title = item.title,
            done = item.done,
            children = walk(item.subtasks, childParentId),
            alreadyExists = existing != null,
        )
    }
    return walk(items, null)
}

private fun toggleAccepted(nodes: List<ImportTaskNode>, targetId: Int): List<ImportTaskNode> = nodes.map { node ->
    if (node.localId == targetId) {
        node.copy(accepted = !node.accepted)
    } else {
        node.copy(children = toggleAccepted(node.children, targetId))
    }
}

/** localIds excluded from the final import - either rejected themselves, or
 * a descendant of a rejected node (rejecting a task excludes its whole
 * subtree, same as [pruneToAccepted] below implements for submission). */
private fun collectExcludedIds(nodes: List<ImportTaskNode>, ancestorExcluded: Boolean, into: MutableSet<Int>) {
    for (node in nodes) {
        val excluded = ancestorExcluded || !node.accepted
        if (excluded) into.add(node.localId)
        collectExcludedIds(node.children, excluded, into)
    }
}

/** Prunes rejected subtrees and converts back to the payload shape
 * [ApiFactory.importProject] expects - already-existing nodes are always
 * kept (they have no checkbox to reject with; the server dedups them again
 * regardless, this just ensures their genuinely-new children still ride
 * along). */
fun pruneToAccepted(nodes: List<ImportTaskNode>): List<ImportSubtaskPayload> =
    nodes.filter { it.accepted }.map { node ->
        ImportSubtaskPayload(title = node.title, done = node.done, subtasks = pruneToAccepted(node.children))
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectImportReviewDialog(
    payload: ImportProjectPayload,
    projectExists: Boolean,
    existingSubtasks: List<Subtask>,
    onDismiss: () -> Unit,
    onConfirm: (ImportProjectPayload) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var tree by remember(payload) { mutableStateOf(buildImportTaskTree(payload.subtasks, existingSubtasks)) }
        var collapsedIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
        val excludedIds = remember(tree) { mutableSetOf<Int>().also { collectExcludedIds(tree, false, it) } }
        val acceptedCount = remember(tree) { tree.sumOf { countAccepted(it) } }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Проверка импорта") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Закрыть") }
                    },
                    actions = {
                        TextButton(
                            onClick = { onConfirm(payload.copy(subtasks = pruneToAccepted(tree))) },
                            enabled = acceptedCount > 0 || payload.static_entries.isNotEmpty(),
                        ) { Text("Импортировать") }
                    },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                LazyColumn {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(payload.project_name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            Text(
                                text = if (projectExists) "существующий проект" else "новый проект",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    val visibleRows = buildVisibleRowsGeneric(tree, { it.children }, { it.localId }, collapsedIds)
                    itemsIndexed(visibleRows, key = { _, (node, _) -> node.localId }) { _, (node, depth) ->
                        val excluded = node.localId in excludedIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .padding(start = (16 + depth * 20).dp, end = 16.dp)
                                .alpha(if (excluded) 0.4f else 1f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (node.children.isNotEmpty()) {
                                IconButton(onClick = {
                                    collapsedIds = if (node.localId in collapsedIds) {
                                        collapsedIds - node.localId
                                    } else {
                                        collapsedIds + node.localId
                                    }
                                }, modifier = Modifier.size(24.dp)) {
                                    Icon(
                                        imageVector = if (node.localId in collapsedIds) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                                        contentDescription = if (node.localId in collapsedIds) "Развернуть" else "Свернуть",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                Spacer(Modifier.size(24.dp))
                            }
                            Text(
                                text = node.title,
                                style = if (node.done) {
                                    MaterialTheme.typography.bodyLarge.copy(textDecoration = TextDecoration.LineThrough)
                                } else {
                                    MaterialTheme.typography.bodyLarge
                                },
                                maxLines = 1,
                                modifier = Modifier.weight(1f).padding(start = 8.dp),
                            )
                            if (node.alreadyExists) {
                                Text(
                                    "уже есть",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                            } else {
                                Checkbox(
                                    checked = node.accepted,
                                    onCheckedChange = { tree = toggleAccepted(tree, node.localId) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun countAccepted(node: ImportTaskNode): Int =
    (if (node.accepted) 1 else 0) + node.children.sumOf { countAccepted(it) }

/** Parses a picked JSON file's raw text into [ImportProjectPayload] - used
 * only for the review dialog, never sent as-is (see [pruneToAccepted]). */
fun parseImportProjectPayload(json: String): ImportProjectPayload = Gson().fromJson(json, ImportProjectPayload::class.java)

/** Serializes a (possibly pruned) payload back to the JSON shape
 * `ApiFactory.importProject` already accepts as-is - no new endpoint. */
fun serializeImportProjectPayload(payload: ImportProjectPayload): String = Gson().toJson(payload)
