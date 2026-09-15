package dev.foldprobe.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable fun AppPicker(slot: Int, home: HomeAppsState, onChoose: (String?) -> Unit, onDismiss: () -> Unit) {
    var query by remember(slot) { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (slot < 16) "Välj app · plats ${slot + 1}" else "Välj genväg · ${slot - 15}") },
        text = {
            Column {
                Text("Välj en app. Finns den redan på hemskärmen byter apparna plats.")
                OutlinedTextField(query, { query = it }, label = { Text("Sök appar") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(home.apps.filter { it.label.contains(query, ignoreCase = true) }, key = { it.component }) { app ->
                        Row(Modifier.fillMaxWidth().clickable { onChoose(app.component) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            app.icon?.let { Image(it, null, Modifier.size(40.dp)) }
                            Text(app.label, Modifier.padding(start = 12.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (home.slots.contains(app.component)) Text("På hemskärmen", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Stäng") } },
        dismissButton = { TextButton(onClick = { onChoose(null) }) { Text("Lämna platsen tom") } })
}
