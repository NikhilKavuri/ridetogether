package com.nikhil.ridetogether

import com.nikhil.ridetogether.data.model.Characters
import com.nikhil.ridetogether.util.RideCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RideCodeTest {

    @Test
    fun `generated codes are always valid`() {
        repeat(2_000) {
            val code = RideCode.generate()
            assertEquals(6, code.length)
            assertTrue("$code failed validation", RideCode.isValid(code))
        }
    }

    @Test
    fun `generation is seedable and spread across the alphabet`() {
        val seen = mutableSetOf<Char>()
        val random = Random(42)
        repeat(500) { seen += RideCode.generate(random).toSet() }
        // Every character in the alphabet should turn up in 3000 draws.
        assertTrue("only saw ${seen.size} distinct characters", seen.size >= 28)
    }

    @Test
    fun `the confusable characters are never generated`() {
        val forbidden = setOf('O', '0', 'I', '1', 'L', 'S', '5')
        repeat(2_000) {
            val code = RideCode.generate()
            forbidden.forEach { assertFalse("$code contains $it", code.contains(it)) }
        }
    }

    @Test
    fun `normalise drops confusable characters rather than guessing at them`() {
        // None of these are in the alphabet, so a real code never contains one.
        // Dropping them shortens the code and fails validation, which is the
        // safe outcome -- guessing could send someone into a stranger's ride.
        assertEquals("", RideCode.normalise("o0Il5s"))
        assertFalse(RideCode.isValid(RideCode.normalise("ABC0DE")))
    }

    @Test
    fun `normalise upper-cases what riders type`() {
        assertEquals("ABC234", RideCode.normalise("abc234"))
    }

    @Test
    fun `normalise strips the separators people add`() {
        assertEquals("ABC234", RideCode.normalise("abc-234"))
        assertEquals("ABC234", RideCode.normalise(" ABC 234 "))
        assertEquals("ABC234", RideCode.normalise("ABC_234"))
    }

    @Test
    fun `normalise truncates rather than producing an over-long code`() {
        assertEquals("ABCDEF", RideCode.normalise("ABCDEFGHJK"))
    }

    @Test
    fun `validation rejects the wrong length and stray characters`() {
        assertFalse(RideCode.isValid("ABC23"))
        assertFalse(RideCode.isValid("ABC2345"))
        assertFalse(RideCode.isValid("ABC23!"))
        assertFalse(RideCode.isValid(""))
    }

    @Test
    fun `an invite link round-trips back to its code`() {
        val code = RideCode.generate()
        assertEquals(code, RideCode.codeFromLink(RideCode.inviteLink(code)))
    }

    @Test
    fun `a link with no usable code returns null rather than a wrong ride`() {
        assertNull(RideCode.codeFromLink("ridetogether://join"))
        assertNull(RideCode.codeFromLink("https://example.com"))
        assertNull(RideCode.codeFromLink("ridetogether://join?code=AB"))
    }
}

class CharactersTest {

    @Test
    fun `every character id maps to itself`() {
        Characters.ALL.forEach { assertEquals(it, Characters.byId(it.id)) }
    }

    @Test
    fun `ids from outside the range wrap instead of crashing`() {
        assertEquals(Characters.ALL[0], Characters.byId(Characters.COUNT))
        assertEquals(Characters.ALL[Characters.COUNT - 1], Characters.byId(-1))
    }

    @Test
    fun `the first two riders always get different characters`() {
        val host = Characters.firstFree(emptyList())
        val friend = Characters.firstFree(listOf(host))
        assertTrue(host != friend)
    }

    @Test
    fun `gaps left by riders who quit are reused`() {
        assertEquals(1, Characters.firstFree(listOf(0, 2, 3)))
    }

    @Test
    fun `a full ride still returns a usable character`() {
        val all = Characters.ALL.map { it.id }
        val next = Characters.firstFree(all)
        assertEquals(Characters.ALL[next], Characters.byId(next))
    }
}
