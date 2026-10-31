package dev.buddy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTest {
    @Test fun noteIsTrimmedAndSingleLine() {
        assertEquals("prefers short answers", cleanNote("  prefers   short\nanswers  "))
        assertNull(cleanNote("   "))
        assertNull(cleanNote(""))
    }

    @Test fun noteLengthLimit() {
        assertNotNull(cleanNote("a".repeat(MAX_NOTE_CHARS)))
        assertNull(cleanNote("a".repeat(MAX_NOTE_CHARS + 1)))
        assertEquals(120, MAX_NOTE_CHARS)
        assertEquals(30, MAX_NOTES)
    }

    @Test fun sensitiveNotesAreRejected() {
        val sensitive = listOf(
            "my password is hunter2", "Wi-Fi PASSWORD: sunshine", "passcode for the phone",
            "card 4111 1111 1111 1111", "card number 4111-1111-1111-1111", "4111111111111111",   // card-number-like
            "the otp is 482913", "code 4829", "his pin is 1234", "bank code 12345678",           // OTP / PIN-like
            "api key sk-ant-abc", "secret recovery words", "one time code sent",
        )
        for (s in sensitive) assertNull(s, cleanNote(s))
    }

    @Test fun ordinaryPreferencesAreKept() {
        for (s in listOf("prefers short answers", "wakes up at 6:30", "calls the camera app clicker", "likes dark mode", "has 128 GB of storage", "buys on daraz")) {
            assertEquals(s, cleanNote(s))
        }
    }

    @Test fun notesAreCappedOldestFirst() {
        var notes = emptyList<String>()
        for (i in 1..MAX_NOTES + 5) notes = addNote(notes, "note number $i")
        assertEquals(MAX_NOTES, notes.size)
        assertEquals("note number 6", notes.first())
        assertEquals("note number ${MAX_NOTES + 5}", notes.last())
    }

    @Test fun repeatedNoteMovesToTheEnd() {
        val notes = addNote(addNote(addNote(emptyList(), "a note"), "b note"), "A NOTE")
        assertEquals(listOf("b note", "A NOTE"), notes)
    }

    @Test fun aliasesAreCappedOldestFirstAndLookedUp() {
        var aliases = emptyList<Alias>()
        for (i in 1..MAX_ALIASES + 3) aliases = addAlias(aliases, "phrase $i", "App $i")
        assertEquals(MAX_ALIASES, aliases.size)
        assertEquals("phrase 4", aliases.first().phrase)
        assertNull(aliasLookup(aliases, "phrase 1"))
        assertEquals("App 53", aliasLookup(aliases, "Phrase 53"))   // case and punctuation don't matter
        assertEquals("App 10", aliasLookup(aliases, "phrase 10!"))
    }

    @Test fun aliasReplacedNotDuplicated() {
        val aliases = addAlias(addAlias(emptyList(), "shopping", "Amazon"), "Shopping", "Daraz")
        assertEquals(listOf(Alias("shopping", "Daraz")), aliases)
        assertEquals(aliases, addAlias(aliases, "  ", "Daraz"))
        assertEquals(aliases, addAlias(aliases, "music", " "))
    }

    @Test fun contextIsCappedAndEmptyWhenThereIsNothing() {
        assertEquals("", memoryContext(emptyList()))
        val few = memoryContext(listOf("prefers short answers", "buys on daraz"))
        assertEquals("learned about the user (stored on their phone): prefers short answers; buys on daraz.", few)
        val many = (1..MAX_NOTES).map { "a fairly long preference note number $it that goes on and on and on and on and on and on" }
        val capped = memoryContext(many)
        assertTrue(capped.length <= MAX_MEMORY_CONTEXT)
        assertTrue(capped.endsWith("…"))
        assertEquals(600, MAX_MEMORY_CONTEXT)
    }

    @Test fun storageRoundTrips() {
        val notes = listOf("prefers short answers", "बिहान उठ्छु")
        assertEquals(notes, decodeNotes(encodeNotes(notes)))
        val aliases = listOf(Alias("shopping", "Daraz"), Alias("गाना", "Spotify"))
        assertEquals(aliases, decodeAliases(encodeAliases(aliases)))
        assertEquals(emptyList<String>(), decodeNotes(""))
        assertEquals(emptyList<Alias>(), decodeAliases(""))
        assertEquals(emptyList<Alias>(), decodeAliases("broken line\nanother"))
        assertEquals(MAX_NOTES, decodeNotes((1..50).joinToString("\n") { "n$it" }).size)
    }

    @Test fun phraseInAnOpenCommand() {
        assertEquals("shopping", appPhrase("open shopping"))
        assertEquals("shopping", appPhrase("Please open the shopping app"))
        assertEquals("daraz", appPhrase("daraz khola"))
        assertEquals("daraz", appPhrase("khol daraz"))
        assertNull(appPhrase("open maps and navigate home"))
        assertNull(appPhrase("what is the weather"))
        assertNull(appPhrase("torch khol"))
    }

    private val labels = listOf("Daraz", "YouTube", "Gmail")

    @Test fun aliasIsLearnedOnlyForAnUnrecognizedPhrase() {
        assertEquals(Alias("shopping", "Daraz"), aliasToLearn("open shopping", "Daraz", labels))
        assertNull(aliasToLearn("open daraz", "Daraz", labels))        // the name itself
        assertNull(aliasToLearn("open youtub", "YouTube", labels))     // the router would already get that
        assertNull(aliasToLearn("what is the time", "Clock", labels))  // not an open command
    }

    @Test fun learnedAliasOpensTheAppLocally() {
        val aliases = listOf(Alias("shopping", "Daraz"))
        assertEquals(LocalCommand.OpenApp("Daraz"), aliasCommand("open shopping", aliases) { labels })
        assertEquals(LocalCommand.OpenApp("Daraz"), aliasCommand("shopping khola", aliases) { labels })
        assertNull(aliasCommand("open music", aliases) { labels })
        assertNull(aliasCommand("open shopping", emptyList()) { labels })
        assertNull(aliasCommand("open shopping", aliases) { listOf("Gmail") })   // app uninstalled
    }

    @Test fun correctionsAreRecognized() {
        for (s in listOf("no", "No, not that one", "wrong app", "galat", "not that")) assertTrue(s, isCorrection(s))
        for (s in listOf("open gmail", "nothing else", "know what", "note that")) assertFalse(s, isCorrection(s))
    }

    @Test fun rememberIsANonActionTool() {
        assertFalse(isActionTool("remember"))
        val tool = (0 until AgentJson.tools().length()).map { AgentJson.tools().getJSONObject(it) }.first { it.optString("name") == "remember" }
        assertEquals("note", tool.getJSONObject("input_schema").getJSONArray("required").getString(0))
        assertTrue(tool.getBoolean("strict"))
    }

    @Test fun promptIsNepaliAndEnglishOnlyWithMemoryRules() {
        assertTrue(AGENT_PROMPT.contains("the user may speak nepali, english, or a mix"))
        assertFalse(AGENT_PROMPT.contains("hindi", ignoreCase = true))
        assertTrue(AGENT_PROMPT.contains("never passwords, codes, card numbers"))
        assertTrue(AGENT_PROMPT.contains("not one-off facts"))
    }
}
