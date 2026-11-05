package com.abhinesh.tvremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abhinesh.tvremote.TvRemoteApp
import com.abhinesh.tvremote.core.AppShortcut
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapVert

private val palette = listOf(
    0xFFE50914 to 0xFFFFFFFF, 0xFF1B2A8A to 0xFFFFFFFF, 0xFFCC0000 to 0xFFFFFFFF, 0xFF00A8E1 to 0xFF00131C,
    0xFF1DB954 to 0xFF04210F, 0xFF7B3FE4 to 0xFFFFFFFF, 0xFFF59E0B to 0xFF2A1700, 0xFF374151 to 0xFFFFFFFF,
)

@Composable
fun AppsScreen(app: TvRemoteApp) {
    val store = app.store
    val list by store.shortcuts.collectAsStateWithLifecycle()
    val launching by app.repo.launching.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<AppShortcut?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var editMode by remember { mutableStateOf(false) }

    var query by remember { mutableStateOf("") }
    val shown = list.filter { query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) || it.aliases.any { a -> a.contains(query.trim(), ignoreCase = true) } }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ScreenTitle("Apps", "Open an app on your TV", Modifier.padding(top = 12.dp)) }
        item {
            androidx.compose.foundation.text.BasicTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ui.text, fontFamily = AppFonts.forText(query)),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Ui.accent),
                decorationBox = { inner ->
                    Row(
                        Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(20.dp)).background(Ui.surface)
                            .border(1.dp, Ui.border, RoundedCornerShape(20.dp)).padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Search, null, tint = Ui.textDim, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(14.dp))
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) Text("Find an app", color = Ui.textDim, style = MaterialTheme.typography.bodyLarge)
                            inner()
                        }
                    }
                },
            )
        }
        val cells: List<AppShortcut?> = if (editMode) shown + listOf(null) else shown
        if (cells.isEmpty()) item { Text("No app called \"$query\". Add it with Edit favourites.", color = Ui.textDim) }
        items(cells.chunked(2)) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { s ->
                    Box(Modifier.weight(1f)) {
                        if (s == null) {
                            AddTile { editing = AppShortcut("custom-${System.currentTimeMillis()}", "", "", emptyList(), palette[5].first, palette[5].second) }
                        } else {
                            val i = list.indexOf(s)
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                OttTile(s, launching == s.id, Modifier.fillMaxWidth()) { if (editMode) editing = s else app.repo.launch(s) }
                                if (editMode) Row {
                                    IconButton(onClick = { store.saveShortcuts(list.move(i, i - 1)) }, enabled = i > 0) { Icon(Icons.Rounded.ChevronLeft, "Move ${s.label} earlier", tint = Ui.text) }
                                    IconButton(onClick = { editing = s }) { Icon(Icons.Rounded.Edit, "Edit ${s.label}", tint = Ui.accentText) }
                                    IconButton(onClick = { store.saveShortcuts(list.move(i, i + 1)) }, enabled = i < list.lastIndex) { Icon(Icons.Rounded.ChevronRight, "Move ${s.label} later", tint = Ui.text) }
                                }
                            }
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item {
            OutlineCopperButton(if (editMode) "Done" else "Edit favourites", { editMode = !editMode }, icon = if (editMode) Icons.Rounded.Check else Icons.Rounded.SwapVert)
            if (editMode) TextButton(onClick = { confirmReset = true }, modifier = Modifier.padding(top = 4.dp)) { Text("Reset to defaults", color = Ui.textDim) }
            if (editMode) Text("The first four appear on the Remote tab.", color = Ui.textMuted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
        }
    }


    editing?.let { s ->
        EditShortcutDialog(
            initial = s,
            isNew = list.none { it.id == s.id },
            onSave = { updated ->
                store.saveShortcuts(if (list.any { it.id == updated.id }) list.map { if (it.id == updated.id) updated else it } else list + updated)
                editing = null
            },
            onDelete = { store.saveShortcuts(list.filterNot { it.id == s.id }); editing = null },
            onDismiss = { editing = null },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset shortcuts?") },
            text = { Text("Restores Netflix, JioHotstar, YouTube and Prime Video with their tested links, and removes your custom shortcuts.") },
            confirmButton = { TextButton(onClick = { store.resetShortcuts(); confirmReset = false }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AddTile(onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(22))
                .border(1.5.dp, Ui.outline, RoundedCornerShape(22)).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Add, "Add an app", Modifier.size(36.dp), tint = Ui.textDim) }
        Text("Add", color = Ui.textDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    }
}

private fun <T> List<T>.move(from: Int, to: Int): List<T> =
    if (to !in indices) this else toMutableList().apply { add(to, removeAt(from)) }

@Composable
private fun EditShortcutDialog(initial: AppShortcut, isNew: Boolean, onSave: (AppShortcut) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var label by remember { mutableStateOf(initial.label) }
    var pkg by remember { mutableStateOf(initial.packageName) }
    var links by remember { mutableStateOf(initial.targets.joinToString("\n")) }
    var color by remember { mutableStateOf(initial.colorArgb to initial.onColorArgb) }
    val targets = links.lines().map { it.trim() }.filter { it.isNotEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add shortcut" else "Edit ${initial.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    links, { links = it }, label = { Text("Launch links, one per line") }, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Tried in order, e.g. netflix:// or https://… A bare package name is sent as market://launch, which the MiTV-AXFR2 refused in testing.") },
                )
                OutlinedTextField(
                    pkg, { pkg = it.trim() }, label = { Text("TV app package (to confirm the launch)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("e.g. com.netflix.ninja. Leave empty if unknown; the launch then can't be confirmed.") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    palette.forEach { c ->
                        Box(
                            Modifier.size(28.dp).background(Color(c.first), CircleShape)
                                .then(if (c.first == color.first) Modifier.border(2.dp, Ui.text, CircleShape) else Modifier)
                                .clickable { color = c },
                        )
                    }
                }
                if (!isNew) TextButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, null, tint = Ui.danger); Text(" Delete shortcut", color = Ui.danger) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = label.isNotBlank() && targets.isNotEmpty(),
                onClick = { onSave(initial.copy(label = label.trim(), packageName = pkg, targets = targets, colorArgb = color.first, onColorArgb = color.second, aliases = if (AppShortcut.DEFAULTS.any { it.id == initial.id }) initial.aliases else listOf(label.trim().lowercase()))) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
