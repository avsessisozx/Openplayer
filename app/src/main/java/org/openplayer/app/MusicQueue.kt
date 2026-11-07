package org.openplayer.app

import kotlin.random.Random

/**
 * Uygulama tarafı müzik sırası: çalma düzeni, karıştırma ve tekrar kararları burada verilir.
 * mpv'nin kendi listesi kullanılmaz; [MusicDeck] sıradaki parçayı motora tek tek açar.
 */
class MusicQueue<T>(private val random: Random = Random.Default) {

    companion object {
        // Değerler kayıtlı `musicRepeat` ayarıyla uyumludur, değiştirmeyin.
        const val REPEAT_OFF = 0
        const val REPEAT_ONE = 1
        const val REPEAT_ALL = 2
    }

    private val list = ArrayList<T>()
    /** Çalma düzeni: [list] konumları. Karıştırma kapalıyken 0, 1, 2... */
    private val order = ArrayList<Int>()

    val items: List<T> get() = list
    val size: Int get() = list.size
    var index = 0
        private set
    val current: T? get() = list.getOrNull(index)

    var repeatMode = REPEAT_OFF

    var shuffle = false
        set(value) {
            if (field == value) return
            field = value
            rebuildOrder()
        }

    fun set(items: List<T>, start: Int) {
        val copy = items.toList()
        list.clear()
        list.addAll(copy)
        index = start.coerceIn(0, (copy.size - 1).coerceAtLeast(0))
        rebuildOrder()
    }

    fun clear() = set(emptyList(), 0)

    fun moveTo(i: Int) {
        if (i in list.indices) index = i
    }

    /** Çalan parçadan hemen sonra çalacak şekilde ekler. */
    fun insertNext(item: T) {
        if (list.isEmpty()) {
            set(listOf(item), 0)
            return
        }
        val at = index + 1
        val pos = order.indexOf(index)
        list.add(at, item)
        for (i in order.indices) if (order[i] >= at) order[i] = order[i] + 1
        order.add(pos + 1, at)
    }

    /** Sıranın sonuna ekler. */
    fun append(item: T) {
        if (list.isEmpty()) {
            set(listOf(item), 0)
            return
        }
        list.add(item)
        order.add(list.size - 1)
    }

    /** Sıradaki konum; yoksa null. [auto]: parça kendiliğinden bitti (tek şarkı tekrarı yalnız burada geçerli). */
    fun peekNext(auto: Boolean): Int? {
        if (list.isEmpty()) return null
        if (auto && repeatMode == REPEAT_ONE) return index
        val pos = order.indexOf(index)
        return when {
            pos + 1 < order.size -> order[pos + 1]
            repeatMode == REPEAT_ALL -> order.first()
            else -> null
        }
    }

    fun peekPrevious(): Int? {
        if (list.isEmpty()) return null
        val pos = order.indexOf(index)
        return when {
            pos > 0 -> order[pos - 1]
            repeatMode == REPEAT_ALL -> order.last()
            else -> null
        }
    }

    private fun rebuildOrder() {
        order.clear()
        if (list.isEmpty()) return
        if (!shuffle) {
            order.addAll(list.indices)
            return
        }
        // Çalan parça başta kalır; böylece karıştırma açılınca müzik kesilmez.
        order.add(index)
        order.addAll(list.indices.filter { it != index }.shuffled(random))
    }
}
