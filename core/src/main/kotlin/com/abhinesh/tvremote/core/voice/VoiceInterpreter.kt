package com.abhinesh.tvremote.core.voice

import com.abhinesh.tvremote.core.AppShortcut
import com.abhinesh.tvremote.core.KeyCodes

/** What a spoken command asks the TV to do. */
sealed interface VoiceAction {
    val summary: String

    data class OpenApp(val app: AppShortcut) : VoiceAction { override val summary get() = "Open ${app.label}" }
    /** [app] null = whatever app is in front ("close it", "close this app"). Always means: go to Home. */
    data class CloseApp(val app: AppShortcut?) : VoiceAction {
        override val summary get() = if (app != null) "Close ${app.label} (go to Home)" else "Close the current app (go to Home)"
    }
    data class Volume(val up: Boolean, val steps: Int) : VoiceAction {
        override val summary get() = (if (up) "Volume up" else "Volume down") + " ×$steps"
    }
    data object Mute : VoiceAction { override val summary get() = "Mute the TV" }
    data object Unmute : VoiceAction { override val summary get() = "Unmute the TV" }
    data class Key(val keyCode: Int, val times: Int = 1) : VoiceAction {
        override val summary get() = KeyCodes.name(keyCode) + if (times > 1) " ×$times" else ""
    }
    data object PowerOff : VoiceAction { override val summary get() = "Put the TV in standby" }
    data object PowerOn : VoiceAction { override val summary get() = "Turn the TV on" }
    data class Type(val text: String) : VoiceAction { override val summary get() = "Type \"$text\"" }
    data class Search(val app: AppShortcut, val query: String) : VoiceAction {
        override val summary get() = "Search \"$query\" in ${app.label}"
    }
}

sealed interface Interpretation {
    val transcript: String

    data class Command(val action: VoiceAction, override val transcript: String) : Interpretation
    /** Not executed: needs the user to choose. [options] can be offered as buttons. */
    data class Clarify(val question: String, val options: List<VoiceAction>, override val transcript: String) : Interpretation
    data class Cancelled(override val transcript: String) : Interpretation
    data class NotUnderstood(override val transcript: String) : Interpretation
}

/** Short-term memory for "open it" and for answering a clarifying question. */
class VoiceContext(private val clock: () -> Long = System::currentTimeMillis) {
    var lastApp: AppShortcut? = null
        private set
    private var lastAppAt = 0L
    var pending: Interpretation.Clarify? = null
        private set
    private var pendingAt = 0L

    fun recentApp(): AppShortcut? = lastApp?.takeIf { clock() - lastAppAt < APP_MEMORY_MS }
    fun activePending(): Interpretation.Clarify? = pending?.takeIf { clock() - pendingAt < PENDING_MS }

    fun remember(result: Interpretation) {
        val apps = when (result) {
            is Interpretation.Command -> listOfNotNull(result.action.appOrNull())
            is Interpretation.Clarify -> result.options.mapNotNull { it.appOrNull() }.distinctBy { it.id }
            else -> emptyList()
        }
        if (apps.size == 1) { lastApp = apps[0]; lastAppAt = clock() }
        if (result is Interpretation.Clarify) { pending = result; pendingAt = clock() } else pending = null
    }

    fun clearPending() { pending = null }

    private fun VoiceAction.appOrNull() = when (this) {
        is VoiceAction.OpenApp -> app
        is VoiceAction.CloseApp -> app
        else -> null
    }

    companion object {
        const val APP_MEMORY_MS = 120_000L
        const val PENDING_MS = 30_000L
    }
}

/**
 * Turns recognised speech into one [VoiceAction] by finding *concepts* (open, close, sound,
 * up, an app, "it", a number, ...) anywhere in the sentence, in English, Telugu or Hindi
 * (including romanised and mixed speech), then resolving them together. Anything
 * contradictory or ambiguous becomes a [Interpretation.Clarify] instead of an action.
 * Purely offline; no AI service.
 */
object VoiceInterpreter {
    const val DEFAULT_VOLUME_STEPS = 3
    private const val MAX_REPEAT = 15

    fun interpret(alternatives: List<String>, shortcuts: List<AppShortcut>, context: VoiceContext): Interpretation {
        val alts = alternatives.map { it.trim() }.filter { it.isNotEmpty() }
        if (alts.isEmpty()) return Interpretation.NotUnderstood("")
        // The recogniser's best guess decides; later alternatives only help when it means nothing.
        val first = interpretOne(alts[0], shortcuts, context)
        if (first !is Interpretation.NotUnderstood) return first
        for (alt in alts.drop(1)) {
            val r = interpretOne(alt, shortcuts, context)
            if (r is Interpretation.Command || r is Interpretation.Clarify) return r
        }
        return first
    }

