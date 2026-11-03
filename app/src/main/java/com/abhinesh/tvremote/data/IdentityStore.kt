package com.abhinesh.tvremote.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.abhinesh.tvremote.core.tls.ClientIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The phone's pairing identity (RSA key + self-signed certificate). The private key is stored
 * encrypted with an AES-256-GCM key that never leaves the Android Keystore (hardware-backed
 * where the phone supports it), and app data is excluded from backups.
 */
class IdentityStore(context: Context) {
    private val prefs = context.getSharedPreferences("identity", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    @Volatile private var cached: ClientIdentity? = null

    suspend fun get(): ClientIdentity = cached ?: mutex.withLock {
        cached ?: withContext(Dispatchers.Default) { load() ?: create() }.also { cached = it }
    }

    /** Deletes the identity; every TV must be paired again afterwards. */
    suspend fun reset() = mutex.withLock {
        cached = null
        prefs.edit().clear().apply()
        runCatching { keystore().deleteEntry(ALIAS) }
    }

    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun wrapKey(): SecretKey {
        (keystore().getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    private fun create(): ClientIdentity {
        val identity = ClientIdentity.generate("TV Remote (${Build.MANUFACTURER} ${Build.MODEL})")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrapKey()) }
        val sealed = cipher.iv + cipher.doFinal(identity.privateKey.encoded)
        prefs.edit()
            .putString("key", Base64.encodeToString(sealed, Base64.NO_WRAP))
            .putString("cert", Base64.encodeToString(identity.certificate.encoded, Base64.NO_WRAP))
            .apply()
        return identity
    }

    private fun load(): ClientIdentity? = runCatching {
        val sealed = Base64.decode(prefs.getString("key", null) ?: return null, Base64.NO_WRAP)
        val certDer = Base64.decode(prefs.getString("cert", null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, sealed, 0, 12))
        }
        val pkcs8 = cipher.doFinal(sealed, 12, sealed.size - 12)
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(certDer.inputStream()) as X509Certificate
        ClientIdentity(key, cert)
    }.getOrNull()

    private companion object {
        const val ALIAS = "tvremote_identity_wrap"
    }
}
