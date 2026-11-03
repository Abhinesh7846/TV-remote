package com.abhinesh.tvremote.core

import com.abhinesh.tvremote.core.voice.Interpretation
import com.abhinesh.tvremote.core.voice.VoiceAction
import com.abhinesh.tvremote.core.voice.VoiceContext
import com.abhinesh.tvremote.core.voice.VoiceInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VoiceInterpreterTest {
    private val apps = AppShortcut.DEFAULTS
    private var now = 1_000_000L
    private val ctx = VoiceContext { now }
    private fun app(id: String) = apps.first { it.id == id }
    private fun run(s: String): Interpretation = VoiceInterpreter.interpret(listOf(s), apps, ctx).also { ctx.remember(it) }
    private fun action(s: String): VoiceAction = assertIs<Interpretation.Command>(run(s), "for \"$s\"").action
    private fun open(id: String) = VoiceAction.OpenApp(app(id))
    private fun close(id: String?) = VoiceAction.CloseApp(id?.let { app(it) })
    private fun up(n: Int = 3) = VoiceAction.Volume(true, n)
    private fun down(n: Int = 3) = VoiceAction.Volume(false, n)
    private fun key(code: Int, times: Int = 1) = VoiceAction.Key(code, times)

    @Test fun englishAppCommands() {
        assertEquals(open("netflix"), action("Open Netflix"))
        assertEquals(open("youtube"), action("open YouTube"))
        assertEquals(open("jiohotstar"), action("launch Jio Hotstar"))
        assertEquals(open("primevideo"), action("open Amazon Prime Video"))
        assertEquals(open("youtube"), action("switch to YouTube"))
        assertEquals(open("netflix"), action("Netflix"))
        assertEquals(open("netflix"), action("play Netflix"))
        assertEquals(open("jiohotstar"), action("open jio cinema"))
    }

    @Test fun closeNeverOpens() {
        assertEquals(close("netflix"), action("close Netflix"))
        assertEquals(close("netflix"), action("exit Netflix"))
        assertEquals(close("netflix"), action("stop Netflix"))
        assertEquals(close("netflix"), action("turn off netflix"))
        assertEquals(close(null), action("close this app"))
        assertEquals(close(null), action("close it"))
        assertEquals(close(null), action("close"))
        assertEquals(close("youtube"), action("యూట్యూబ్ క్లోజ్ చెయ్యి"))
        assertEquals(close("netflix"), action("नेटफ्लिक्स बंद करो"))
        assertEquals(close(null), action("ये ऐप बंद करो"))
        assertEquals(close(null), action("ఈ యాప్ క్లోజ్ చేయి"))
        assertEquals(close("netflix"), action("netflix band karo"))
        assertEquals(close("netflix"), action("close Netflix and go home"))
    }

    @Test fun contradictionsAskInsteadOfActing() {
        val r = assertIs<Interpretation.Clarify>(run("open and close Netflix"))
        assertEquals(setOf(open("netflix"), close("netflix")), r.options.toSet())
        assertIs<Interpretation.Clarify>(run("volume up and down"))
        assertIs<Interpretation.Clarify>(run("mute unmute"))
        val two = assertIs<Interpretation.Clarify>(run("open Netflix YouTube"))
        assertEquals(listOf(open("netflix"), open("youtube")), two.options)
        assertIs<Interpretation.Clarify>(run("go home and open youtube"))
        assertIs<Interpretation.Clarify>(run("volume"))
    }

    @Test fun openItUsesRecentAppOnly() {
        val first = assertIs<Interpretation.Clarify>(run("open it"))
        assertTrue(first.question.contains("Which app"))
        action("close Netflix")
        assertEquals(open("netflix"), action("open it")) // Netflix was mentioned just now
        now += 5 * 60_000
        assertIs<Interpretation.Clarify>(run("open it")) // too long ago
    }

    @Test fun answeringAClarifyingQuestion() {
        assertIs<Interpretation.Clarify>(run("open"))
        assertEquals(open("primevideo"), action("prime video"))
        assertIs<Interpretation.Clarify>(run("Netflix ఓపెన్ చేసి క్లోజ్ చెయ్యి"))
        assertIs<Interpretation.Cancelled>(run("cancel"))
    }

    @Test fun englishSoundAndKeys() {
        assertEquals(up(), action("increase volume"))
        assertEquals(down(), action("decrease the volume"))
        assertEquals(up(5), action("volume up 5"))
        assertEquals(down(2), action("turn the volume down two"))
        assertEquals(up(), action("louder"))
        assertEquals(VoiceAction.Mute, action("mute"))
        assertEquals(VoiceAction.Unmute, action("unmute"))
        assertEquals(VoiceAction.Mute, action("turn off the sound"))
        assertEquals(key(KeyCodes.HOME), action("go home"))
        assertEquals(key(KeyCodes.BACK), action("go back"))
        assertEquals(key(KeyCodes.DPAD_DOWN, 3), action("down 3"))
        assertEquals(key(KeyCodes.DPAD_UP), action("up"))
        assertEquals(key(KeyCodes.DPAD_LEFT), action("left"))
        assertEquals(key(KeyCodes.DPAD_CENTER), action("select"))
        assertEquals(key(KeyCodes.MEDIA_PAUSE), action("pause"))
        assertEquals(key(KeyCodes.MEDIA_PLAY), action("play"))
        assertEquals(key(KeyCodes.MEDIA_FAST_FORWARD), action("fast forward"))
        assertEquals(key(KeyCodes.MEDIA_NEXT), action("next episode"))
        assertEquals(key(KeyCodes.SETTINGS), action("open settings"))
        assertEquals(VoiceAction.PowerOff, action("turn off the TV"))
        assertEquals(VoiceAction.PowerOn, action("turn on the TV"))
        assertEquals(VoiceAction.PowerOff, action("standby"))
    }

    @Test fun telugu() {
        assertEquals(up(), action("సౌండ్ పెంచు"))
        assertEquals(up(), action("వాల్యూమ్‌ని పెంచండి"))
        assertEquals(down(), action("సౌండ్ తగ్గించు"))
        assertEquals(down(2), action("వాల్యూమ్ రెండు సార్లు తగ్గించు"))
        assertEquals(open("youtube"), action("యూట్యూబ్ ఓపెన్ చెయ్యి"))
        assertEquals(open("netflix"), action("నెట్‌ఫ్లిక్స్ తెరువు"))
        assertEquals(open("jiohotstar"), action("జియో హాట్‌స్టార్ పెట్టు"))
        assertEquals(open("primevideo"), action("ప్రైమ్ వీడియో ఓపెన్ చేయి"))
        assertEquals(VoiceAction.Mute, action("మ్యూట్ చేయి"))
        assertEquals(VoiceAction.Unmute, action("అన్‌మ్యూట్ చేయి"))
        assertEquals(VoiceAction.Mute, action("సౌండ్ ఆఫ్ చెయ్యి"))
        assertEquals(key(KeyCodes.HOME), action("హోమ్ కి వెళ్ళు"))
        assertEquals(key(KeyCodes.BACK), action("వెనక్కి వెళ్ళు"))
        assertEquals(key(KeyCodes.MEDIA_PAUSE), action("పాజ్ చెయ్యి"))
        assertEquals(VoiceAction.PowerOff, action("టీవీ ఆఫ్ చెయ్యి"))
        assertEquals(VoiceAction.PowerOn, action("టీవీ ఆన్ చెయ్యి"))
    }

    @Test fun hindi() {
        assertEquals(up(), action("आवाज़ बढ़ाओ"))
        assertEquals(up(), action("आवाज बढ़ा दो"))
        assertEquals(down(), action("वॉल्यूम कम करो"))
        assertEquals(up(2), action("आवाज़ दो बार बढ़ाओ"))
        assertEquals(open("youtube"), action("यूट्यूब खोलो"))
        assertEquals(open("netflix"), action("नेटफ्लिक्स खोल दो"))
        assertEquals(open("jiohotstar"), action("हॉटस्टार चलाओ"))
        assertEquals(open("primevideo"), action("प्राइम वीडियो लगाओ"))
        assertEquals(VoiceAction.Mute, action("म्यूट करो"))
        assertEquals(VoiceAction.Mute, action("आवाज़ बंद करो"))
        assertEquals(VoiceAction.Unmute, action("अनम्यूट करो"))
        assertEquals(key(KeyCodes.HOME), action("होम पर जाओ"))
        assertEquals(key(KeyCodes.BACK), action("वापस जाओ"))
        assertEquals(key(KeyCodes.DPAD_DOWN), action("नीचे"))
        assertEquals(key(KeyCodes.MEDIA_PAUSE), action("रोको"))
        assertEquals(key(KeyCodes.MEDIA_PLAY), action("चलाओ"))
        assertEquals(VoiceAction.PowerOff, action("टीवी बंद करो"))
        assertEquals(VoiceAction.PowerOn, action("टीवी चालू करो"))
    }

    @Test fun mixedLanguageAndRomanised() {
        assertEquals(open("youtube"), action("YouTube open cheyyi"))
        assertEquals(open("netflix"), action("Netflix ఓపెన్ చెయ్యి"))
        assertEquals(up(), action("volume penchu"))
        assertEquals(up(), action("awaz badhao"))
        assertEquals(down(), action("volume kam karo"))
        assertEquals(open("youtube"), action("youtube kholo"))
        assertEquals(open("primevideo"), action("Prime video खोलो"))
        assertEquals(up(), action("sound పెంచు"))
    }

    @Test fun usesAlternativesOnlyWhenTheBestOneMeansNothing() {
        val r = VoiceInterpreter.interpret(listOf("open net flicks", "close netflix"), apps, VoiceContext())
        assertEquals(open("netflix"), assertIs<Interpretation.Command>(r).action)
        val r2 = VoiceInterpreter.interpret(listOf("hello there", "open netflix"), apps, VoiceContext())
        assertEquals(open("netflix"), assertIs<Interpretation.Command>(r2).action)
        assertIs<Interpretation.NotUnderstood>(VoiceInterpreter.interpret(listOf("what is the capital of france"), apps, VoiceContext()))
    }
}
