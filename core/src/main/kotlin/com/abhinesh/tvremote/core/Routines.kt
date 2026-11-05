package com.abhinesh.tvremote.core

import com.abhinesh.tvremote.core.voice.VoiceAction

/** A saved sequence of remote actions. [steps] use the codes understood by [RoutineSteps]. */
data class Routine(val id: String, val name: String, val icon: String, val steps: List<String>)

/**
 * Routine step codes:
 *   open:<shortcutId>   volume:up:<n>   volume:down:<n>   mute   unmute   home
 *   key:<keyCode>       power:off       power:on
 */
object RoutineSteps {
    val DEFAULTS = listOf(
        Routine("movie-night", "Movie Night", "movie", listOf("open:netflix")),
        Routine("gaming", "Gaming", "game", listOf("key:${KeyCodes.TV_INPUT_HDMI_2}")),
        Routine("quiet-mode", "Quiet Mode", "moon", listOf("volume:down:5")),
    )

    fun toAction(step: String, shortcuts: List<AppShortcut>): VoiceAction? {
        val p = step.split(':')
        return when (p[0]) {
            "open" -> (shortcuts + AppShortcut.DEFAULTS).firstOrNull { it.id == p.getOrNull(1) }?.let { VoiceAction.OpenApp(it) }
            "volume" -> VoiceAction.Volume(p.getOrNull(1) == "up", p.getOrNull(2)?.toIntOrNull()?.coerceIn(1, 15) ?: 3)
            "mute" -> VoiceAction.Mute
            "unmute" -> VoiceAction.Unmute
            "home" -> VoiceAction.Key(KeyCodes.HOME)
            "key" -> p.getOrNull(1)?.toIntOrNull()?.let { VoiceAction.Key(it) }
            "power" -> if (p.getOrNull(1) == "on") VoiceAction.PowerOn else VoiceAction.PowerOff
            else -> null
        }
    }

    fun describe(step: String, shortcuts: List<AppShortcut>): String {
        val p = step.split(':')
        return when (p[0]) {
            "open" -> "Open " + ((shortcuts + AppShortcut.DEFAULTS).firstOrNull { it.id == p.getOrNull(1) }?.label ?: p.getOrNull(1))
            "volume" -> if (p.getOrNull(1) == "up") "Raise volume" else "Lower volume"
            "mute" -> "Mute"
            "unmute" -> "Unmute"
            "home" -> "Go to Home"
            "key" -> when (val code = p.getOrNull(1)?.toIntOrNull()) {
                KeyCodes.TV_INPUT_HDMI_1 -> "Switch to HDMI 1"
                KeyCodes.TV_INPUT_HDMI_2 -> "Switch to HDMI 2"
                KeyCodes.TV_INPUT_HDMI_3 -> "Switch to HDMI 3"
                KeyCodes.TV_INPUT -> "Open input menu"
                null -> "Unknown step"
                else -> KeyCodes.name(code)
            }
            "power" -> if (p.getOrNull(1) == "on") "Turn TV on" else "Turn TV off"
            else -> "Unknown step"
        }
    }

    /** Short card subtitle: "Open Netflix", "Lower volume · Mute", ... */
    fun summary(r: Routine, shortcuts: List<AppShortcut>): String =
        r.steps.joinToString(" · ") { describe(it, shortcuts) }.ifEmpty { "No steps yet" }

    /** Steps offered when building a routine. */
    fun choices(shortcuts: List<AppShortcut>): List<String> =
        shortcuts.map { "open:${it.id}" } + listOf(
            "volume:down:5", "volume:up:5", "mute", "unmute", "home",
            "key:${KeyCodes.TV_INPUT_HDMI_1}", "key:${KeyCodes.TV_INPUT_HDMI_2}", "key:${KeyCodes.TV_INPUT_HDMI_3}", "key:${KeyCodes.TV_INPUT}",
            "power:off", "power:on",
        )
}
