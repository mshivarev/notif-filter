package ru.notiffilter.service

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.notiffilter.classify.NotifInput
import ru.notiffilter.classify.PromoClassifier
import ru.notiffilter.classify.Verdict
import ru.notiffilter.data.LogEntry
import ru.notiffilter.data.Repo
import java.util.UUID

class FilterListenerService : NotificationListenerService() {

    companion object {
        @Volatile var instance: FilterListenerService? = null; private set

        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected

        /** contentIntent скрытых уведомлений — чтобы можно было «открыть» из журнала, пока процесс жив. */
        private val intents = object : LinkedHashMap<String, PendingIntent>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PendingIntent>?) = size > 300
        }

        fun intentFor(token: String): PendingIntent? = synchronized(intents) { intents[token] }

        private val SKIP_PACKAGES = setOf("android", "com.android.systemui", "com.android.phone")
        private val SKIP_CATEGORIES = setOf(
            Notification.CATEGORY_CALL, Notification.CATEGORY_ALARM, Notification.CATEGORY_NAVIGATION,
            Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_SYSTEM, Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_STOPWATCH, Notification.CATEGORY_SERVICE, Notification.CATEGORY_ERROR,
        )
    }

    /** Последнее содержимое по ключу — чтобы обновления одного и того же уведомления не дублировались в журнале. */
    private val lastSeen = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 500
    }
    private val labels = HashMap<String, String>()

    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
    }

    override fun onListenerConnected() {
        instance = this
        _connected.value = true
    }

    override fun onListenerDisconnected() {
        instance = null
        _connected.value = false
        requestRebind(ComponentName(this, FilterListenerService::class.java))
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        _connected.value = false
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        runCatching { handle(sbn, rankingMap) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = onNotificationPosted(sbn, null)

    /** Пройтись по уже висящим уведомлениям. Возвращает, сколько скрыто. */
    fun scanActive(): Int {
        val ranking = runCatching { currentRanking }.getOrNull()
        val active = runCatching { activeNotifications }.getOrNull() ?: return 0
        return active.count { runCatching { handle(it, ranking) }.getOrDefault(false) }
    }

    /** @return true, если уведомление признано рекламой. */
    private fun handle(sbn: StatusBarNotification, rankingMap: RankingMap?): Boolean {
        if (!Repo.enabled) return false
        val pkg = sbn.packageName
        if (pkg == packageName || pkg in SKIP_PACKAGES) return false
        val n = sbn.notification
        if (sbn.isOngoing || !sbn.isClearable) return false
        if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return false
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (n.category in SKIP_CATEGORIES) return false

        val ex = n.extras
        val title = (ex.getCharSequence(Notification.EXTRA_TITLE_BIG) ?: ex.getCharSequence(Notification.EXTRA_TITLE))
            ?.toString().orEmpty()
        val parts = buildList {
            add(ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))
            add(ex.getCharSequence(Notification.EXTRA_SUB_TEXT))
            add(ex.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))
            ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.let { addAll(it) }
        }
        val text = parts.filterNotNull().map { it.toString().trim() }.filter { it.isNotEmpty() }.distinct()
            .joinToString("\n")
        if (title.isBlank() && text.isBlank()) return false

        val template = ex.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        val messaging = n.category == Notification.CATEGORY_MESSAGE ||
            template.contains("MessagingStyle") || ex.containsKey(Notification.EXTRA_MESSAGES)

        var channelName = ""
        if (rankingMap != null) {
            val r = Ranking()
            if (rankingMap.getRanking(sbn.key, r)) channelName = r.channel?.name?.toString().orEmpty()
        }

        val input = NotifInput(pkg, title, text, n.channelId.orEmpty(), channelName, messaging)
        val verdict = PromoClassifier.classify(input, Repo.modeOf(pkg), Repo.sensitivity, Repo.keywords)

        val sig = (title + "\u0000" + text).hashCode()
        val duplicate = synchronized(lastSeen) { lastSeen.put(sbn.key, sig) == sig }

        if (verdict.isPromo) {
            val dismiss = !Repo.testMode
            if (dismiss) {
                cancelNotification(sbn.key)
                cancelOrphanSummary(sbn)
            }
            if (!duplicate) record(sbn, input, verdict, blocked = true, dismissed = dismiss)
            return true
        }
        if (!duplicate) record(sbn, input, verdict, blocked = false, dismissed = false)
        return false
    }

    private fun record(sbn: StatusBarNotification, input: NotifInput, v: Verdict, blocked: Boolean, dismissed: Boolean) {
        val token = UUID.randomUUID().toString()
        sbn.notification.contentIntent?.let { synchronized(intents) { intents[token] = it } }
        Repo.log(
            LogEntry(
                id = 0, token = token, pkg = input.pkg, app = appLabel(input.pkg),
                title = input.title, text = input.text,
                channel = input.channelName.ifBlank { input.channelId },
                time = sbn.postTime, score = v.score, reasons = v.reasons.joinToString(", "),
                blocked = blocked, dismissed = dismissed,
            )
        )
    }

    /** Если после удаления рекламы в группе остался только «заголовок группы» — убираем и его. */
    private fun cancelOrphanSummary(sbn: StatusBarNotification) {
        val group = sbn.notification.group ?: return
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        val same = active.filter { it.packageName == sbn.packageName && it.notification.group == group && it.key != sbn.key }
        val children = same.filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
        if (children.isEmpty()) {
            same.filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 }
                .forEach { cancelNotification(it.key) }
        }
    }

    private fun appLabel(pkg: String): String = labels.getOrPut(pkg) { appLabel(this, pkg) }
}

fun appLabel(ctx: Context, pkg: String): String = try {
    val pm = ctx.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
} catch (_: PackageManager.NameNotFoundException) {
    pkg
}
