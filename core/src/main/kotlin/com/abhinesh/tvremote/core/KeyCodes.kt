package com.abhinesh.tvremote.core

/** RemoteKeyCode values; identical to android.view.KeyEvent key codes. */
object KeyCodes {
    const val HOME = 3
    const val BACK = 4
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val POWER = 26
    const val SPACE = 62
    const val ENTER = 66
    const val DEL = 67
    const val MENU = 82
    const val SEARCH = 84
    const val MEDIA_PLAY_PAUSE = 85
    const val MEDIA_STOP = 86
    const val MEDIA_NEXT = 87
    const val MEDIA_PREVIOUS = 88
    const val MEDIA_REWIND = 89
    const val MEDIA_FAST_FORWARD = 90
    const val MEDIA_PLAY = 126
    const val MEDIA_PAUSE = 127
    const val VOLUME_MUTE = 164
    const val INFO = 165
    const val CHANNEL_UP = 166
    const val CHANNEL_DOWN = 167
    const val GUIDE = 172
    const val CAPTIONS = 175
    const val SETTINGS = 176
    const val TV_INPUT = 178
    const val ASSIST = 219
    const val SLEEP = 223
    const val WAKEUP = 224
    const val ALL_APPS = 284

    /** Best-effort key events for typing when the TV's keyboard ignores IME text. */
    fun forChar(c: Char): Int? = when (c) {
        in 'a'..'z' -> 29 + (c - 'a')
        in 'A'..'Z' -> 29 + (c - 'A')
        in '0'..'9' -> 7 + (c - '0')
        ' ' -> SPACE
        ',' -> 55
        '.' -> 56
        '-' -> 69
        '=' -> 70
        '/' -> 76
        '@' -> 77
        '+' -> 81
        '\'' -> 75
        ';' -> 74
        '\n' -> ENTER
        else -> null
    }

    private val names = mapOf(
        HOME to "Home", BACK to "Back", DPAD_UP to "Up", DPAD_DOWN to "Down", DPAD_LEFT to "Left",
        DPAD_RIGHT to "Right", DPAD_CENTER to "OK", VOLUME_UP to "Volume up", VOLUME_DOWN to "Volume down",
        POWER to "Power", ENTER to "Enter", DEL to "Delete", MENU to "Menu", SEARCH to "Search",
        MEDIA_PLAY_PAUSE to "Play/Pause", MEDIA_STOP to "Stop", MEDIA_NEXT to "Next", MEDIA_PREVIOUS to "Previous",
        MEDIA_REWIND to "Rewind", MEDIA_FAST_FORWARD to "Fast forward", MEDIA_PLAY to "Play", MEDIA_PAUSE to "Pause",
        VOLUME_MUTE to "Mute", INFO to "Info", CHANNEL_UP to "Channel up", CHANNEL_DOWN to "Channel down",
        GUIDE to "Guide", CAPTIONS to "Captions", SETTINGS to "Settings", TV_INPUT to "Input", ASSIST to "Assistant",
        SLEEP to "Sleep", WAKEUP to "Wake", ALL_APPS to "All apps",
    )

    fun name(code: Int): String = names[code] ?: "Key $code"
}
