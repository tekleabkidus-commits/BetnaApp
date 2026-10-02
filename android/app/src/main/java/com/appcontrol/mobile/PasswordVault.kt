package com.appcontrol.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Decryption and writes require recent device authentication; credentials never enter telemetry. */
class PasswordVault(context:Context){
    private val file=AtomicFile(File(context.filesDir,"betna-password-vault.bin"))
    private val alias="betna.password-vault.v1"
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (store.getKey(alias,null) as? SecretKey)?.let{return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setUserAuthenticationRequired(true).setUserAuthenticationValidityDurationSeconds(30).build())
        }.generateKey()
    }
    @Synchronized fun read():JSONArray {
        val secret=key()
        if(!file.baseFile.exists())return JSONArray()
        val bytes=file.readFully();require(bytes.size in 29..1000000)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,secret,GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        return JSONArray(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8))
    }
    @Synchronized private fun write(rows:JSONArray){
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
        val stream=file.startWrite()
        try{stream.write(cipher.iv+cipher.doFinal(rows.toString().toByteArray()));file.finishWrite(stream)}catch(e:Exception){file.failWrite(stream);throw e}
    }
    fun save(origin:String,user:String,password:String){
        require(origin.startsWith("https://")&&user.length<=254&&password.length in 1..1024)
        val rows=read();val existing=(0 until rows.length()).firstOrNull{val a=rows.getJSONObject(it);a.getString("origin")==origin&&a.getString("username")==user}
        val row=JSONObject().put("id",existing?.let{rows.getJSONObject(it).getString("id")}?:UUID.randomUUID().toString()).put("origin",origin).put("username",user).put("password",password)
        if(existing!=null)rows.put(existing,row)else{require(rows.length()<100);rows.put(row)};write(rows)
    }
    fun delete(id:String){val rows=read();for(i in rows.length()-1 downTo 0)if(rows.getJSONObject(i).getString("id")==id)rows.remove(i);write(rows)}
}
