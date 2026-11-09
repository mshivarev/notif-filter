package ru.notiffilter.ui

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.notiffilter.data.Repo

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)
        enableEdgeToEdge()
        setContent { AppTheme { AppRoot() } }
    }
}

private val Green = Color(0xFF2E5E4E)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF8BD0B6))
        else -> lightColorScheme(primary = Green)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private enum class Tab(val title: String, val icon: ImageVector) {
    HOME("Главная", Icons.Filled.Home),
    LOG("Журнал", Icons.Filled.Notifications),
    APPS("Приложения", Icons.AutoMirrored.Filled.List),
    RULES("Правила", Icons.Filled.Settings),
}

@Composable
private fun AppRoot() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.title) },
                    )
                }
            }
        },
    ) { pad ->
        val m = Modifier.padding(pad)
        when (Tab.entries[tab]) {
            Tab.HOME -> HomeScreen(m, openLog = { tab = Tab.LOG.ordinal })
            Tab.LOG -> LogScreen(m)
            Tab.APPS -> AppsScreen(m)
            Tab.RULES -> RulesScreen(m)
        }
    }
}

/** Загрузить данные в фоне и перезагружать при каждом изменении в Repo. */
@Composable
fun <T> repoState(initial: T, vararg keys: Any?, load: () -> T): State<T> {
    val version by Repo.changes.collectAsState()
    return produceState(initial, version, *keys) {
        value = withContext(Dispatchers.IO) { load() }
    }
}

/** Открыть contentIntent скрытого уведомления (Android 14+ требует явного разрешения запуска). */
@Suppress("DEPRECATION")
fun sendPendingIntent(ctx: Context, pi: PendingIntent): Boolean = runCatching {
    if (Build.VERSION.SDK_INT >= 34) {
        val opts = ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        pi.send(ctx, 0, null, null, null, null, opts.toBundle())
    } else {
        pi.send()
    }
}.isSuccess
