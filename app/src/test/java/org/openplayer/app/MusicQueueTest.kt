package org.openplayer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class MusicQueueTest {

    private fun queue(n: Int, start: Int = 0) = MusicQueue<Int>(Random(7)).apply { set((0 until n).toList(), start) }

    /** Karıştırma açıkken baştan sona yürür, ziyaret sırasını döner. */
    private fun walk(q: MusicQueue<Int>): List<Int> {
        val seen = arrayListOf(q.index)
        while (true) {
            val n = q.peekNext(auto = true) ?: break
            q.moveTo(n)
            seen += n
        }
        return seen
    }

    @Test
    fun linearOrderAndRepeatModes() {
        val q = queue(3)
        assertEquals(1, q.peekNext(auto = true))
        q.moveTo(2)
        assertNull(q.peekNext(auto = true))
        q.repeatMode = MusicQueue.REPEAT_ALL
        assertEquals(0, q.peekNext(auto = true))
        q.repeatMode = MusicQueue.REPEAT_ONE
        assertEquals(2, q.peekNext(auto = true))
        // Elle "sonraki", tek şarkı tekrarında da ilerler; sonda durur.
        assertNull(q.peekNext(auto = false))
        q.moveTo(0)
        assertEquals(1, q.peekNext(auto = false))
        assertNull(q.peekPrevious())
        q.repeatMode = MusicQueue.REPEAT_ALL
        assertEquals(2, q.peekPrevious())
    }

    @Test
    fun shuffleKeepsCurrentFirstAndVisitsAllOnce() {
        val q = queue(10, start = 4)
        q.shuffle = true
        assertEquals(4, q.index)
        val order = walk(q)
        assertEquals(4, order.first())
        assertEquals((0 until 10).toSet(), order.toSet())
        assertEquals(10, order.size)
        // Geri gitmek aynı karışık sırayı izler.
        assertEquals(order[order.size - 2], q.peekPrevious())
    }

    @Test
    fun shuffleOffReturnsToListOrder() {
        val q = queue(5, start = 2)
        q.shuffle = true
        q.shuffle = false
        assertEquals(3, q.peekNext(auto = true))
        assertEquals(1, q.peekPrevious())
    }

    @Test
    fun insertNextAndAppend() {
        val q = queue(3, start = 1)
        q.insertNext(9)
        assertEquals(listOf(0, 1, 9, 2), q.items)
        assertEquals(2, q.peekNext(auto = false))
        q.append(8)
        assertEquals(listOf(0, 1, 9, 2, 8), q.items)
        q.moveTo(3)
        assertEquals(4, q.peekNext(auto = false))
    }

    @Test
    fun insertNextWhileShuffled() {
        val q = queue(6, start = 3)
        q.shuffle = true
        q.insertNext(42)
        assertEquals(42, q.items[q.peekNext(auto = false)!!])
        assertEquals(3, q.current)
        assertEquals(7, walk(q).size)
    }

    @Test
    fun emptyQueue() {
        val q = MusicQueue<Int>()
        assertNull(q.peekNext(auto = true))
        assertNull(q.peekPrevious())
        assertNull(q.current)
        q.insertNext(5)
        assertEquals(5, q.current)
    }
}
