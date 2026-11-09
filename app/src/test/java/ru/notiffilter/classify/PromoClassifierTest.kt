package ru.notiffilter.classify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromoClassifierTest {

    private fun n(title: String, text: String, channel: String = "", messaging: Boolean = false) =
        NotifInput("com.example", title, text, channelId = channel, isMessaging = messaging)

    private val promo = listOf(
        n("Скидки до 70% 🔥", "Только сегодня — распродажа электроники"),
        n("Wildberries", "Успейте купить! Цены снижены на тысячи товаров"),
        n("Ozon", "Промокод OZON500 на первый заказ — дарим 500 баллов"),
        n("Пятёрочка", "Акция недели: -30% на кофе и чай"),
        n("Самокат", "Бесплатная доставка весь день 🛒"),
        n("Яндекс Еда", "Пицца за 299 ₽ вместо 599 ₽ — только до воскресенья"),
        n("Магнит", "Персональное предложение: кешбэк 10% баллами"),
        n("Lamoda", "ЧЁРНАЯ ПЯТНИЦА уже здесь!!"),
        n("М.Видео", "Новинки по лучшим ценам — не пропустите"),
        n("Тинькофф", "Специальное предложение: кредитная карта с кэшбэком до 30%"),
        n("Сбер", "Выгодное предложение для вас — вклад под 18%"),
        n("Spotify", "Get 3 months of Premium — limited offer"),
        n("AliExpress", "11.11 Global Shopping Festival: coupons up to 50% off"),
        n("Delivery Club", "Дарим скидку 25% на следующий заказ 🎁"),
        n("Кинопоиск", "Подписка за 1 ₽ — успей оформить до конца недели"),
        n("Летуаль", "Последний шанс: распродажа заканчивается через 2 часа"),
        n("Додо Пицца", "Вторая пицца в подарок по промокоду"),
        n("СберМаркет", "Скидочная неделя: до 50% на молочку"),
        n("Яндекс Маркет", "Специально для вас подборка товаров со скидкой"),
        n("Перекрёсток", "Розыгрыш iPhone среди покупателей! Участвуйте"),
        n("Билайн", "Подключите тариф выгоднее — первый месяц бесплатно, это выгода 400 ₽"),
        n("Магазин", "Хиты продаж снова в наличии"),
        n("Новости магазина", "Загляните к нам", channel = "promo_notifications"),
        n("Обновление", "Узнайте что нового", channel = "marketing"),
        n("Burger King", "Воппер за полцены! Только до 15:00"),
    )

    private val normal = listOf(
        n("Мама", "Купи хлеба по дороге домой", messaging = true),
        n("Сбербанк", "Код подтверждения: 482913. Никому не сообщайте"),
        n("Ozon", "Ваш заказ №12345 доставлен в пункт выдачи"),
        n("Wildberries", "Заказ передан курьеру, ожидайте сегодня"),
        n("Тинькофф", "Покупка на 1 250 ₽, баланс 14 300 ₽"),
        n("Сбербанк", "Зачисление перевода 5 000 ₽ от Ивана И."),
        n("Госуслуги", "Новый вход в аккаунт с устройства Windows"),
        n("Телефон", "Пропущенный вызов от +7 999 123-45-67"),
        n("Gmail", "Иван Петров: Отчёт за квартал во вложении"),
        n("Telegram", "Аня: смотри какая скидка в магазине, пойдём завтра?", messaging = true),
        n("Яндекс Еда", "Курьер уже в пути, будет через 10 минут"),
        n("Календарь", "Встреча с командой через 15 минут"),
        n("Аэрофлот", "Регистрация на рейс SU1234 открыта"),
        n("YouTube", "Новое видео на канале: Как собрать ПК"),
        n("Погода", "Завтра похолодание до -5°"),
        n("Шагомер", "Вы прошли 10 000 шагов!"),
        n("Сбербанк", "Списание 299 ₽ за подписку СберПрайм"),
        n("Google", "Your verification code is 123456"),
        n("Почта России", "Отправление прибыло в отделение, трек-номер RA123456789RU"),
        n("WhatsApp", "Папа: Акции Газпрома опять выросли", messaging = true),
        n("Брокер", "Дивиденды по акциям зачислены на счёт"),
    )

    @Test
    fun promoDetected() {
        val missed = promo.filterNot { PromoClassifier.classify(it).isPromo }
            .map { "${it.title}: ${it.text} -> ${PromoClassifier.classify(it)}" }
        assertTrue("Пропущена реклама:\n" + missed.joinToString("\n"), missed.isEmpty())
    }

    @Test
    fun normalNotBlocked() {
        val wrong = normal.filter { PromoClassifier.classify(it).isPromo }
            .map { "${it.title}: ${it.text} -> ${PromoClassifier.classify(it)}" }
        assertTrue("Ложные срабатывания:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun appModesOverride() {
        val ad = promo.first()
        assertFalse(PromoClassifier.classify(ad, AppMode.ALLOW).isPromo)
        assertTrue(PromoClassifier.classify(normal[5], AppMode.BLOCK).isPromo)
    }

    @Test
    fun customKeywords() {
        val x = n("Магазин", "Свежая выпечка уже на полках")
        assertFalse(PromoClassifier.classify(x).isPromo)
        assertTrue(PromoClassifier.classify(x, custom = listOf(CustomKeyword("выпечка", 3))).isPromo)
        val ad = n("Магазин", "Скидка 20% на всё")
        assertFalse(PromoClassifier.classify(ad, custom = listOf(CustomKeyword("магазин", -10))).isPromo)
    }

    @Test
    fun emojiAloneIsNotEnough() {
        assertFalse(PromoClassifier.classify(n("Друзья", "🔥🔥🎉 УРА!!!")).isPromo)
    }

    @Test
    fun normalizeYo() {
        assertEquals("черная пятница", PromoClassifier.normalize("Чёрная  Пятница"))
    }
}
