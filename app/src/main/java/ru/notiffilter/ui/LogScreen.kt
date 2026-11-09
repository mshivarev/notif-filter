@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package ru.notiffilter.ui

import android.widget.Toast
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.notiffilter.classify.AppMode
import ru.notiffilter.data.LogEntry
import ru.notiffilter.data.Repo
import ru.notiffilter.service.FilterListenerService
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val dayFmt = SimpleDateFormat("d MMMM, EEEE", Locale("ru"))
private val timeFmt = SimpleDateFormat("HH:mm", Locale("ru"))

private fun dayTitle(t: Long): String {
    val c = Calendar.getInstance()
    val today = c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    c.timeInMillis = t
    val d = c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    return when (today - d) {
        0 -> "Сегодня"
        1 -> "Вчера"
        else -> dayFmt.format(Date(t))
    }
}

@Composable
fun LogScreen(modifier: Modifier) {
    var showBlocked by rememberSaveable { mutableStateOf(true) }
    val list by repoState(emptyList<LogEntry>(), showBlocked) { Repo.entries(showBlocked) }
    var confirmClear by remember { mutableStateOf(false) }
    var keywordFor by remember { mutableStateOf<LogEntry?>(null) }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp, 16.dp, 16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Журнал", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (list.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Очистить") }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            SegmentedButton(showBlocked, { showBlocked = true }, SegmentedButtonDefaults.itemShape(0, 2)) {
                Text("Скрытые")
            }
            SegmentedButton(!showBlocked, { showBlocked = false }, SegmentedButtonDefaults.itemShape(1, 2)) {
                Text("Пропущенные")
            }
        }
        Text(
            if (showBlocked) "Нажмите на запись, чтобы открыть её или пометить «не реклама»."
            else "Последние 300 обычных уведомлений. Если среди них реклама — научите фильтр.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp, 8.dp),
        )

        if (list.isEmpty()) {
            Text(
                if (showBlocked) "Пока ничего не скрыто." else "Пока пусто.",
                Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val grouped = remember(list) { list.groupBy { dayTitle(it.time) } }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 0.dp, 16.dp, 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            grouped.forEach { (day, entries) ->
                item(key = "h_$day") {
                    Text(day, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                }
                items(entries, key = { it.id }) { e -> LogCard(e, onAddKeyword = { keywordFor = e }) }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить журнал?") },
            text = { Text(if (showBlocked) "Все скрытые уведомления будут удалены из журнала." else "Список пропущенных будет очищен.") },
            confirmButton = { TextButton({ Repo.clear(showBlocked); confirmClear = false }) { Text("Очистить") } },
            dismissButton = { TextButton({ confirmClear = false }) { Text("Отмена") } },
        )
    }

    keywordFor?.let { e -> AddKeywordDialog(e) { keywordFor = null } }
}

@Composable
private fun LogCard(e: LogEntry, onAddKeyword: () -> Unit) {
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    e.app, style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(timeFmt.format(Date(e.time)), style = MaterialTheme.typography.labelMedium)
            }
            if (e.title.isNotBlank()) Text(e.title, fontWeight = FontWeight.SemiBold)
            if (e.text.isNotBlank()) Text(
                e.text, style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
            )
            if (e.blocked || expanded) {
                Spacer(Modifier.padding(2.dp))
                Text(
                    buildString {
                        if (e.blocked && !e.dismissed) append("Тест, не убрано · ")
                        append("балл ${e.score}")
                        if (e.reasons.isNotBlank()) append(" · ${e.reasons}")
                        if (expanded && e.channel.isNotBlank()) append(" · канал «${e.channel}»")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val pi = FilterListenerService.intentFor(e.token)
                    if (pi != null) TextButton({
                        if (!sendPendingIntent(ctx, pi)) Toast.makeText(ctx, "Не удалось открыть", Toast.LENGTH_SHORT).show()
                    }) { Text("Открыть") }
                    if (e.blocked) {
                        TextButton({
                            Repo.setMode(e.pkg, AppMode.ALLOW)
                            Repo.delete(e.id)
                            Toast.makeText(ctx, "«${e.app}» теперь не фильтруется", Toast.LENGTH_SHORT).show()
                        }) { Text("Не реклама: доверять приложению") }
                    } else {
                        TextButton({
                            Repo.setMode(e.pkg, AppMode.BLOCK)
                            Repo.deleteByPkg(e.pkg, blocked = false)
                            Toast.makeText(ctx, "Все уведомления «${e.app}» будут скрываться", Toast.LENGTH_SHORT).show()
                        }) { Text("Блокировать приложение") }
                        TextButton(onAddKeyword) { Text("Это реклама: добавить слово") }
                    }
                    TextButton({ Repo.delete(e.id) }) { Text("Удалить") }
                }
            }
        }
    }
}

@Composable
private fun AddKeywordDialog(e: LogEntry, onDone: () -> Unit) {
    var word by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Слово-признак рекламы") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Впишите слово или фразу из этого уведомления. Уведомления с ней будут считаться рекламой.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("${e.title}\n${e.text}".trim(), style = MaterialTheme.typography.bodySmall, maxLines = 4)
                OutlinedTextField(word, { word = it }, singleLine = true, label = { Text("Слово или фраза") })
            }
        },
        confirmButton = {
            TextButton(enabled = word.isNotBlank(), onClick = {
                Repo.addKeyword(word, 3)
                Repo.delete(e.id)
                onDone()
            }) { Text("Добавить") }
        },
        dismissButton = { TextButton(onDone) { Text("Отмена") } },
    )
}
