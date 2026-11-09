package ru.notiffilter.classify

/** Что известно об уведомлении. */
data class NotifInput(
    val pkg: String,
    val title: String,
    val text: String,
    val channelId: String = "",
    val channelName: String = "",
    /** Личная переписка (MessagingStyle или категория msg). */
    val isMessaging: Boolean = false,
)

enum class AppMode { AUTO, ALLOW, BLOCK }

/** Пользовательское слово: положительный вес — признак рекламы, отрицательный — признак «не реклама». */
data class CustomKeyword(val word: String, val weight: Int)

enum class Sensitivity(val threshold: Int, val title: String) {
    LOW(5, "Низкая"), MEDIUM(3, "Средняя"), HIGH(2, "Высокая")
}

data class Verdict(val isPromo: Boolean, val score: Int, val reasons: List<String>)

object PromoClassifier {

    private val spaces = Regex("\\s+")
    private val caps = Regex("[A-ZА-ЯЁ]{4,}")

    fun normalize(s: String): String = s
        .lowercase()
        .replace('ё', 'е')
        .replace(' ', ' ')
        .replace(spaces, " ")
        .trim()

    fun classify(
        input: NotifInput,
        mode: AppMode = AppMode.AUTO,
        sensitivity: Sensitivity = Sensitivity.MEDIUM,
        custom: List<CustomKeyword> = emptyList(),
    ): Verdict {
        when (mode) {
            AppMode.ALLOW -> return Verdict(false, 0, listOf("приложение в доверенных"))
            AppMode.BLOCK -> return Verdict(true, 99, listOf("приложение заблокировано"))
            AppMode.AUTO -> Unit
        }

        val raw = "${input.title}\n${input.text}"
        val text = normalize(raw)
        var score = 0
        var content = false // был ли содержательный признак рекламы
        val reasons = mutableListOf<String>()

        fun add(label: String, w: Int, meaningful: Boolean = false) {
            score += w
            if (meaningful && w > 0) content = true
            reasons += if (w > 0) label else "$label ($w)"
        }

        for (r in Rules.text) {
            if (r.regex.containsMatchIn(text)) add(r.label, r.weight, meaningful = r.weight >= 2)
        }

        val channel = normalize("${input.channelId} ${input.channelName}")
        if (channel.isNotBlank()) {
            for (r in Rules.channel) if (r.regex.containsMatchIn(channel)) add(r.label, r.weight, true)
        }

        val emoji = Rules.promoEmoji.count { raw.contains(it) }
        if (emoji > 0) add("эмодзи", if (emoji >= 2) 2 else 1)
        if (raw.count { it == '!' } >= 2) add("!!", 1)
        if (caps.containsMatchIn(raw)) add("КАПС", 1)

        for (k in custom) {
            val w = normalize(k.word)
            if (w.isNotEmpty() && text.contains(w)) add("«${k.word}»", k.weight, true)
        }

        if (input.isMessaging) add("личное сообщение", -5)

        // Одних эмодзи и восклицаний мало: нужен хотя бы один содержательный признак.
        return Verdict(content && score >= sensitivity.threshold, score, reasons)
    }
}
