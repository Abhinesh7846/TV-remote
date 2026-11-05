package com.abhinesh.tvremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abhinesh.tvremote.TvRemoteApp
import com.abhinesh.tvremote.core.Routine
import com.abhinesh.tvremote.core.RoutineSteps

private val ROUTINE_ICONS = listOf(
    "movie" to Icons.Outlined.Movie, "game" to Icons.Outlined.SportsEsports, "moon" to Icons.Outlined.Bedtime,
    "music" to Icons.Outlined.MusicNote, "tv" to Icons.Outlined.Tv, "star" to Icons.Outlined.StarOutline,
)

private fun iconFor(key: String): ImageVector = ROUTINE_ICONS.firstOrNull { it.first == key }?.second ?: Icons.Outlined.StarOutline

@Composable
fun RoutinesScreen(app: TvRemoteApp) {
    val routines by app.store.routines.collectAsStateWithLifecycle()
    val shortcuts by app.store.shortcuts.collectAsStateWithLifecycle()
    val runs by app.repo.routineRuns.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Routine?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle("Routines")
        routines.forEach { r ->
            val run = runs[r.id]
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Ui.surface).border(1.dp, Ui.border, RoundedCornerShape(22.dp))
                    .clickable { editing = r }.padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(iconFor(r.icon), null, tint = Ui.accentText, modifier = Modifier.size(38.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    ScriptText(r.name, style = MaterialTheme.typography.titleLarge, fontSize = 20.sp)
                    ScriptText(
                        run?.message ?: RoutineSteps.summary(r, shortcuts),
                        color = when { run == null -> Ui.textDim; run.running -> Ui.accentText; run.ok -> Ui.success; else -> Ui.danger },
                        style = MaterialTheme.typography.bodyLarge, maxLines = 2,
                    )
                }
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(Ui.accentDark.copy(alpha = 0.35f))
                        .clickable(enabled = run?.running != true) { app.repo.runRoutine(r) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (run?.running == true) CircularProgressIndicator(Modifier.size(22.dp), color = Ui.accentText, strokeWidth = 2.5.dp)
                    else Icon(Icons.Rounded.PlayArrow, "Run ${r.name}", tint = Ui.accentText, modifier = Modifier.size(30.dp))
                }
            }
        }
        Spacer(Modifier.size(4.dp))
        OutlineCopperButton("Add routine", { editing = Routine("r-${System.currentTimeMillis()}", "", "star", emptyList()) }, icon = Icons.Rounded.Add,
            modifier = Modifier.border(1.2.dp, Ui.accent, RoundedCornerShape(20.dp)))
        Text("Tap a routine to edit it. HDMI input steps send the TV's HDMI keys; check they switch input on your TV.",
            color = Ui.textMuted, style = MaterialTheme.typography.bodySmall)
    }

    editing?.let { r ->
        RoutineDialog(app, r, isNew = routines.none { it.id == r.id },
            onSave = { u ->
                app.store.saveRoutines(if (routines.any { it.id == u.id }) routines.map { if (it.id == u.id) u else it } else routines + u)
                editing = null
            },
            onDelete = { app.store.saveRoutines(routines.filterNot { it.id == r.id }); editing = null },
            onDismiss = { editing = null })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoutineDialog(app: TvRemoteApp, initial: Routine, isNew: Boolean, onSave: (Routine) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val shortcuts = app.store.shortcuts.value
    var name by remember { mutableStateOf(initial.name) }
    var icon by remember { mutableStateOf(initial.icon) }
    var steps by remember { mutableStateOf(initial.steps) }
    var adding by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ui.surface,
        title = { Text(if (isNew) "Add routine" else "Edit routine") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ROUTINE_ICONS.forEach { (key, img) ->
                        Box(
                            Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (icon == key) Ui.accentDark.copy(alpha = 0.45f) else Ui.key).clickable { icon = key },
                            contentAlignment = Alignment.Center,
                        ) { Icon(img, key, tint = if (icon == key) Ui.accentText else Ui.textDim) }
                    }
                }
                Text("Steps (run in order)", style = MaterialTheme.typography.titleSmall)
                steps.forEachIndexed { i, s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}. ${RoutineSteps.describe(s, shortcuts)}", modifier = Modifier.weight(1f))
                        IconButton(onClick = { steps = steps.filterIndexed { j, _ -> j != i } }) { Icon(Icons.Rounded.Close, "Remove step", tint = Ui.textDim) }
                    }
                }
                Box {
                    TextButton(onClick = { adding = true }) { Icon(Icons.Rounded.Add, null, tint = Ui.accentText); Text(" Add step", color = Ui.accentText) }
                    DropdownMenu(expanded = adding, onDismissRequest = { adding = false }) {
                        RoutineSteps.choices(shortcuts).forEach { c ->
                            DropdownMenuItem(text = { Text(RoutineSteps.describe(c, shortcuts)) }, onClick = { steps = steps + c; adding = false })
                        }
                    }
                }
                if (!isNew) TextButton(onClick = onDelete) { Text("Delete routine", color = Ui.danger) }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && steps.isNotEmpty(), onClick = { onSave(initial.copy(name = name.trim(), icon = icon, steps = steps)) }) {
                Text("Save", color = Ui.accentText)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Ui.textDim) } },
    )
}
