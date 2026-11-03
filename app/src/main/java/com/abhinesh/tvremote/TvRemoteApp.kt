package com.abhinesh.tvremote

import android.app.Application
import com.abhinesh.tvremote.data.IdentityStore
import com.abhinesh.tvremote.data.Store
import com.abhinesh.tvremote.net.Discovery
import com.abhinesh.tvremote.voice.PhoneSpeech
import com.abhinesh.tvremote.voice.TvVoice

class TvRemoteApp : Application() {
    lateinit var store: Store; private set
    lateinit var identityStore: IdentityStore; private set
    lateinit var repo: RemoteRepository; private set
    lateinit var phoneSpeech: PhoneSpeech; private set
    lateinit var tvVoice: TvVoice; private set

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        identityStore = IdentityStore(this)
        repo = RemoteRepository(store, identityStore, Discovery(this))
        phoneSpeech = PhoneSpeech(this).apply { setAutoDetectResult(store.settings.value.autoDetectWorks) }
        tvVoice = TvVoice(repo)
    }
}
