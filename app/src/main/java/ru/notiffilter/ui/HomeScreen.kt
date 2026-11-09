package ru.notiffilter.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.widget.Toast
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import ru.notiffilter.data.Repo
import ru.notiffilter.service.FilterListenerService

fun isListenerEnabled(ctx: Context) =
    NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

fun isBatteryUnrestricted(ctx: Context) =
    (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

@Composable
fun HomeScreen(modifier: Modifier, openLog: () -> Unit) {
    val ctx = LocalContext.current
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }

    val granted = remember(resumeTick) { isListenerEnabled(ctx) }
    val battery = remember(resumeTick) { isBatteryUnrestricted(ctx) }
    val connected by FilterListenerService.connected.collectAsState()
    val version by Repo.changes.collectAsState()
    var enabled by remember(version) { mutableStateOf(Repo.enabled) }
    var testMode by remember(version) { mutableStateOf(Repo.testMode) }
    val stats by repoState(Repo.Stats(0, 0)) { Repo.stats() }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Без рекламы", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Приложение читает входящие уведомления и убирает рекламные — акции, скидки, промокоды. " +
                "Всё работает на телефоне, без интернета.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!granted) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Нужен доступ к уведомлениям", fontWeight = FontWeight.Bold)
                    Text(
                        "Откройте настройки, найдите «Без рекламы» и включите доступ. " +
                            "Если Android пишет «Ограниченная настройка» — зайдите в «О приложении» → ⋮ → " +
                            "«Разрешить ограниченные настройки», затем повторите."
                    )
                    Button(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }) { Text("Открыть настройки") }
                }
            }
        } else if (!connected) {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Доступ выдан, сервис подключается…", fontWeight = FontWeight.Bold)
                    Text("Если это сообщение не исчезает, перезапустите службу.")
                    OutlinedButton(onClick = {
                        NotificationListenerService.requestRebind(ComponentName(ctx, FilterListenerService::class.java))
                    }) { Text("Перезапустить") }
                }
            }
        }

        Card {
            Column(Modifier.padding(16.dp)) {
                Text("Скрыто сегодня", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${stats.today}", fontSize = 44.sp, fontWeight = FontWeight.Bold)
                Text("Всего за 30 дней: ${stats.total}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.padding(4.dp))
                Row {
                    OutlinedButton(onClick = openLog) { Text("Журнал") }
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = connected && enabled, onClick = {
                        val n = FilterListenerService.instance?.scanActive() ?: 0
                        val msg = if (Repo.testMode) "Найдено рекламы: $n (тестовый режим — не убрано)"
                        else "Убрано из шторки: $n"
                        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                    }) { Text("Очистить шторку") }
                }
            }
        }

        Card {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                SwitchRow("Фильтр включён", "Проверять каждое новое уведомление", enabled) {
                    enabled = it; Repo.enabled = it
                }
                SwitchRow(
                    "Тестовый режим",
                    "Только записывать в журнал, не убирать из шторки — чтобы проверить, что ничего важного не теряется",
                    testMode,
                ) { testMode = it; Repo.testMode = it }
            }
        }

        if (!battery) {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Фоновая работа", fontWeight = FontWeight.Bold)
                    Text(
                        "Чтобы система не выгружала фильтр, снимите для «Без рекламы» ограничение батареи. " +
                            "На Xiaomi/Huawei/Honor также включите «Автозапуск».",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = {
                        runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    }) { Text("Настройки батареи") }
                }
            }
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