    fun interpretOne(utterance: String, shortcuts: List<AppShortcut>, context: VoiceContext): Interpretation {
        val text = normalize(utterance)
        if (text.isEmpty()) return Interpretation.NotUnderstood(utterance)

        TYPE_PREFIX.find(text)?.let { m -> return Interpretation.Command(VoiceAction.Type(utterance.trim().split(Regex("\\s+"), 2).getOrElse(1) { m.groupValues[1] }), utterance) }

        val hits = findHits(text, shortcuts)
        val c = hits.map { it.concept }.toSet()
        val apps = hits.mapNotNull { it.app }.distinctBy { it.id }
        val count = hits.firstNotNullOfOrNull { it.number }

        // Answer to a pending question ("Did you mean ...?" -> "yes" / "no").
        context.activePending()?.let { pending ->
            if (Concept.NO in c) return Interpretation.Cancelled(utterance)
            val yes = Concept.YES in c || (c - setOf(Concept.OK, Concept.FILLER)).isEmpty() && Concept.OK in c
            if (yes && pending.options.size == 1 && apps.isEmpty()) return Interpretation.Command(pending.options[0], utterance)
        }

        val intents = ArrayList<VoiceAction>()

        // ---- sound
        val sound = Concept.SOUND in c
        val up = Concept.LOUDER in c || (Concept.INCREASE in c) || (sound && Concept.UP in c)
        val down = Concept.QUIETER in c || (Concept.DECREASE in c) || (sound && Concept.DOWN in c)
        val unmute = Concept.UNMUTE in c || (sound && Concept.ON in c)
        val mute = Concept.MUTE in c || (sound && (Concept.OFF in c || Concept.CLOSE in c || Concept.STOP in c || Concept.PAUSE in c))
        val steps = (count ?: DEFAULT_VOLUME_STEPS).coerceIn(1, MAX_REPEAT)
        when {
            mute && unmute -> return Interpretation.Clarify("Mute or unmute?", listOf(VoiceAction.Mute, VoiceAction.Unmute), utterance)
            unmute -> intents += VoiceAction.Unmute
            mute -> intents += VoiceAction.Mute
            up && down -> return Interpretation.Clarify("Volume up or down?", listOf(VoiceAction.Volume(true, steps), VoiceAction.Volume(false, steps)), utterance)
            up -> intents += VoiceAction.Volume(true, steps)
            down -> intents += VoiceAction.Volume(false, steps)
            sound -> return Interpretation.Clarify("Volume up, volume down, or mute?",
                listOf(VoiceAction.Volume(true, steps), VoiceAction.Volume(false, steps), VoiceAction.Mute), utterance)
        }
        val soundHandled = intents.isNotEmpty()

        // ---- apps
        val tvWord = Concept.TV in c || Concept.POWER in c
        val pronoun = Concept.IT in c || Concept.THIS_APP in c
        val closeWord = !soundHandled && (Concept.CLOSE in c ||
            (apps.isNotEmpty() && (Concept.STOP in c || Concept.OFF in c)) ||
            (Concept.THIS_APP in c && (Concept.OFF in c || Concept.STOP in c)))
        val openWord = Concept.OPEN in c || (apps.isNotEmpty() && (Concept.PLAY_OR_OPEN in c || Concept.ON in c))
        if (apps.size > 1) {
            val verb = if (closeWord && !openWord) VoiceAction::CloseApp else VoiceAction::OpenApp
            return Interpretation.Clarify("Which app: ${apps.joinToString(" or ") { it.label }}?", apps.map { verb(it) }, utterance)
        }
        val app = apps.firstOrNull()
        // Words left after removing commands, app names and fillers: a title to search for.
        val query = leftover(text, hits)
        val searchVerb = openWord || Concept.SEARCH in c || Concept.PLAY_OR_OPEN in c
        when {
            closeWord && openWord -> {
                val a = app ?: context.recentApp()
                val options = if (a != null) listOf(VoiceAction.OpenApp(a), VoiceAction.CloseApp(a)) else listOf(VoiceAction.CloseApp(null))
                return Interpretation.Clarify("Did you want to open or close ${a?.label ?: "the app"}?", options, utterance)
            }
            closeWord && app != null -> intents += VoiceAction.CloseApp(app)
            closeWord && tvWord && !pronoun -> intents += VoiceAction.PowerOff // "टीवी बंद करो", "close the TV"
            closeWord -> intents += VoiceAction.CloseApp(null) // "close it", "close this app", "బంద్ చేయి"
            // "play Stranger Things on Netflix", "यूट्यूब पर पुष्पा गाने चलाओ", "search Jawan"
            searchVerb && query.isNotEmpty() && !soundHandled && !tvWord && c.none { it in STANDALONE } -> {
                if (app != null) intents += VoiceAction.Search(app, query)
                else return Interpretation.Clarify("Search for \"$query\" in which app?", shortcuts.take(4).map { VoiceAction.Search(it, query) }, utterance)
            }
            Concept.SEARCH in c && query.isEmpty() ->
                return Interpretation.Clarify("What should I search for? Say, for example, \"search Jawan on JioHotstar\".", emptyList(), utterance)
            openWord && app != null -> intents += VoiceAction.OpenApp(app)
            openWord && tvWord && !pronoun -> intents += VoiceAction.PowerOn // "TV ఆన్ చెయ్యి", "टीवी चालू करो"
            openWord && pronoun -> {
                val recent = context.recentApp()
                    ?: return Interpretation.Clarify("Which app should I open?", shortcuts.take(4).map { VoiceAction.OpenApp(it) }, utterance)
                intents += VoiceAction.OpenApp(recent)
            }
            // "go to settings", "होम पर जाओ": the verb belongs to another target, not an app.
            Concept.OPEN in c && c.none { it in STANDALONE } ->
                return Interpretation.Clarify("Which app should I open?", shortcuts.take(4).map { VoiceAction.OpenApp(it) }, utterance)
            app != null && !soundHandled -> intents += VoiceAction.OpenApp(app) // just the app name
        }

        // ---- power
        if (intents.none { it is VoiceAction.PowerOff || it is VoiceAction.PowerOn } && !soundHandled && app == null && !pronoun) {
            when {
                Concept.SLEEP in c || (Concept.OFF in c && (tvWord || c.none { it in NON_POWER })) -> intents += VoiceAction.PowerOff
                Concept.WAKE in c || (Concept.ON in c && (tvWord || c.none { it in NON_POWER })) -> intents += VoiceAction.PowerOn
            }
        }

        // ---- navigation and playback
        val closing = intents.any { it is VoiceAction.CloseApp }
        if (Concept.HOME in c && !closing) intents += VoiceAction.Key(KeyCodes.HOME)
        if (Concept.BACK in c && Concept.HOME !in c && !closing) intents += VoiceAction.Key(KeyCodes.BACK)
        val n = (count ?: 1).coerceIn(1, MAX_REPEAT)
        if (!soundHandled) {
            if (Concept.UP in c || Concept.NAV_UP in c) intents += VoiceAction.Key(KeyCodes.DPAD_UP, n)
            if (Concept.DOWN in c || Concept.NAV_DOWN in c) intents += VoiceAction.Key(KeyCodes.DPAD_DOWN, n)
        }
        if (Concept.NAV_LEFT in c) intents += VoiceAction.Key(KeyCodes.DPAD_LEFT, n)
        if (Concept.NAV_RIGHT in c) intents += VoiceAction.Key(KeyCodes.DPAD_RIGHT, n)
        if (Concept.PLAY_PAUSE in c) intents += VoiceAction.Key(KeyCodes.MEDIA_PLAY_PAUSE)
        if (Concept.FORWARD in c) intents += VoiceAction.Key(KeyCodes.MEDIA_FAST_FORWARD, n)
        if (Concept.REWIND in c) intents += VoiceAction.Key(KeyCodes.MEDIA_REWIND, n)
        if (Concept.NEXT in c) intents += VoiceAction.Key(KeyCodes.MEDIA_NEXT)
        if (Concept.PREVIOUS in c) intents += VoiceAction.Key(KeyCodes.MEDIA_PREVIOUS)
        if (Concept.PAUSE in c && !soundHandled) intents += VoiceAction.Key(KeyCodes.MEDIA_PAUSE)
        if (Concept.STOP in c && !soundHandled && !closing) intents += VoiceAction.Key(KeyCodes.MEDIA_STOP)
        if (Concept.PLAY_OR_OPEN in c && app == null && !pronoun) intents += VoiceAction.Key(KeyCodes.MEDIA_PLAY)
        if (Concept.SETTINGS in c) intents += VoiceAction.Key(KeyCodes.SETTINGS)
        if (Concept.INPUT in c) intents += VoiceAction.Key(KeyCodes.TV_INPUT)
        // The Search key opens Google Assistant on this TV (tested on the MiTV-AXFR2).
        if (Concept.ASSISTANT in c) intents += VoiceAction.Key(KeyCodes.SEARCH)
        // "OK" / "ठीक है" / "సరే" is a filler when another command was spoken.
        if (Concept.OK in c && intents.isEmpty()) intents += VoiceAction.Key(KeyCodes.DPAD_CENTER)

        val distinct = intents.distinct()
        return when (distinct.size) {
            0 -> Interpretation.NotUnderstood(utterance)
            1 -> Interpretation.Command(distinct[0], utterance)
            else -> Interpretation.Clarify(
                "I heard more than one command: ${distinct.joinToString(" and ") { it.summary }}. Which one?", distinct, utterance,
            )
        }
    }

