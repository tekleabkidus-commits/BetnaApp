package com.appcontrol.mobile
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
class UpdateInstaller(private val activity:Activity,private val api:AppApi){
    private val executor=Executors.newSingleThreadExecutor()
    var pendingFile:File?=null
    private fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    @Suppress("DEPRECATION") private fun verifyApk(file:File,release:JSONObject){
        val pm=activity.packageManager
        val flags=if(Build.VERSION.SDK_INT>=28)PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive=pm.getPackageArchiveInfo(file.absolutePath,flags)?:error("Invalid APK")
        val own=pm.getPackageInfo(activity.packageName,flags)
        require(archive.packageName==activity.packageName){"Unexpected app identity"}
        val version=if(Build.VERSION.SDK_INT>=28)archive.longVersionCode else archive.versionCode.toLong()
        require(version==release.getLong("version_code")&&version>BuildConfig.VERSION_CODE){"Unexpected app version"}
        val a=if(Build.VERSION.SDK_INT>=28)archive.signingInfo?.apkContentsSigners else archive.signatures
        val b=if(Build.VERSION.SDK_INT>=28)own.signingInfo?.apkContentsSigners else own.signatures
        require(!a.isNullOrEmpty()&&!b.isNullOrEmpty()&&a.map{digest(it.toByteArray())}.toSet()==b.map{digest(it.toByteArray())}.toSet()){"APK signing identity does not match"}
    }
    fun download(release:JSONObject,callback:(String,Boolean)->Unit){
        api.event("update_clicked")
        executor.execute{
            val result=runCatching{
                val urls=mutableListOf(release.getString("apk_url"));release.optJSONArray("backup_urls")?.let{for(i in 0 until it.length())urls.add(it.getString(i))}
                val directory=File(activity.cacheDir,"updates");directory.mkdirs();val file=File(directory,"release.apk")
                val expectedSize=release.getLong("size_bytes");require(expectedSize in 1..500_000_000)
                var complete=false
                for(url in urls){
                    try{
                        require(Uri.parse(url).scheme=="https")
                        api.client.newCall(Request.Builder().url(url).build()).execute().use{response->
                            require(response.isSuccessful&&response.request.url.isHttps)
                            val body=response.body?:error("Empty update");val hash=MessageDigest.getInstance("SHA-256");var received=0L
                            body.byteStream().use{input->file.outputStream().use{output->val buffer=ByteArray(32768);while(true){val n=input.read(buffer);if(n<0)break;received+=n;require(received<=expectedSize);hash.update(buffer,0,n);output.write(buffer,0,n)}}}
                            require(received==expectedSize);require(hash.digest().joinToString(""){"%02x".format(it)}.equals(release.getString("sha256"),true)){"Update checksum mismatch"}
                            verifyApk(file,release);complete=true
                        }
                        if(complete)break
                    }catch(_:Exception){file.delete()}
                }
                require(complete){"Unable to download a verified update"};file
            }
            activity.runOnUiThread{
                result.onSuccess{file->api.event("update_downloaded");pendingFile=file;callback("Download verified. Confirm installation in Android.",true);installIfAllowed()}
                    .onFailure{api.event("update_failed",code="download_or_verification");callback("The update could not be verified or downloaded. Please retry.",false)}
            }
        }
    }
    fun installIfAllowed(){
        val file=pendingFile?:return
        if(!activity.packageManager.canRequestPackageInstalls()){
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${activity.packageName}")));return
        }
        pendingFile=null
        val uri=FileProvider.getUriForFile(activity,"${activity.packageName}.files",file)
        activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
