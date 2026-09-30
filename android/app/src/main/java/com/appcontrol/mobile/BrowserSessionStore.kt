package com.appcontrol.mobile

import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Browser history stays on this device, encrypted. It is never uploaded as telemetry. */
class BrowserSessionStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "browser-session.bin"))
    private val executor = Executors.newSingleThreadExecutor()
    private val alias = "betna.browser-session"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(callback: (Bundle?) -> Unit) { executor.execute {
        val restored = runCatching {
            require(file.baseFile.length() in 13..12_000_000)
            val bytes = file.openRead().use { it.readBytes() }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0,12)))
            val plain = cipher.doFinal(bytes.copyOfRange(12, bytes.size))
            val parcel = Parcel.obtain()
            try { parcel.unmarshall(plain,0,plain.size);parcel.setDataPosition(0);parcel.readBundle(javaClass.classLoader) }
            finally { parcel.recycle() }
        }.getOrNull()
        callback(restored)
    } }
    fun write(snapshot: Bundle) {
        val parcel = Parcel.obtain()
        val bytes = try { parcel.writeBundle(snapshot);parcel.marshall() } finally { parcel.recycle() }
        if (bytes.size > 10_000_000) return
        executor.execute { runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
            val encrypted = cipher.iv + cipher.doFinal(bytes)
            val output = file.startWrite()
            try { output.write(encrypted);file.finishWrite(output) } catch(e: Exception) { file.failWrite(output);throw e }
        } }
    }
    fun clear() { executor.execute { file.delete() } }
    fun close() { executor.shutdown() }
}