    // ------------------------------------------------------------------ lexicon

    enum class Concept {
        OPEN, PLAY_OR_OPEN, CLOSE, OFF, ON, TV, POWER, SOUND, UP, DOWN, INCREASE, DECREASE, LOUDER, QUIETER,
        MUTE, UNMUTE, HOME, BACK, NAV_UP, NAV_DOWN, NAV_LEFT, NAV_RIGHT, OK, PAUSE, STOP, NEXT, PREVIOUS,
        FORWARD, REWIND, PLAY_PAUSE, SETTINGS, INPUT, IT, THIS_APP, YES, NO, WAKE, SLEEP, APP, NUMBER, FILLER, SEARCH,
        ASSISTANT,
    }

    /** Concepts that make a bare "off"/"on" mean something other than TV power. */
    private val NON_POWER = setOf(Concept.SOUND, Concept.APP, Concept.IT, Concept.THIS_APP, Concept.MUTE, Concept.UNMUTE)

    /** Targets that an "open"/"go to" verb can point at instead of an app. */
    private val STANDALONE = setOf(
        Concept.HOME, Concept.BACK, Concept.SETTINGS, Concept.INPUT, Concept.NAV_UP, Concept.NAV_DOWN,
        Concept.NAV_LEFT, Concept.NAV_RIGHT, Concept.NEXT, Concept.PREVIOUS, Concept.FORWARD, Concept.REWIND,
        Concept.ASSISTANT,
    )

