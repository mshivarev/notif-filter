@file:OptIn(ExperimentalMaterial3Api::class)

package ru.notiffilter.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.notiffilter.classify.NotifInput
import ru.notiffilter.classify.PromoClassifier
import ru.notiffilter.classify.Rules
import ru.notiffilter.classify.Sensitivity
import ru.notiffilter.data.Repo

@Composable
fun RulesScreen(modifier: Modifier) {
    val version by Repo.changes.collectAsState()
    val sensitivity = remember(version) { Repo.sensitivity }
    val keywords = remember(version) { Repo.keywords }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Правила", style = MaterialTheme.typography.headlineSmall)

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Чувствительность", style = MaterialTheme.typography.titleMedium)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Sensitivity.entries.forEachIndexed { i, s ->
                        SegmentedButton(
                            selected = s == sensitivity,
                            onClick = { Repo.sensitivity = s },
                            shape = SegmentedButtonDefaults.itemShape(i, Sensitivity.entries.size),
                            label = { Text(s.title) },
                        )
                    }
                }
                Text(
                    when (sensitivity) {
                        Sensitivity.LOW -> "Скрывать только явную рекламу. Меньше ошибок, но часть промо проскочит."
                        Sensitivity.MEDIUM -> "Баланс: одного сильного признака («скидка», «промокод», «−30%») достаточно."
                        Sensitivity.HIGH -> "Скрывать даже мягкие предложения и подборки. Возможны лишние срабатывания."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        TesterCard(sensitivity)

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Свои слова", style = MaterialTheme.typography.titleMedium)
                Text(
                    "«Реклама» — уведомление с этим словом будет скрыто. «Не реклама» — защитит от скрытия " +
                        "(например, название вашего банка или «расписание»).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                var word by rememberSaveable { mutableStateOf("") }
                OutlinedTextField(word, { word = it }, singleLine = true, label = { Text("Слово или фраза") },
                    modifier = Modifier.fillMaxWidth())
                Row {
                    Button(enabled = word.isNotBlank(), onClick = { Repo.addKeyword(word, 3); word = "" }) {
                        Text("Реклама")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(enabled = word.isNotBlank(), onClick = { Repo.addKeyword(word, -6); word = "" }) {
                        Text("Не реклама")
                    }
                }
                keywords.forEach { k ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (k.weight > 0) "реклама" else "не реклама",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (k.weight > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(84.dp),
                        )
                        Text(k.word, Modifier.weight(1f))
                        IconButton({ Repo.removeKeyword(k.word) }) { Icon(Icons.Filled.Close, "Удалить") }
                    }
                }
            }
        }

        BuiltInRulesCard()
    }
}

@Composable
private fun TesterCard(sensitivity: Sensitivity) {
    var title by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Проверить уведомление", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(title, { title = it }, singleLine = true, label = { Text("Заголовок") },
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(text, { text = it }, label = { Text("Текст") }, modifier = Modifier.fillMaxWidth())
            if (title.isNotBlank() || text.isNotBlank()) {
                val v = PromoClassifier.classify(NotifInput("test", title, text), sensitivity = sensitivity, custom = Repo.keywords)
                Text(
                    if (v.isPromo) "Реклама — будет скрыто" else "Не реклама — останется",
                    fontWeight = FontWeight.Bold,
                    color = if (v.isPromo) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Балл ${v.score} (порог ${sensitivity.threshold})" +
                        if (v.reasons.isNotEmpty()) ": ${v.reasons.joinToString(", ")}" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun BuiltInRulesCard() {
    var open by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable { open = !open }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (open) "Встроенные правила ▲" else "Встроенные правила ▼",
                style = MaterialTheme.typography.titleMedium,
            )
            if (open) {
                Text(
                    "Каждый признак добавляет или отнимает баллы. Уведомление скрывается, если сумма ≥ порога " +
                        "и есть хотя бы один содержательный признак рекламы. Также учитывается название канала " +
                        "уведомления (у многих магазинов есть канал «Акции» или «Promo»), эмодзи и капслок. " +
                        "Личные сообщения получают −5.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                (Rules.text + Rules.channel).sortedByDescending { it.weight }.forEach { r ->
                    Row {
                        Text(
                            if (r.weight > 0) "+${r.weight}" else "${r.weight}",
                            color = if (r.weight > 0) MaterialTheme.colorScheme.error else Color(0xFF2E7D32),
                            modifier = Modifier.width(36.dp),
                        )
                        Text(r.label)
                    }
                }
            }
        }
    }
}
