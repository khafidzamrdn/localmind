package com.localmind.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingParserTest {

    @Test
    fun separatesAStandardThinkingBlock() {
        val raw = " thinking\nWeigh the options.\n response\n\nFinal answer."
        val split = ThinkingParser.split(raw)

        assertEquals("Weigh the options.", split.reasoning)
        assertEquals("Final answer.", split.answer)
        assertTrue(split.hasReasoning)
        assertFalse(split.isThinking)
    }

    @Test
    fun anUnclosedBlockIsStillThinkingWhileStreaming() {
        val split = ThinkingParser.split(" thinking\nStill working")

        assertEquals("Still working", split.reasoning)
        assertEquals("", split.answer)
        assertTrue(split.isThinking)
    }

    @Test
    fun plainTextIsANormalAnswer() {
        val split = ThinkingParser.split("Just an answer")

        assertEquals("Just an answer", split.answer)
        assertEquals("", split.reasoning)
        assertFalse(split.hasTags)
    }

    @Test
    fun aClosingMarkerWithoutOpeningIsPrefilledThinking() {
        val split = ThinkingParser.split("Reasoning text\n response\n\nAnswer")

        assertEquals("Reasoning text", split.reasoning)
        assertEquals("Answer", split.answer)
    }

    @Test
    fun supportsMultipleBlocksAndAlternateMarkers() {
        val split = ThinkingParser.split("<|think|>one<|/think|>middle<thinking>two</thinking>end")

        assertEquals("one\n\ntwo", split.reasoning)
        assertEquals("middleend", split.answer)
    }

    @Test
    fun stripReasoningKeepsOnlyTheAnswer() {
        assertEquals(
            "Answer",
            ThinkingParser.stripReasoning(" thinking\nReason\n response\n\nAnswer"),
        )
        assertEquals("untouched", ThinkingParser.stripReasoning("untouched"))
    }
}