    private val LEXICON: List<Pair<String, Concept>> = buildList {
        fun add(concept: Concept, vararg phrases: String) = phrases.forEach { add(it to concept) }
        // English and romanised Hindi/Telugu
        add(Concept.OPEN, "open", "launch", "go to", "switch to", "change to", "show me", "put on", "run", "kholo", "khol do", "kholiye", "khol", "open karo", "open kar do", "open cheyyi", "open chey", "open cheyi", "open chesi", "open cheyandi", "teruvu", "pettu")
        add(Concept.PLAY_OR_OPEN, "play", "start", "watch", "chalao", "chala do", "lagao", "laga do", "shuru karo", "dekhna", "dikhao")
        add(Concept.SEARCH, "search", "search for", "find", "look for", "khojo", "dhundho", "dhoondo", "search karo")
        add(Concept.SEARCH, "సెర్చ్", "వెతుకు", "వెతకండి", "వెతుకు")
        add(Concept.SEARCH, "खोजो", "ढूंढो", "ढूँढो", "सर्च", "सर्च करो")
        add(Concept.CLOSE, "close all", "close everything", "sab band karo", "सब बंद करो", "అన్నీ క్లోజ్")
        add(Concept.CLOSE, "close", "exit", "quit", "get out", "leave", "band karo", "band kar do", "band", "bahar niklo", "close karo", "close cheyyi", "close chey", "musey", "museyi", "moosey")
        add(Concept.OFF, "turn off", "switch off", "off", "off karo", "off cheyyi")
        add(Concept.ON, "turn on", "switch on", "on karo", "on cheyyi", "chalu karo", "chalu")
        add(Concept.TV, "tv", "t v", "television", "the tv")
        add(Concept.POWER, "power")
        add(Concept.SOUND, "volume", "sound", "audio", "awaz", "aawaz", "awaaz", "aavaz")
        add(Concept.UP, "up")
        add(Concept.DOWN, "down")
        add(Concept.INCREASE, "increase", "raise", "higher", "boost", "more", "badhao", "badha do", "badhaiye", "zyada", "jyada", "tez karo", "penchu", "penchandi", "pencu", "ekkuva")
        add(Concept.DECREASE, "decrease", "reduce", "lower", "less", "kam karo", "kam kar do", "kam", "ghatao", "dheema", "dheere", "tagginchu", "taggincu", "thagginchu", "takkuva")
        add(Concept.LOUDER, "louder", "turn it up", "turn up", "make it louder")
        add(Concept.QUIETER, "quieter", "softer", "turn it down", "turn down", "make it quieter")
        add(Concept.MUTE, "mute", "silence", "silent", "mute karo", "mute cheyyi")
        add(Concept.UNMUTE, "unmute", "un mute", "unmute karo", "unmute cheyyi", "sound on", "volume on")
        add(Concept.HOME, "home", "home screen", "main screen", "go home")
        add(Concept.BACK, "back", "go back", "previous screen", "peeche", "wapas", "vapas", "venakki", "venaki", "venukaku")
        add(Concept.NAV_UP, "move up", "go up", "scroll up", "upar", "paiki")
        add(Concept.NAV_DOWN, "move down", "go down", "scroll down", "neeche", "niche", "kindaki", "kindiki")
        add(Concept.NAV_LEFT, "left", "move left", "go left", "baayen", "bayen", "baen", "edama", "edamaki")
        add(Concept.NAV_RIGHT, "right", "move right", "go right", "daayen", "dayen", "daen", "kudi", "kudiki")
        add(Concept.OK, "ok", "okay", "select", "enter", "choose", "click", "press ok", "theek hai", "thik hai", "sare")
        add(Concept.PAUSE, "pause", "hold on", "roko", "ruko", "pause karo", "aapu", "aagu")
        add(Concept.STOP, "stop")
        add(Concept.NEXT, "next", "skip", "next episode", "agla", "agli")
        add(Concept.PREVIOUS, "previous", "prev", "previous episode", "pichla", "pichli")
        add(Concept.FORWARD, "fast forward", "forward", "skip ahead", "aage", "aage karo")
        add(Concept.REWIND, "rewind", "go back ten seconds")
        add(Concept.PLAY_PAUSE, "play pause", "play or pause", "toggle play")
        add(Concept.SETTINGS, "settings", "setting")
        add(Concept.INPUT, "input", "source", "hdmi")
        add(Concept.ASSISTANT, "assistant", "google assistant", "assistant", "hey google", "ok google", "okay google", "voice assistant")
        add(Concept.IT, "it", "this", "that", "ise", "isko", "usko", "idi", "deenni", "adi", "daanni")
        add(Concept.THIS_APP, "this app", "the app", "current app", "this application", "ye app", "is app", "ee app")
        add(Concept.YES, "yes", "yeah", "yep", "sure", "correct", "haan", "ha", "ji haan", "avunu")
        add(Concept.NO, "no", "nope", "cancel", "don't", "dont", "nahi", "nahin", "mat", "vaddu", "kaadu")
        add(Concept.WAKE, "wake up", "wake")
        add(Concept.SLEEP, "sleep", "standby", "stand by", "go to sleep")
        add(Concept.FILLER, "all right", "please", "the", "app", "application", "mein", "lo")

        // Telugu (prefix match: suffixes such as -ని, -లో, -కి attach to the word)
        add(Concept.OPEN, "ఓపెన్", "తెరువు", "తెరవు", "తెరవండి", "తెరిచి", "పెట్టు", "పెట్టండి", "చూపించు")
        add(Concept.PLAY_OR_OPEN, "ప్లే", "స్టార్ట్", "మొదలు")
        add(Concept.CLOSE, "క్లోజ్", "మూసేయి", "మూసెయ్యి", "మూసివేయి", "మూసేయండి", "ముయ్యి", "బంద్", "బయటకు", "ఎగ్జిట్")
        add(Concept.OFF, "ఆఫ్")
        add(Concept.ON, "ఆన్")
        add(Concept.TV, "టీవీ", "టివి", "టీ వీ")
        add(Concept.POWER, "పవర్")
        add(Concept.SOUND, "సౌండ్", "సౌండు", "వాల్యూమ్", "వాల్యూం", "శబ్దం", "శబ్ధం", "ధ్వని")
        add(Concept.INCREASE, "పెంచు", "పెంచండి", "పెంచి", "ఎక్కువ", "పెద్దది")
        add(Concept.DECREASE, "తగ్గించు", "తగ్గించండి", "తగ్గించి", "తగ్గు", "తక్కువ")
        add(Concept.MUTE, "మ్యూట్")
        add(Concept.UNMUTE, "అన్మ్యూట్", "అన్ మ్యూట్")
        add(Concept.HOME, "హోమ్", "హోం", "మెయిన్ స్క్రీన్")
        add(Concept.BACK, "వెనక్కి", "వెనుకకు", "వెనక్కు", "బ్యాక్")
        add(Concept.NAV_UP, "పైకి")
        add(Concept.NAV_DOWN, "కిందికి", "కిందకి", "క్రిందికి", "కింద")
        add(Concept.NAV_LEFT, "ఎడమ")
        add(Concept.NAV_RIGHT, "కుడి")
        add(Concept.OK, "ఓకే", "సెలెక్ట్", "ఎంచుకో", "సరే")
        add(Concept.PAUSE, "పాజ్", "ఆపు", "ఆగు")
        add(Concept.STOP, "స్టాప్", "ఆపేయి", "ఆపెయ్యి")
        add(Concept.NEXT, "నెక్స్ట్", "తర్వాత", "తరువాత")
        add(Concept.PREVIOUS, "మునుపటి", "ప్రీవియస్")
        add(Concept.FORWARD, "ఫార్వర్డ్", "ముందుకు")
        add(Concept.REWIND, "రివైండ్")
        add(Concept.SETTINGS, "సెట్టింగ్స్", "సెట్టింగ్")
        add(Concept.INPUT, "ఇన్పుట్")
        add(Concept.ASSISTANT, "అసిస్టెంట్", "అసిస్టెంటు", "గూగుల్ అసిస్టెంట్")
        add(Concept.IT, "ఇది", "దీన్ని", "దాన్ని", "అది", "ఇదే")
        add(Concept.THIS_APP, "ఈ యాప్", "ఈ app")
        add(Concept.YES, "అవును", "అవునండి")
        add(Concept.NO, "వద్దు", "కాదు")
        add(Concept.WAKE, "లేపు")
        add(Concept.SLEEP, "స్టాండ్బై", "నిద్ర")
        add(Concept.FILLER, "యాప్", "చెయ్యి", "చేయి", "చేయండి", "చెయ్యండి", "చెయ్")

        // Hindi (whole-word match; Hindi writes postpositions as separate words)
        add(Concept.OPEN, "खोलो", "खोल", "खोल दो", "खोलिए", "खोलिये", "ओपन", "ओपन करो", "दिखाओ", "पर जाओ")
        add(Concept.PLAY_OR_OPEN, "चलाओ", "चला दो", "चलाइए", "लगाओ", "लगा दो", "प्ले", "प्ले करो", "शुरू करो", "स्टार्ट")
        add(Concept.CLOSE, "बंद करो", "बंद कर दो", "बंद", "बंद कीजिए", "क्लोज", "क्लोज करो", "बाहर निकलो", "बाहर", "एग्जिट")
        add(Concept.OFF, "ऑफ", "ऑफ करो", "ऑफ कर दो")
        add(Concept.ON, "ऑन", "ऑन करो", "चालू करो", "चालू", "चालू कर दो")
        add(Concept.TV, "टीवी", "टी वी", "टेलीविजन")
        add(Concept.POWER, "पावर")
        add(Concept.SOUND, "आवाज", "आवाज़", "वॉल्यूम", "वोल्यूम", "वॉल्यूम को", "साउंड")
        add(Concept.INCREASE, "बढ़ाओ", "बढ़ा", "बढ़ा दो", "बढ़ाइए", "बढ़ाइये", "बढ़ाना", "ज्यादा", "ज़्यादा", "तेज", "तेज़", "तेज करो", "ऊंचा")
        add(Concept.DECREASE, "कम करो", "कम कर दो", "कम", "घटाओ", "घटा दो", "धीमा", "धीमा करो", "धीरे")
        add(Concept.MUTE, "म्यूट", "म्यूट करो", "चुप")
        add(Concept.UNMUTE, "अनम्यूट", "अन म्यूट", "अनम्यूट करो")
        add(Concept.HOME, "होम", "होम स्क्रीन", "मुख्य स्क्रीन")
        add(Concept.BACK, "पीछे", "पीछे जाओ", "वापस", "वापस जाओ", "बैक")
        add(Concept.NAV_UP, "ऊपर")
        add(Concept.NAV_DOWN, "नीचे")
        add(Concept.NAV_LEFT, "बाएं", "बायें", "बाएँ", "लेफ्ट")
        add(Concept.NAV_RIGHT, "दाएं", "दायें", "दाएँ", "राइट")
        add(Concept.OK, "ठीक है", "ओके", "चुनो", "सेलेक्ट")
        add(Concept.PAUSE, "रोको", "रुको", "पॉज", "पॉज़", "पॉज करो", "ठहरो")
        add(Concept.STOP, "स्टॉप")
        add(Concept.NEXT, "अगला", "अगली", "नेक्स्ट")
        add(Concept.PREVIOUS, "पिछला", "पिछली")
        add(Concept.FORWARD, "आगे", "आगे करो", "आगे बढ़ाओ", "फॉरवर्ड")
        add(Concept.REWIND, "रिवाइंड", "पीछे करो")
        add(Concept.SETTINGS, "सेटिंग्स", "सेटिंग")
        add(Concept.INPUT, "इनपुट")
        add(Concept.ASSISTANT, "असिस्टेंट", "असिस्टेंट", "गूगल असिस्टेंट")
        add(Concept.IT, "इसे", "इसको", "उसे", "उसको", "यह", "ये", "वो", "वह")
        add(Concept.THIS_APP, "यह ऐप", "ये ऐप", "इस ऐप", "इस ऐप को", "यह एप", "ये एप")
        add(Concept.YES, "हाँ", "हां", "हा", "जी हाँ", "जी हां", "जी")
        add(Concept.NO, "नहीं", "ना", "मत", "रहने दो", "कैंसल")
        add(Concept.WAKE, "जगाओ", "उठाओ")
        add(Concept.SLEEP, "स्टैंडबाय", "स्लीप")
        add(Concept.FILLER, "ऐप", "एप", "को", "पर", "में", "करो", "कर दो", "दो", "जरा", "ज़रा", "प्लीज")
    }.map { (p, c) -> normalize(p) to c }.filter { it.first.isNotEmpty() }

