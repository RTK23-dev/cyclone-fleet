package com.cyclone.mobile.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceUnderstandingTest {
    @Test fun `a well formed reply is read strictly`() {
        val u = VoiceUnderstanding.parse("""{"kind":"reply","goal":"Reply to Louella's latest message: I'm fine with it, see you later.",
            "ack":"Replying to Louella.","missing":"","confidence":0.92}""")
        assertEquals(VoiceKind.REPLY, u.kind)
        assertEquals("Reply to Louella's latest message: I'm fine with it, see you later.", u.goal)
        assertEquals("Replying to Louella.", u.ack)
    }

    @Test fun `code fences and chatter around the json are tolerated`() {
        val u = VoiceUnderstanding.parse("```json\n{\"kind\":\"task\",\"goal\":\"Set a timer for 10 minutes.\",\"ack\":\"Setting a 10 minute timer.\",\"missing\":\"\",\"confidence\":1}\n```")
        assertEquals(VoiceKind.TASK, u.kind)
        assertEquals("Set a timer for 10 minutes.", u.goal)
    }

    @Test fun `malformed means unclear`() {
        for (bad in listOf(null, "", "sure! I'll do that", "{not json", "{\"kind\":\"launch_rockets\",\"goal\":\"x\"}", "[]")) {
            assertEquals(bad, VoiceKind.UNCLEAR, VoiceUnderstanding.parse(bad).kind)
        }
    }

    @Test fun `a task without a goal or with low confidence is unclear`() {
        assertEquals(VoiceKind.UNCLEAR, VoiceUnderstanding.parse("""{"kind":"task","goal":"  ","ack":"On it","missing":"","confidence":0.9}""").kind)
        val low = VoiceUnderstanding.parse("""{"kind":"task","goal":"Text mom","ack":"","missing":"What should I tell your mom?","confidence":0.2}""")
        assertEquals(VoiceKind.UNCLEAR, low.kind)
        assertEquals("What should I tell your mom?", low.missing)
    }

    @Test fun `long lines are clipped to the spoken limits`() {
        val u = VoiceUnderstanding.parse("""{"kind":"task","goal":"Navigate home.","ack":"Okay I will now start navigating you to your home address right away",
            "missing":"","confidence":0.8}""")
        assertTrue(VoiceCopy.words(u.ack).size <= VoiceCopy.ACK_WORDS)
        val q = VoiceUnderstanding.parse("""{"kind":"unclear","goal":"","ack":"","missing":"${"who ".repeat(30)}","confidence":0.5}""")
        assertTrue(VoiceCopy.words(q.missing).size <= VoiceCopy.QUESTION_WORDS)
    }

    @Test fun `the prompt names the direct tools and carries what is open`() {
        val system = VoiceUnderstanding.systemPrompt()
        for (tool in VoiceUnderstanding.DIRECT_TOOLS) assertTrue(tool, system.contains(tool))
        val user = VoiceUnderstanding.userPrompt("yeah send it", VoiceContext(
            open = VoiceContext.OpenAsk("question", "Which Louella?", listOf("Louella M", "Louella K")),
            recentGoals = listOf("Reply to Louella")))
        assertTrue(user.contains("OPEN (question): \"Which Louella?\""))
        assertTrue(user.contains("Louella M | Louella K"))
        assertTrue(user.endsWith("SPOKEN: \"yeah send it\""))
    }

    @Test fun `a follow up joins the earlier words`() {
        val user = VoiceUnderstanding.userPrompt("that I'm late", VoiceContext(followUp = VoiceContext.FollowUp("text my mom", "What should I tell your mom?")))
        assertTrue(user.contains("EARLIER the owner said: \"text my mom\""))
        assertTrue(user.contains("Cyclone asked: \"What should I tell your mom?\""))
    }

    @Test fun `the schema is strict and lists every kind`() {
        val format = VoiceUnderstanding.responseFormat()
        val schema = format.getJSONObject("json_schema")
        assertTrue(schema.getBoolean("strict"))
        val kinds = schema.getJSONObject("schema").getJSONObject("properties").getJSONObject("kind").getJSONArray("enum")
        assertEquals(VoiceKind.entries.size, kinds.length())
        assertFalse(schema.getJSONObject("schema").getBoolean("additionalProperties"))
    }

    // ---- copy ---------------------------------------------------------------------------------------------------------

    @Test fun `done lines are short and never secret`() {
        assertEquals("Done.", VoiceCopy.done(""))
        assertEquals("Done: timer set for 10 minutes.", VoiceCopy.done("Timer set for 10 minutes. It will ring at 14:20."))
        val long = VoiceCopy.done("I opened the app and looked through " + "many ".repeat(40) + "things.")
        assertTrue(long, VoiceCopy.words(long).size <= VoiceCopy.DONE_WORDS)
        val code = VoiceCopy.done("Your verification code is 482913.")
        assertFalse(code, code.contains("482913"))
    }

    @Test fun `failed lines explain briefly`() {
        assertEquals("That didn't work.", VoiceCopy.failed(null))
        assertEquals("That didn't work: the app would not open.", VoiceCopy.failed("The app would not open. Tried twice."))
    }

    @Test fun `long messages are never read in full`() {
        assertEquals("I will be home late", VoiceCopy.message("I will be home late"))
        assertEquals("a long message", VoiceCopy.message("word ".repeat(26)))
    }

    @Test fun `options read naturally`() {
        assertEquals("", VoiceCopy.options(emptyList()))
        assertEquals("Louella M, or Louella K?", VoiceCopy.options(listOf("Louella M", "Louella K")))
        assertEquals("A, B, or C?", VoiceCopy.options(listOf("A", "B", "C", "D")))
    }

    @Test fun `spoken text is redacted`() {
        assertEquals("Your password: hidden", VoiceRedaction.spoken("Your password: hunter22"))
        assertFalse(VoiceRedaction.spoken("card 4111 1111 1111 1111").contains("4111"))
        assertFalse(VoiceRedaction.spoken("use sk-abcdefghijklmnop").contains("sk-"))
    }

    @Test fun `questions keep to fifteen words and summarise long quotes`() {
        assertEquals("Louella wrote \"I will be home late\". How should I answer?",
            VoiceMoments.question("Louella wrote \"I will be home late\". How should I answer?"))
        val long = VoiceMoments.question("Louella wrote \"${"blah ".repeat(30).trim()}\". How should I answer?")
        assertEquals("Louella wrote a long message. How should I answer?", long)
        val wordy = VoiceMoments.question("I looked at the chat and there are several people named Louella in your contacts list. Which Louella do you mean?")
        assertEquals("Which Louella do you mean?", wordy)
        assertEquals("Which one? Home, or Work?", VoiceMoments.question("Which one?", listOf("Home", "Work")))
    }
}
