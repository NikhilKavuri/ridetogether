package com.nikhil.ridetogether.util

import kotlin.random.Random

/**
 * Six-character ride codes, meant to survive being read aloud over an intercom
 * or typed with gloves on.
 *
 * The alphabet excludes the pairs people actually confuse when reading a code
 * aloud -- O/0, I/1, S/5 -- so a correctly transcribed code can never contain
 * one. [normalise] therefore only upper-cases and drops anything outside the
 * alphabet: an O that someone typed anyway falls out, the code comes up short,
 * and they get "check the code" rather than being silently sent to a different
 * ride by a guess about what they meant.
 */
object RideCode {

    const val LENGTH = 6

    /** 30 characters: A-Z and 2-9, minus the confusable ones. */
    private const val ALPHABET = "ABCDEFGHJKLMNPQRTUVWXYZ2346789"

    fun generate(random: Random = Random.Default): String =
        (1..LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    /** Always call this on user input before hitting the database. */
    fun normalise(input: String): String = input
        .uppercase()
        .filter { ALPHABET.contains(it) }
        .take(LENGTH)

    fun isValid(code: String): Boolean =
        code.length == LENGTH && code.all { ALPHABET.contains(it) }

    /** Deep link the share sheet sends to friends. */
    fun inviteLink(code: String): String = "ridetogether://join?code=$code"

    fun codeFromLink(link: String): String? {
        val marker = "code="
        val i = link.indexOf(marker)
        if (i < 0) return null
        val raw = link.substring(i + marker.length).takeWhile { it.isLetterOrDigit() }
        val normalised = normalise(raw)
        return if (isValid(normalised)) normalised else null
    }
}