    /** Extra spoken forms for the built-in shortcuts (merged with the shortcut's own aliases). */
    private val APP_ALIASES = mapOf(
        "netflix" to listOf("netflix", "net flix", "netflicks", "net flicks", "netfix", "नेटफ्लिक्स", "नेट फ्लिक्स", "नेटफ़्लिक्स", "నెట్‌ఫ్లిక్స్", "నెట్ఫ్లిక్స్", "నెట్ ఫ్లిక్స్"),
        "youtube" to listOf("youtube", "you tube", "u tube", "utube", "यूट्यूब", "यू ट्यूब", "युट्यूब", "यूटयूब", "యూట్యూబ్", "యూ ట్యూబ్", "యుట్యూబ్", "యూటూబ్"),
        "jiohotstar" to listOf("jiohotstar", "jio hotstar", "hotstar", "hot star", "disney hotstar", "jio cinema", "जियो हॉटस्टार", "जिओ हॉटस्टार", "हॉटस्टार", "हॉट स्टार", "जियो सिनेमा", "జియో హాట్‌స్టార్", "జియోహాట్‌స్టార్", "హాట్‌స్టార్", "హాట్ స్టార్", "జియో సినిమా"),
        "primevideo" to listOf("prime video", "prime videos", "prime", "amazon prime", "amazon prime video", "amazon", "प्राइम वीडियो", "प्राइम", "अमेज़न प्राइम", "अमेजन प्राइम", "अमेज़न", "ప్రైమ్ వీడియో", "ప్రైమ్", "అమెజాన్ ప్రైమ్", "అమెజాన్"),
    )

