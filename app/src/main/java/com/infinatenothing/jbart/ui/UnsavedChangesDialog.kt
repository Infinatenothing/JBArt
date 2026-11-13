package com.infinatenothing.jbart.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.infinatenothing.jbart.R
import com.infinatenothing.jbart.session.RecentProject

@Composable
fun UnsavedChangesDialog(
    name: String,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Color.White,
        titleContentColor = Color.Black,
        textContentColor = Color.Black,
        title = { Text(stringResource(R.string.project_unsaved_title)) },
        text = { Text(stringResource(R.string.project_unsaved_body, name)) },
        confirmButton = {
            TextButton(onClick = onSave, modifier = Modifier.testTag("unsaved_save")) {
                Text(stringResource(R.string.project_save))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onCancel, modifier = Modifier.testTag("unsaved_cancel")) {
                    Text(stringResource(R.string.canvas_cancel))
                }
                TextButton(onClick = onDiscard, modifier = Modifier.testTag("unsaved_discard")) {
                    Text(stringResource(R.string.project_discard))
                }
            }
        },
    )
}

@Composable
fun RecentProjectsDialog(
    recents: List<RecentProject>,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit,
    onBrowse: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        titleContentColor = Color.Black,
        textContentColor = Color.Black,
        title = { Text(stringResource(R.string.project_recent_title)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                recents.forEach { recent ->
                    Row(
                        modifier = Modifier
                            .clickable { onOpen(recent.uri) }
                            .heightIn(min = 56.dp)
                            .testTag("recent_project"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Thumb(recent)
                        Text(
                            text = recent.name,
                            color = Color.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        )
                        val remove = stringResource(R.string.project_recent_remove, recent.name)
                        IconButton(onClick = { onRemove(recent.uri) }) {
                            Icon(Icons.Filled.Close, contentDescription = remove, tint = Color.Black)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onBrowse, modifier = Modifier.testTag("project_browse")) {
                Text(stringResource(R.string.project_browse))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.canvas_cancel))
            }
        },
    )
}

@Composable
private fun Thumb(recent: RecentProject) {
    val image = remember(recent.thumb, recent.openedAt) {
        recent.thumb?.let { file -> BitmapFactory.decodeFile(file.path)?.asImageBitmap() }
    }
    Box(Modifier.size(64.dp).border(1.dp, Color.Black), contentAlignment = Alignment.Center) {
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(62.dp))
        }
    }
}
