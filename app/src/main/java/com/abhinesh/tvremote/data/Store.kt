package com.abhinesh.tvremote.data

import android.content.Context
import com.abhinesh.tvremote.core.AppShortcut
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class SavedTv(
    val name: String,
    val host: String,
    val port: Int = 6466,
    val pairPort: Int = 6467,
    /** Hex SHA-256 of the TV's public key, learned on first connection (trust on first use). */
    val pinSha256: String? = null,
    val model: String? = null,
    val btMac: String? = null,
    /** Optional Wi-Fi/Ethernet MAC entered by the user for Wake-on-LAN. */
    val wolMac: String? = null,
)

data class Settings(
    val haptics: Boolean = true,
    val imeEnabled: Boolean = true,
    val tvVoiceEnabled: Boolean = true,
    val speechLanguage: String = "en-IN",
    val onDeviceOnly: Boolean = false,
    /** "auto", "ondevice", "default" or a flattened ComponentName of a speech service. */
    val speechEngine: String = "auto",
    /** Result of trying language auto-detection: null = untested. */
    val autoDetectWorks: Boolean? = null,
    /** Send Wake-on-LAN when the TV is off the network (only useful if the TV supports it). */
    val useWakeOnLan: Boolean = false,
    val swipePad: Boolean = false,
    val keepScreenOn: Boolean = false,
)

/** Plain SharedPreferences storage; the only secret (the private key) lives in [IdentityStore]. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("tvremote", Context.MODE_PRIVATE)

    private val _tv = MutableStateFlow(loadTv())
    private val _settings = MutableStateFlow(loadSettings())
    private val _shortcuts = MutableStateFlow(loadShortcuts())
    val tv: StateFlow<SavedTv?> = _tv.asStateFlow()
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val shortcuts: StateFlow<List<AppShortcut>> = _shortcuts.asStateFlow()

    fun saveTv(tv: SavedTv?) {
        _tv.value = tv
        prefs.edit().apply {
            if (tv == null) remove("tv") else putString("tv", JSONObject().apply {
                put("name", tv.name); put("host", tv.host); put("port", tv.port); put("pairPort", tv.pairPort)
                put("pin", tv.pinSha256 ?: ""); put("model", tv.model ?: ""); put("bt", tv.btMac ?: ""); put("wol", tv.wolMac ?: "")
            }.toString())
        }.apply()
    }

    fun updateTv(transform: (SavedTv) -> SavedTv) {
        _tv.value?.let { saveTv(transform(it)) }
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        val s = transform(_settings.value)
        _settings.value = s
        prefs.edit().putString("settings", JSONObject().apply {
            put("haptics", s.haptics); put("ime", s.imeEnabled); put("tvVoice", s.tvVoiceEnabled)
            put("lang", s.speechLanguage); put("onDevice", s.onDeviceOnly); put("swipe", s.swipePad); put("screenOn", s.keepScreenOn)
            put("engine", s.speechEngine); put("autoDetect", s.autoDetectWorks?.toString() ?: ""); put("wol", s.useWakeOnLan)
        }.toString()).apply()
    }

    fun saveShortcuts(list: List<AppShortcut>) {
        _shortcuts.value = list
        prefs.edit().putString("shortcuts", JSONArray().apply {
            list.forEach { s ->
                put(JSONObject().apply {
                    put("id", s.id); put("label", s.label); put("pkg", s.packageName)
                    put("targets", JSONArray(s.targets)); put("color", s.colorArgb); put("on", s.onColorArgb)
                    put("aliases", JSONArray(s.aliases))
                })
            }
        }.toString()).apply()
    }

    fun resetShortcuts() = saveShortcuts(AppShortcut.DEFAULTS)

    private fun loadTv(): SavedTv? = prefs.getString("tv", null)?.let { raw ->
        runCatching {
            val o = JSONObject(raw)
            SavedTv(
                name = o.getString("name"), host = o.getString("host"), port = o.optInt("port", 6466),
                pairPort = o.optInt("pairPort", 6467), pinSha256 = o.optString("pin").ifEmpty { null },
                model = o.optString("model").ifEmpty { null }, btMac = o.optString("bt").ifEmpty { null },
                wolMac = o.optString("wol").ifEmpty { null },
            )
        }.getOrNull()
    }

    private fun loadSettings(): Settings = prefs.getString("settings", null)?.let { raw ->
        runCatching {
            val o = JSONObject(raw)
            Settings(
                haptics = o.optBoolean("haptics", true), imeEnabled = o.optBoolean("ime", true),
                tvVoiceEnabled = o.optBoolean("tvVoice", true), speechLanguage = o.optString("lang", "en-IN"),
                onDeviceOnly = o.optBoolean("onDevice", false), swipePad = o.optBoolean("swipe", false),
                keepScreenOn = o.optBoolean("screenOn", false),
                speechEngine = o.optString("engine", "auto").ifEmpty { "auto" },
                autoDetectWorks = o.optString("autoDetect").toBooleanStrictOrNull(),
                useWakeOnLan = o.optBoolean("wol", false),
            )
        }.getOrNull()
    } ?: Settings()

    private fun loadShortcuts(): List<AppShortcut> = prefs.getString("shortcuts", null)?.let { raw ->
        runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                AppShortcut(
                    id = o.getString("id"), label = o.getString("label"), packageName = o.optString("pkg"),
                    targets = o.getJSONArray("targets").let { t -> (0 until t.length()).map { t.getString(it) } },
                    colorArgb = o.getLong("color"), onColorArgb = o.optLong("on", 0xFFFFFFFF),
                    aliases = o.optJSONArray("aliases")?.let { t -> (0 until t.length()).map { t.getString(it) } } ?: emptyList(),
                )
            }
        }.getOrNull()
    } ?: AppShortcut.DEFAULTS
}