    /** Words that are never part of a title. */
    private val STOP = setOf(
        "hey", "hi", "please", "now", "can", "you", "could", "i", "want", "to", "a", "an", "the", "on", "in", "from", "for",
        "me", "my", "and", "just", "go", "lets", "let", "some", "of", "with", "movie", "movies", "film", "cinema", "video",
        "ok", "google", "app", "tv", "show", "the movie", "watch",
        "अभी", "मुझे", "देखना", "है", "कर", "दो", "पर", "में", "को", "का", "की", "के", "और", "से", "फिल्म", "मूवी", "सिनेमा", "वीडियो", "वाला", "ज़रा", "जरा",
        "లో", "ని", "కి", "ఇప్పుడు", "నాకు", "చూడాలి", "కావాలి", "ఒక", "సినిమా", "మూవీ", "వీడియో", "పెట్టు", "చూపించు",
    ).map { normalize(it) }.toSet()

    private fun leftover(text: String, hits: List<Hit>): String {
        val taken = BooleanArray(text.length)
        for (h in hits) for (k in h.start until h.end) taken[k] = true
        return Regex("\\S+").findAll(text)
            .filter { m -> m.range.none { taken[it] } } // a Telugu word with a matched prefix ("యూట్యూబ్‌లో") is not a title
            .map { it.value }
            .filterNot { it in STOP }
            .joinToString(" ")
    }

