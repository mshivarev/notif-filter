@file:OptIn(ExperimentalMaterial3Api::class)

package ru.notiffilter.ui

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.notiffilter.classify.AppMode
import ru.notiffilter.data.Repo

private class AppInfo(val pkg: String, val label: String, val icon: ImageBitmap?)

private val modeTitles = listOf(AppMode.AUTO to "Авто", AppMode.ALLOW to "Доверять", AppMode.BLOCK to "Блок")

@Composable
fun AppsScreen(modifier: Modifier) {
    val ctx = LocalContext.current
    val apps by produceState<List<AppInfo>?>(null) {
        value = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launcher, 0)
                .distinctBy { it.activityInfo.packageName }
                .filter { it.activityInfo.packageName != ctx.packageName }
                .map { ri ->
                    AppInfo(
                        ri.activityInfo.packageName,
                        ri.loadLabel(pm).toString(),
                        runCatching { ri.loadIcon(pm).toBitmap(96, 96).asImageBitmap() }.getOrNull(),
                    )
                }
        }
    }
    val counts by repoState(emptyMap<String, Int>()) { Repo.blockedCountByPkg() }
    val modes by repoState(emptyMap<String, AppMode>()) { Repo.allModes() }
    var query by rememberSaveable { mutableStateOf("") }

    Column(modifier.fillMaxSize()) {
        Text("Приложения", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp))
        Text(
            "Авто — решают правила. Доверять — никогда не трогать. Блок — скрывать всё от приложения.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        OutlinedTextField(
            query, { query = it }, singleLine = true, label = { Text("Поиск") },
            modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
        )

        val all = apps
        if (all == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        val shown = remember(all, counts, modes, query) {
            all.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
                .sortedWith(
                    compareByDescending<AppInfo> { counts[it.pkg] ?: 0 }
                        .thenByDescending { modes.containsKey(it.pkg) }
                        .thenBy { it.label.lowercase() }
                )
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(shown, key = { it.pkg }) { app ->
                AppRow(app, counts[app.pkg] ?: 0, modes[app.pkg] ?: AppMode.AUTO)
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@Composable
private fun AppRow(app: AppInfo, count: Int, mode: AppMode) {
    Column(Modifier.fillMaxWidth().padding(16.dp, 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (app.icon != null) Image(app.icon, null, Modifier.size(36.dp))
            else Spacer(Modifier.size(36.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (count > 0) Text(
                    "Скрыто: $count", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.padding(3.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modeTitles.forEachIndexed { i, (m, title) ->
                SegmentedButton(
                    selected = mode == m,
                    onClick = { Repo.setMode(app.pkg, m) },
                    shape = SegmentedButtonDefaults.itemShape(i, modeTitles.size),
                    label = { Text(title) },
                )
            }
        }
    }
}
