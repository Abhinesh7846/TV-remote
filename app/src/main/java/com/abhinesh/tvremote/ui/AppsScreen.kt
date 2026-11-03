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
import androidx.compose.foundation.lazy.itemsIndexed
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

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("App shortcuts", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
            Text(
                "Apps open on the TV, not on this phone. The first four appear on the remote. A launch only shows as opened once the TV reports that app in front.",
                color = Ui.textDim, style = MaterialTheme.typography.bodySmall,
            )
        }
        itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
            Row(
                Modifier.fillMaxWidth().background(Ui.key, RoundedCornerShape(16.dp)).padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp).background(Color(s.colorArgb), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Text(s.label.take(1), color = Color(s.onColorArgb), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.label + if (i < 4) "" else "  (not on remote)", fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.targets.joinToString("  ·  "), color = Ui.textDim, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { app.repo.launch(s) }, enabled = launching == null) { Icon(Icons.Rounded.PlayArrow, "Open ${s.label} on TV") }
                IconButton(onClick = { store.saveShortcuts(list.move(i, i - 1)) }, enabled = i > 0) { Icon(Icons.Rounded.ArrowUpward, "Move ${s.label} up") }
                IconButton(onClick = { store.saveShortcuts(list.move(i, i + 1)) }, enabled = i < list.lastIndex) { Icon(Icons.Rounded.ArrowDownward, "Move ${s.label} down") }
                IconButton(onClick = { editing = s }) { Icon(Icons.Rounded.Edit, "Edit ${s.label}") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { editing = AppShortcut("custom-${System.currentTimeMillis()}", "", "", emptyList(), palette[5].first, palette[5].second) }) {
                    Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Add shortcut")
                }
                OutlinedButton(onClick = { confirmReset = true }) { Text("Reset to defaults") }
            }
            Spacer(Modifier.padding(8.dp))
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
                onClick = { onSave(initial.copy(label = label.trim(), packageName = pkg, targets = targets, colorArgb = color.first, onColorArgb = color.second, aliases = initial.aliases.ifEmpty { listOf(label.trim().lowercase()) })) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