    private data class Hit(val start: Int, val end: Int, val concept: Concept, val app: AppShortcut? = null, val number: Int? = null)

    private val NUMBER_WORDS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10, "once" to 1, "twice" to 2, "thrice" to 3,
        "ఒకటి" to 1, "రెండు" to 2, "మూడు" to 3, "నాలుగు" to 4, "ఐదు" to 5, "ఆరు" to 6, "ఏడు" to 7, "ఎనిమిది" to 8, "తొమ్మిది" to 9, "పది" to 10,
    ).mapKeys { normalize(it.key) }
    /** Hindi "दो" also means "give" (खोल दो), so Hindi numbers only count before "बार" (times). */
    private val HINDI_TIMES = Regex("(?:^| )(एक|दो|तीन|चार|पांच|पाच|छह|छः|सात|आठ|नौ|दस) बार(?: |$)")
    private val HINDI_NUMBERS = mapOf("एक" to 1, "दो" to 2, "तीन" to 3, "चार" to 4, "पांच" to 5, "पाच" to 5, "छह" to 6, "छः" to 6, "सात" to 7, "आठ" to 8, "नौ" to 9, "दस" to 10)
    private val TYPE_PREFIX = Regex("^(?:type|टाइप|టైప్)\\s+(.+)$")

    private fun isTelugu(s: String) = s.any { it in 'ఀ'..'౿' }

    private fun findHits(text: String, shortcuts: List<AppShortcut>): List<Hit> {
        val candidates = ArrayList<Hit>()
        fun scan(phrase: String, make: (Int, Int) -> Hit) {
            var from = 0
            while (true) {
                val i = text.indexOf(phrase, from)
                if (i < 0) return
                val end = i + phrase.length
                val startOk = i == 0 || text[i - 1] == ' '
                // Telugu suffixes attach to words, so only the start must be a word boundary there.
                val endOk = isTelugu(phrase) || end == text.length || text[end] == ' '
                if (startOk && endOk) candidates += make(i, end)
                from = i + 1
            }
        }
        for ((phrase, concept) in LEXICON) scan(phrase) { s, e -> Hit(s, e, concept) }
        for (sc in shortcuts) {
            val names = (sc.aliases + sc.label + APP_ALIASES[sc.id].orEmpty()).map { normalize(it) }.filter { it.length >= 2 }.distinct()
            for (name in names) {
                scan(name) { s, e -> Hit(s, e, Concept.APP, app = sc) }
                val squashed = name.replace(" ", "")
                if (squashed != name && squashed.length >= 5) scan(squashed) { s, e -> Hit(s, e, Concept.APP, app = sc) }
            }
        }
        for ((word, n) in NUMBER_WORDS) scan(word) { s, e -> Hit(s, e, Concept.NUMBER, number = n) }
        Regex("(?:^| )(\\d{1,2})(?: |$)").findAll(text).forEach { m ->
            val g = m.groups[1]!!
            g.value.toInt().takeIf { it in 1..MAX_REPEAT }?.let { candidates += Hit(g.range.first, g.range.last + 1, Concept.NUMBER, number = it) }
        }
        HINDI_TIMES.findAll(text).forEach { m ->
            // The hit spans "दो बार" so it outranks the filler reading of "दो".
            val g = m.groups[1]!!
            candidates += Hit(g.range.first, g.range.last + 1 + " बार".length, Concept.NUMBER, number = HINDI_NUMBERS[g.value])
        }
        // Longest phrases first; a span is used once ("unmute" never also counts as "mute").
        val taken = BooleanArray(text.length)
        val chosen = ArrayList<Hit>()
        for (h in candidates.sortedWith(compareByDescending<Hit> { it.end - it.start }.thenBy { it.start })) {
            if ((h.start until h.end).any { taken[it] }) continue
            for (k in h.start until h.end) taken[k] = true
            chosen += h
        }
        return chosen.sortedBy { it.start }
    }

    /** Lower-case, drop zero-width joiners and the Devanagari nukta, unify chandrabindu, strip punctuation. */
    fun normalize(s: String): String = s.lowercase()
        .replace("‌", "").replace("‍", "").replace("़", "").replace('ँ', 'ं')
        .replace("'", "")
        .replace(Regex("[^\\p{L}\\p{M}\\p{N}\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
