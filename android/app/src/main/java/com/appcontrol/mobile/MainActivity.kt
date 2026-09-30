package com.appcontrol.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.http.SslError
import android.os.*
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity:ComponentActivity(){
    private lateinit var api:AppApi
    private lateinit var engine:ConnectionEngine
    private lateinit var installer:UpdateInstaller
    private lateinit var root:LinearLayout
    private lateinit var content:FrameLayout
    private lateinit var tabButton:Button
    private lateinit var pageProgress:ProgressBar
    private lateinit var pageSpinner:ProgressBar
    private lateinit var sessionStore:BrowserSessionStore
    private lateinit var watchdog:UiWatchdog
    private var restoredSession:Bundle?=null
    private var sessionLoaded=false
    private var lastBackgroundAt=0L
    private var openingShown=false
    private var openingPending=false
    private var messageRequestPending=false
    private var maintenanceDialog:AlertDialog?=null
    private var maintenanceBlocking=false
    private var maintenanceRevision=-1L
    private val statusText by lazy { TextView(this).apply { textSize=12f;setPadding(dp(8),0,dp(8),0) } }
    private var savingSession=false
    private val networkCallback=object:ConnectivityManager.NetworkCallback(){
        override fun onAvailable(network:Network){handler.post{if(initialized)statusText.text="Connection available" else if(sessionLoaded)connect()}}
        override fun onLost(network:Network){handler.post{statusText.text="Connection changed · use Test connection if needed"}}
    }
    private val handler=Handler(Looper.getMainLooper())
    private val io=Executors.newFixedThreadPool(3)
    private val tabs=mutableListOf<BrowserTab>()
    private var selected:BrowserTab?=null
    private var resumed=false
    private var initialized=false
    private var messageOpen=false
    private var requiredUpdate=false
    private val launchStartedAt=System.currentTimeMillis()
    private var loadedAt=System.currentTimeMillis()
    private var lastConfig=0L
    private var lastMessageCheck=0L
    private var fileCallback:ValueCallback<Array<Uri>>?=null
    private var pendingPushUrl:String?=null
    private var updateDialog:AlertDialog?=null
    private inner class BrowserTab(val id:String, val main:Boolean, val openedAt:Long=System.currentTimeMillis(), var lastActivity:Long=System.currentTimeMillis(), var title:String="Loading…", var failed:Boolean=false, var loadingStarted:Long=System.currentTimeMillis(), var saved:Bundle?=null, var lastUrl:String?=null, var web:WebView?=null, var progress:Int=100) {
        val view:WebView get() {
            web?.let{return it}
            val created=createWebView();web=created
            val history=saved
            if(history!=null){if(runCatching{created.restoreState(history)}.getOrNull()==null){failed=true};saved=null}
            return created
        }
    }
    // ComponentActivity does not use FragmentActivity or its legacy permission handling.
    @SuppressLint("InvalidFragmentVersionForActivityResult")
    private val notificationPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){api.heartbeat(resumed)}
    @SuppressLint("InvalidFragmentVersionForActivityResult")
    private val filePicker=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){result->fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode,result.data));fileCallback=null}
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun button(label:String,description:String=label,action:()->Unit)=Button(this).apply{text=label;contentDescription=description;isAllCaps=false;minWidth=dp(48);minimumWidth=dp(48);setPadding(dp(8),0,dp(8),0);setOnClickListener{action()}}
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);api=(application as App).api;installer=UpdateInstaller(this,api);sessionStore=BrowserSessionStore(this);watchdog=UiWatchdog(api);api.configurationListener={if(initialized){expireTabs();checkUpdate();checkMaintenance()}}
        WindowCompat.setDecorFitsSystemWindows(window,false)
        root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.WHITE)}
        ViewCompat.setOnApplyWindowInsetsListener(root){view,insets->val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets}
        val headerHeight=dp(maxOf(48,(24+20*resources.configuration.fontScale).toInt()))
        val toolbar=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(4),0,dp(4),0)}
        toolbar.addView(button("Home","Homepage"){if(!requiredUpdate&&!maintenanceBlocking)home()},LinearLayout.LayoutParams(-2,headerHeight))
        toolbar.addView(button("↻","Reload page"){if(!requiredUpdate&&!maintenanceBlocking){selected?.let{it.failed=false;showTab(it);it.view.reload()}}},LinearLayout.LayoutParams(-2,headerHeight))
        tabButton=button("Tabs 1","Choose tabs"){showTabs()};toolbar.addView(tabButton,LinearLayout.LayoutParams(-2,headerHeight))
        toolbar.addView(button("×","Close selected tab"){if(!requiredUpdate&&!maintenanceBlocking)selected?.let{closeTab(it,false)}},LinearLayout.LayoutParams(-2,headerHeight))
        toolbar.addView(button("⋮","App options"){options()},LinearLayout.LayoutParams(-2,headerHeight))
        root.addView(HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;addView(toolbar)})
        pageSpinner=ProgressBar(this,null,android.R.attr.progressBarStyleSmall).apply{visibility=View.GONE;contentDescription="Page loading"}
        root.addView(LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;addView(statusText,LinearLayout.LayoutParams(0,-2,1f));addView(pageSpinner,LinearLayout.LayoutParams(dp(20),dp(20)))},LinearLayout.LayoutParams(-1,dp(24)))
        pageProgress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{max=100;visibility=View.GONE}
        root.addView(pageProgress,LinearLayout.LayoutParams(-1,dp(3)));content=FrameLayout(this);root.addView(content,LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true){override fun handleOnBackPressed(){when{requiredUpdate->finish();maintenanceBlocking->moveTaskToBack(true);selected?.view?.canGoBack()==true->selected?.view?.goBack();selected?.main==false->selected?.let{closeTab(it,false)};else->moveTaskToBack(true)}}})
        receivePush(intent);showStartup("Connecting…")
        sessionStore.read { snapshot->runOnUiThread{restoredSession=snapshot;sessionLoaded=true;connect()} }
        runCatching{getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)}
        api.preferences.getString("last_crash",null)?.let{api.event("app_crash",code=it);api.preferences.edit().remove("last_crash").apply()}
    }
    private var connecting=false
    private fun connect(){if(connecting||!sessionLoaded)return;connecting=true;api.refresh{ok,error->
        connecting=false
        if(isFinishing)return@refresh
        if(!ok){showStartup(error?:"Unable to connect",true);return@refresh}
        if(initialized){expireTabs();checkUpdate();checkMaintenance();return@refresh}
        val dnsEnabled=api.configuration.optJSONObject("dns")?.optBoolean("enabled",true)?:true
        if(dnsEnabled&&!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)){showStartup("Update Android System WebView to use this app’s connection mode.",true);return@refresh}
        val ready={
            initialized=true;loadedAt=System.currentTimeMillis();lastConfig=loadedAt
            restoreTabs()
            if(tabs.none{it.main})addTab(api.configuration.optString("website_url",BuildConfig.WEBSITE_URL),true)
            api.event("startup",durationMs=System.currentTimeMillis()-launchStartedAt)
            checkUpdate();checkMaintenance()
            val previous=api.preferences.getInt("last_launch_version",0)
            val trigger=if(previous==0)"first_open" else if(previous!=BuildConfig.VERSION_CODE)"updated" else "app_open"
            api.sessionTrigger=trigger
            api.preferences.edit().putInt("last_launch_version",BuildConfig.VERSION_CODE).apply()
            beginOpening(trigger)
            pendingPushUrl?.let{if(!requiredUpdate&&!maintenanceBlocking)navigate(it)};pendingPushUrl=null
        }
        if(WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)){
            engine=ConnectionEngine((application as App).dns);engine.start()
            val proxy=ProxyConfig.Builder().addProxyRule("http://127.0.0.1:${engine.port}").build()
            ProxyController.getInstance().setProxyOverride(proxy,{handler.post(it)},{ready()})
        }else ready()
    }}
    private fun showStartup(text:String,retry:Boolean=false){
        content.removeAllViews();val column=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(24),dp(30),dp(24),dp(30))}
        column.addView(TextView(this).apply{this.text=text;textSize=20f;gravity=Gravity.CENTER})
        if(!retry)column.addView(ProgressBar(this))
        if(retry){column.addView(button("Retry"){connect()});column.addView(button("Test connection"){testConnection()});column.addView(button("Support"){support()})}
        content.addView(column,FrameLayout.LayoutParams(-1,-1))
    }
    @SuppressLint("SetJavaScriptEnabled") private fun createWebView():WebView=WebView(this).apply{
        importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_YES
        settings.javaScriptEnabled=true;settings.domStorageEnabled=true
        settings.allowFileAccess=false;settings.allowContentAccess=false;settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.setSupportMultipleWindows(true);settings.javaScriptCanOpenWindowsAutomatically=false
        settings.useWideViewPort=true;settings.loadWithOverviewMode=true
        if(WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE))settings.safeBrowsingEnabled=true
        settings.mediaPlaybackRequiresUserGesture=true;settings.userAgentString=settings.userAgentString+" Betna/${BuildConfig.VERSION_NAME}"
        CookieManager.getInstance().setAcceptCookie(true);CookieManager.getInstance().setAcceptThirdPartyCookies(this,true)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        setOnTouchListener{view,event->tabs.find{it.web===view}?.lastActivity=System.currentTimeMillis();false}
        webViewClient=object:WebViewClient(){
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean{
                if(!request.isForMainFrame)return false
                val tab=tabs.find{it.web===view}?:return true
                val uri=request.url
                if(uri.scheme !in listOf("http","https")){external(uri);return true}
                if(!BuildConfig.DEBUG&&uri.scheme!="https"){Toast.makeText(this@MainActivity,"This link needs HTTPS to open securely.",Toast.LENGTH_LONG).show();return true}
                if(uri.userInfo!=null){Toast.makeText(this@MainActivity,"Links containing credentials are not supported.",Toast.LENGTH_SHORT).show();return true}
                val mainHost=Uri.parse(tab.view.url?:api.configuration.optString("website_url",BuildConfig.WEBSITE_URL)).host
                if(tab.main && !uri.host.equals(mainHost,true)){addTab(uri.toString(),false);return true}
                return false
            }
            override fun onPageStarted(view:WebView,url:String?,icon:Bitmap?){tabs.find{it.web===view}?.let{it.failed=false;it.lastUrl=url;it.progress=0;it.loadingStarted=System.currentTimeMillis();if(it===selected){pageProgress.progress=0;pageProgress.visibility=View.VISIBLE;pageSpinner.visibility=View.VISIBLE;statusText.text=Uri.parse(url).host?:"Loading…"}}}
            override fun onPageFinished(view:WebView,url:String?){
                val tab=tabs.find{it.web===view}?:return;CookieManager.getInstance().flush()
                tab.lastUrl=url;tab.progress=100;if(tab===selected){pageProgress.visibility=View.GONE;pageSpinner.visibility=View.GONE}
                if(!tab.failed){api.event("page_loaded",host=runCatching{Uri.parse(url).host}.getOrNull(),durationMs=System.currentTimeMillis()-tab.loadingStarted)}
                saveSession()
            }
            override fun onReceivedError(view:WebView,request:WebResourceRequest,error:WebResourceError){if(request.isForMainFrame){val tab=tabs.find{it.web===view}?:return;tab.failed=true;api.event("page_failed",host=request.url.host,code="WEBVIEW_${error.errorCode}");if(tab===selected)showError(tab)}}
            override fun onReceivedHttpError(view:WebView,request:WebResourceRequest,response:WebResourceResponse){if(request.isForMainFrame && response.statusCode>=500){val tab=tabs.find{it.web===view}?:return;tab.failed=true;api.event("page_failed",host=request.url.host,code="HTTP_${response.statusCode}");if(tab===selected)showError(tab)}}
            override fun onReceivedSslError(view:WebView,ssl:SslErrorHandler,error:SslError){ssl.cancel();tabs.find{it.web===view}?.let{tab->tab.failed=true;api.event("page_failed",host=Uri.parse(error.url).host,code="TLS_ERROR");if(tab===selected)showError(tab)}}
            override fun onRenderProcessGone(view:WebView,detail:RenderProcessGoneDetail):Boolean {
                val tab=tabs.find{it.web===view}?:return true
                tab.saved=Bundle().also{runCatching{view.saveState(it)}};tab.failed=true;tab.web=null
                (view.parent as? ViewGroup)?.removeView(view);runCatching{view.destroy()}
                api.event("renderer_failed",code=if(detail.didCrash())"RENDERER_CRASH" else "RENDERER_MEMORY")
                if(tab===selected)showError(tab);return true
            }
            override fun onFormResubmission(view:WebView,dontResend:Message,resend:Message){dontResend.sendToTarget();Toast.makeText(this@MainActivity,"Form was not resubmitted. Return to the website to continue.",Toast.LENGTH_LONG).show()}
        }
        webChromeClient=object:WebChromeClient(){
            override fun onProgressChanged(view:WebView,value:Int){tabs.find{it.web===view}?.let{it.progress=value;if(it===selected){pageProgress.progress=value;pageProgress.visibility=if(value<100&&!it.failed)View.VISIBLE else View.GONE;pageSpinner.visibility=pageProgress.visibility}}}
            override fun onReceivedTitle(view:WebView,title:String?){tabs.find{it.web===view}?.title=title?.take(100)?:"Website"}
            override fun onCreateWindow(view:WebView,isDialog:Boolean,isUserGesture:Boolean,resultMsg:Message):Boolean{
                if(!isUserGesture)return false
                val tab=addTab(null,false)?:return false
                (resultMsg.obj as WebView.WebViewTransport).webView=tab.view;resultMsg.sendToTarget();return true
            }
            override fun onCloseWindow(window:WebView){tabs.find{it.web===window}?.let{closeTab(it,false)}}
            override fun onShowFileChooser(view:WebView,callback:ValueCallback<Array<Uri>>,params:FileChooserParams):Boolean{
                fileCallback?.onReceiveValue(null);fileCallback=callback
                return try{filePicker.launch(params.createIntent());true}catch(_:ActivityNotFoundException){fileCallback=null;false}
            }
            override fun onPermissionRequest(request:PermissionRequest){request.deny()}
        }
        setDownloadListener{url,userAgent,disposition,mime,_->downloadFile(url,userAgent,disposition,mime)}
    }
    private fun addTab(url:String?,main:Boolean):BrowserTab?{
        if(!main && tabs.size>=(api.configuration.optJSONObject("tabs")?.optInt("max_tabs",8)?:8)){Toast.makeText(this,"Close a tab before opening another.",Toast.LENGTH_LONG).show();return null}
        val tab=BrowserTab(UUID.randomUUID().toString(),main,lastUrl=url);tabs.add(tab);showTab(tab)
        if(url!=null)tab.view.loadUrl(url)
        if(!main)api.event("tab_opened",host=url?.let{Uri.parse(it).host})
        saveSession();return tab
    }
    private fun showTab(tab:BrowserTab){
        if(requiredUpdate||maintenanceBlocking)return
        selected=tab;tab.lastActivity=System.currentTimeMillis();content.removeAllViews();(tab.view.parent as? ViewGroup)?.removeView(tab.view)
        content.addView(tab.view,FrameLayout.LayoutParams(-1,-1));tabButton.text="Tabs ${tabs.size}"
        pageProgress.progress=tab.progress;pageProgress.visibility=if(tab.progress<100&&!tab.failed)View.VISIBLE else View.GONE;pageSpinner.visibility=pageProgress.visibility;statusText.text=Uri.parse(tab.web?.url?:tab.lastUrl?:"").host?:"Betna"
        if(tab.failed)showError(tab)
    }
    private fun home(){val main=tabs.firstOrNull{it.main}?:return;main.failed=false;showTab(main);main.view.loadUrl(api.configuration.optString("website_url",BuildConfig.WEBSITE_URL))}
    private fun closeTab(tab:BrowserTab,expired:Boolean){
        if(tab.main)return
        val wasSelected=selected===tab;tabs.remove(tab);tab.web?.let{(it.parent as? ViewGroup)?.removeView(it);it.stopLoading();it.destroy()};tab.web=null
        api.event(if(expired)"tab_expired" else "tab_closed")
        if(wasSelected)tabs.firstOrNull{it.main}?.let{showTab(it)}
        tabButton.text="Tabs ${tabs.size}";saveSession()
    }
    private fun expireTabs(){val config=api.configuration.optJSONObject("tabs")?:return;val now=System.currentTimeMillis()
        tabs.toList().forEach{tab->if(TabPolicy.expired(tab.main,config.optBoolean("auto_close",true),tab.openedAt,tab.lastActivity,now,config.optInt("timeout_minutes",60),config.optString("basis","opened")))closeTab(tab,true)}
    }
    private fun showTabs(){if(requiredUpdate||maintenanceBlocking)return;expireTabs();val snapshot=tabs.toList();AlertDialog.Builder(this).setTitle("Open tabs").setItems(snapshot.map{(if(it.main)"Home · " else "")+it.title+"\n"+(Uri.parse(it.web?.url?:it.lastUrl?:"").host?:"")}.toTypedArray()){_,index->snapshot.getOrNull(index)?.let{if(it in tabs)showTab(it)}}.setNegativeButton("Close",null).show()}
    private fun showError(tab:BrowserTab){
        if(tab!==selected)return;content.removeAllViews();pageProgress.visibility=View.GONE;pageSpinner.visibility=View.GONE
        val network=getSystemService(ConnectivityManager::class.java);val available=network.getNetworkCapabilities(network.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true
        val code=UUID.randomUUID().toString().take(8)
        val column=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(24),dp(24),dp(24),dp(24))}
        column.addView(TextView(this).apply{text=if(available)"Website could not be reached" else "No internet connection detected";textSize=22f;gravity=Gravity.CENTER})
        column.addView(TextView(this).apply{text="Your last action has not been automatically repeated.\nSupport reference: $code";gravity=Gravity.CENTER;setPadding(0,dp(14),0,dp(20))})
        api.event("page_failed",host=Uri.parse(tab.web?.url?:tab.lastUrl?:"").host,code="REF_$code")
        column.addView(button("Retry"){api.event("retry");tab.failed=false;val previous=tab.web;showTab(tab);if(previous!=null)tab.view.reload() else if(tab.view.url==null)tab.lastUrl?.let{tab.view.loadUrl(it)}})
        column.addView(button("Test connection"){testConnection()})
        column.addView(button("Homepage"){home()});column.addView(button("Support"){support()})
        if(tab.main){val backups=api.configuration.optJSONArray("backup_domains");if(backups!=null&&backups.length()>0)column.addView(button("Try backup homepage"){
            AlertDialog.Builder(this).setTitle("Open a backup homepage?").setMessage("This opens a new homepage. Your previous action will not be repeated, and you may need to sign in again.").setPositiveButton("Open"){_,_->tab.failed=false;showTab(tab);tab.view.loadUrl(backups.getString(0))}.setNegativeButton("Cancel",null).show()
        })}
        content.addView(column,FrameLayout.LayoutParams(-1,-1))
    }
    private fun navigate(url:String){val uri=runCatching{Uri.parse(url)}.getOrNull()?:return;if(uri.scheme !in listOf("http","https")){external(uri);return};if(uri.userInfo!=null||(!BuildConfig.DEBUG&&uri.scheme!="https"))return
        val mainHost=Uri.parse(api.configuration.optString("website_url",BuildConfig.WEBSITE_URL)).host
        if(uri.host.equals(mainHost,true)){val main=tabs.firstOrNull{it.main}?:return;showTab(main);main.view.loadUrl(url)}else addTab(url,false)
    }
    private fun external(uri:Uri){
        if(uri.scheme in listOf("javascript","file","content","data","about","blob"))return
        try{
            val parsed=if(uri.scheme=="intent")Intent.parseUri(uri.toString(),Intent.URI_INTENT_SCHEME) else null
            val fallback=parsed?.getStringExtra("browser_fallback_url")
            val intent=Intent(Intent.ACTION_VIEW,parsed?.data?:uri).addCategory(Intent.CATEGORY_BROWSABLE).apply{parsed?.`package`?.let{setPackage(it)}}
            if(intent.data?.scheme in listOf("javascript","file","content","data","about","blob"))return
            intent.flags=0
            try{startActivity(intent)}catch(_:ActivityNotFoundException){if(fallback!=null&&Uri.parse(fallback).scheme in listOf("http","https"))navigate(fallback)else Toast.makeText(this,"No installed app can open this link.",Toast.LENGTH_LONG).show()}
        }catch(_:Exception){Toast.makeText(this,"This link could not be opened.",Toast.LENGTH_SHORT).show()}
    }
    private fun support(){val url=api.configuration.optString("support_url","");if(url.isBlank())Toast.makeText(this,"Support contact has not been configured.",Toast.LENGTH_LONG).show()else try{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}catch(_:Exception){Toast.makeText(this,url,Toast.LENGTH_LONG).show()}}
    private fun downloadFile(url:String,userAgent:String?,disposition:String?,mime:String?){
        val uri=Uri.parse(url)
        if(uri.scheme !in listOf("https","http")){Toast.makeText(this,"This download format is not supported.",Toast.LENGTH_LONG).show();return}
        AlertDialog.Builder(this).setTitle("Download file?").setMessage(uri.host).setPositiveButton("Download"){_,_->
            Toast.makeText(this,"Downloading…",Toast.LENGTH_SHORT).show()
            io.execute{
                val result=runCatching{
                    val client=api.client.newBuilder().addNetworkInterceptor{chain->
                        val target=chain.request().url.toString();val builder=chain.request().newBuilder().removeHeader("Cookie")
                        CookieManager.getInstance().getCookie(target)?.let{builder.header("Cookie",it)}
                        val response=chain.proceed(builder.build())
                        response.headers("Set-Cookie").forEach{CookieManager.getInstance().setCookie(target,it,null)}
                        response
                    }.build()
                    val request=okhttp3.Request.Builder().url(url);userAgent?.let{request.header("User-Agent",it)}
                    client.newCall(request.build()).execute().use{response->
                        require(response.isSuccessful);val body=response.body?:error("Empty download")
                        val name=URLUtil.guessFileName(url,disposition,mime).replace(Regex("[^a-zA-Z0-9._-]"),"_").take(120)
                        val folder=java.io.File(cacheDir,"downloads");folder.mkdirs()
                        val file=java.io.File(folder,"${UUID.randomUUID()}-$name")
                        try{body.byteStream().use{input->file.outputStream().use{output->val buffer=ByteArray(32768);var total=0L;while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=100_000_000);output.write(buffer,0,n)}}};file}catch(e:Exception){file.delete();throw e}
                    }
                }
                runOnUiThread{result.onSuccess{file->AlertDialog.Builder(this).setTitle("Download complete").setMessage("Open or share this file to save a permanent copy.").setPositiveButton("Open"){_,_->
                    try{val fileUri=androidx.core.content.FileProvider.getUriForFile(this,"$packageName.files",file);startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(fileUri,mime?:"application/octet-stream").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}catch(_:Exception){Toast.makeText(this,"No app is available to open this file.",Toast.LENGTH_LONG).show()}
                }.setNeutralButton("Share / save"){_,_->val fileUri=androidx.core.content.FileProvider.getUriForFile(this,"$packageName.files",file);startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime?:"application/octet-stream").putExtra(Intent.EXTRA_STREAM,fileUri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Save or share"))}.setNegativeButton("Close",null).show()}.onFailure{Toast.makeText(this,"Download failed. Please retry from the website.",Toast.LENGTH_LONG).show()}}
            }
        }.setNegativeButton("Cancel",null).show()
    }
    private fun options(){AlertDialog.Builder(this).setTitle("App options").setItems(arrayOf("Notification preferences","Support","About this app","Test connection","Download Betna app")){_,which->when(which){0->{val labels=arrayOf("Promotional messages");val checked=booleanArrayOf(api.promotions);AlertDialog.Builder(this).setTitle("Notifications").setMultiChoiceItems(labels,checked){_,_,on->api.promotions=on;api.heartbeat(resumed)}.setPositiveButton("Done",null).setNeutralButton("Android permission"){_,_->if(Build.VERSION.SDK_INT>=33)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)else Toast.makeText(this,"Manage notification permission in Android settings.",Toast.LENGTH_LONG).show()}.show()};1->support();3->testConnection();4->{val url=api.configuration.optString("download_page_url");if(url.startsWith("https://"))external(Uri.parse(url))else Toast.makeText(this,"Download page is not connected yet.",Toast.LENGTH_LONG).show()};2->AlertDialog.Builder(this).setTitle(getString(com.appcontrol.mobile.R.string.app_name)).setMessage("Version ${BuildConfig.VERSION_NAME}\nInstallation: ${api.preferences.getString("installation_id","Not connected")}\n${if(api.isDemo)"Preview mode: no back office connected" else "Connected to your app management service"}").setPositiveButton("OK",null).show()}}.show()}
    private fun checkMessages(trigger:String){
        if(!resumed||messageOpen||requiredUpdate||maintenanceBlocking||messageRequestPending)return
        messageRequestPending=true
        lastMessageCheck=System.currentTimeMillis();api.messages(trigger,(System.currentTimeMillis()-loadedAt)/1000){message->messageRequestPending=false;if(message!=null&&!messageOpen&&OpeningPolicy.canDisplay(message.optBoolean("opening_only"),resumed,loadedAt,System.currentTimeMillis(),openingWindow(),requiredUpdate||maintenanceBlocking)){displayMessage(message);if(OpeningPolicy.isOpening(trigger))openingShown=true}else if(message!=null)api.event("campaign_failed",message.optString("delivery_id"),code="OPENING_WINDOW_EXPIRED")}
    }
    private fun displayMessage(message:JSONObject){
        val id=message.getString("delivery_id");val seen=getSharedPreferences("campaign-dedup",MODE_PRIVATE)
        if(seen.contains(id)||message.optLong("expires_at")<=System.currentTimeMillis()/1000)return
        if(message.optBoolean("opening_only")&&message.optLong("opening_deadline")<=System.currentTimeMillis()/1000){api.event("campaign_failed",id,code="OPENING_WINDOW_EXPIRED");return}
        seen.edit().putLong(id,System.currentTimeMillis()).apply();messageOpen=true
        val column=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(12),dp(20),dp(8))}
        column.addView(TextView(this).apply{text=message.optString("body");textSize=16f})
        val image=message.optString("image_url").takeIf{it.startsWith("https://")}
        if(image!=null){val imageView=ImageView(this).apply{adjustViewBounds=true;maxHeight=dp(200)};column.addView(imageView,0);io.execute{runCatching{api.client.newCall(okhttp3.Request.Builder().url(image).build()).execute().use{response->val source=response.body?.source()?:return@use;source.request(2_000_001);require(source.buffer.size<=2_000_000);val bytes=source.buffer.readByteArray();val opts=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true};android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,opts);require(opts.outWidth in 1..10000&&opts.outHeight in 1..10000);opts.inJustDecodeBounds=false;opts.inSampleSize=maxOf(1,maxOf(opts.outWidth,opts.outHeight)/1000);val bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,opts);runOnUiThread{imageView.setImageBitmap(bitmap)}}}}}
        val optOut=CheckBox(this).apply{text="Do not show again"};if(message.optBoolean("allow_opt_out",true))column.addView(optOut)
        var clicked=false
        val dialog=AlertDialog.Builder(this).setTitle(message.optString("title")).setView(column).setNegativeButton("Close",null)
        val action=message.optString("action_url");if(action.startsWith("http"))dialog.setPositiveButton(message.optString("button_text","Open")){_,_->clicked=true;api.event("campaign_clicked",id);navigate(action)}
        val built=dialog.create();built.setOnDismissListener{messageOpen=false;if(!clicked)api.event("campaign_dismissed",id);if(optOut.isChecked)api.optOut(id)}
        built.show()
        if(message.optString("type")=="banner")built.window?.let{it.setGravity(Gravity.TOP);it.setLayout(-1,-2);val attrs=it.attributes;attrs.y=dp(65);attrs.dimAmount=0f;it.attributes=attrs}
        api.event(if(message.optString("type")=="banner")"banner_displayed" else "popup_displayed",id)
    }
    private fun checkUpdate(){
        val release=api.configuration.optJSONObject("release")
        requiredUpdate=release?.optBoolean("required",false)==true
        if(requiredUpdate){maintenanceDialog?.setOnDismissListener(null);maintenanceDialog?.dismiss();maintenanceDialog=null}
        if(release==null){updateDialog?.dismiss();updateDialog=null;return}
        if(updateDialog?.isShowing==true)return
        val key="remind_${release.getInt("version_code")}"
        if(!requiredUpdate&&System.currentTimeMillis()<api.preferences.getLong(key,0))return
        val status=TextView(this).apply{text=release.optString("notes","A new version is available.");setPadding(dp(24),dp(16),dp(24),dp(16))}
        val builder=AlertDialog.Builder(this).setTitle(if(requiredUpdate)"Update required" else "Update available").setView(status).setPositiveButton("Update now",null).setCancelable(!requiredUpdate)
        if(!requiredUpdate)builder.setNegativeButton("Later"){_,_->api.preferences.edit().putLong(key,System.currentTimeMillis()+release.optLong("remind_hours",24)*3600000).apply()}
        else builder.setNegativeButton("Close app"){_,_->finish()}
        updateDialog=builder.create().also{dialog->dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=false;status.text="Downloading and verifying update…"
            installer.download(release){message,_->status.text=message;dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=true}
        };dialog.setOnDismissListener{updateDialog=null}}
        api.event("update_prompted")
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);if(intent.action==Intent.ACTION_MAIN&&initialized&&System.currentTimeMillis()-lastBackgroundAt>30000)beginOpening("app_open");receivePush(intent);pendingPushUrl?.let{if(initialized&&!requiredUpdate)navigate(it)};pendingPushUrl=null}
    private fun receivePush(intent:Intent){intent.getStringExtra("push_delivery")?.let{api.event("notification_opened",it);intent.removeExtra("push_delivery")};pendingPushUrl=intent.getStringExtra("push_url")?.takeIf{it.startsWith("https://")||it.startsWith("http://")};intent.removeExtra("push_url")}
    private fun openingWindow()=api.configuration.optJSONObject("campaigns")?.optInt("opening_window_seconds",5)?.coerceIn(2,30)?:5
    private fun beginOpening(trigger:String){
        if(openingPending)return;openingPending=true;openingShown=false;loadedAt=System.currentTimeMillis()
        api.sessionTrigger=trigger
        api.startOpening { api.event("app_open");api.heartbeat(true);openingPending=false;pollOpening(trigger) }
    }
    private fun pollOpening(trigger:String){
        if(isFinishing||openingShown||System.currentTimeMillis()-loadedAt>=openingWindow()*1000L)return
        checkMessages(trigger)
        handler.postDelayed({pollOpening(trigger)},1000)
    }
    private fun saveSession(){
        if(!initialized||!::sessionStore.isInitialized||savingSession)return
        if(api.configuration.optJSONObject("tabs")?.optBoolean("preserve_session",true)==false){sessionStore.clear();return}
        savingSession=true
        try {
            val snapshot=Bundle();snapshot.putInt("format",1);snapshot.putString("selected",selected?.id)
            val items=ArrayList<Bundle>()
            tabs.forEach{tab->val item=Bundle();item.putString("id",tab.id);item.putBoolean("main",tab.main);item.putLong("opened",tab.openedAt);item.putLong("activity",tab.lastActivity);item.putString("title",tab.title);item.putString("url",tab.web?.url?:tab.lastUrl)
                val history=tab.web?.let{view->Bundle().also{runCatching{view.saveState(it)}}}?:tab.saved
                if(history!=null)item.putBundle("history",history);items.add(item)
            }
            snapshot.putParcelableArrayList("tabs",items);sessionStore.write(snapshot);CookieManager.getInstance().flush()
        }finally{savingSession=false}
    }
    @Suppress("DEPRECATION") private fun restoreTabs(){
        val snapshot=restoredSession;restoredSession=null
        if(snapshot==null||snapshot.getInt("format")!=1||api.configuration.optJSONObject("tabs")?.optBoolean("preserve_session",true)==false)return
        val config=api.configuration.optJSONObject("tabs")?:JSONObject();val now=System.currentTimeMillis()
        val items=snapshot.getParcelableArrayList<Bundle>("tabs")?:return
        items.take(20).forEach{item->val main=item.getBoolean("main");val opened=item.getLong("opened",now);val activity=item.getLong("activity",now)
            if(!TabPolicy.expired(main,config.optBoolean("auto_close",true),opened,activity,now,config.optInt("timeout_minutes",60),config.optString("basis","opened"))){
                if(main&&tabs.any{it.main})return@forEach
                val url=item.getString("url")?.takeIf{Uri.parse(it).scheme in listOf("http","https")}
                tabs.add(BrowserTab(item.getString("id")?:UUID.randomUUID().toString(),main,opened,activity,item.getString("title")?:"Website",saved=item.getBundle("history"),lastUrl=url,failed=item.getBundle("history")==null))
            }
        }
        val chosen=tabs.find{it.id==snapshot.getString("selected")}?:tabs.firstOrNull{it.main}
        chosen?.let{showTab(it)}
    }
    private fun checkMaintenance(){
        val notice=api.configuration.optJSONObject("maintenance");val enabled=notice?.optBoolean("enabled",false)==true
        if(!enabled){val previously=maintenanceBlocking;maintenanceBlocking=false;maintenanceDialog?.dismiss();maintenanceDialog=null;maintenanceRevision=-1;if(previously)selected?.let{showTab(it)};return}
        val revision=api.configuration.optLong("configuration_id",api.configuration.optLong("revision"))
        maintenanceBlocking=notice!!.optBoolean("blocking",false)
        if(requiredUpdate||!resumed)return
        if(maintenanceRevision==revision)return
        maintenanceDialog?.dismiss();maintenanceRevision=revision
        val builder=AlertDialog.Builder(this).setTitle(notice.optString("title","Service notice")).setMessage(notice.optString("message")).setNeutralButton("Telegram support"){_,_->support()}.setCancelable(!maintenanceBlocking)
        if(maintenanceBlocking)builder.setPositiveButton("Check again"){_,_->maintenanceRevision=-1;api.refresh{_,_->checkMaintenance()}}.setNegativeButton("Close app"){_,_->finish()}
        else builder.setPositiveButton("Continue",null)
        maintenanceDialog=builder.create().also{it.show();it.setOnDismissListener{if(maintenanceBlocking&&!isFinishing){handler.post{maintenanceRevision=-1;checkMaintenance()}}}}
    }
    private fun testConnection(){
        val status=TextView(this).apply{text="Testing the back office, DNS, website and phone capabilities…";setPadding(dp(20),dp(20),dp(20),dp(20))}
        val dialog=AlertDialog.Builder(this).setTitle("Test connection").setView(status).setNegativeButton("Close",null).create();dialog.show()
        io.execute{val result=ConnectionDiagnostics(this,api,(application as App).dns).run();runOnUiThread{
            if(isFinishing)return@runOnUiThread
            val checks=result.getJSONArray("checks");status.text="Network: ${result.getString("network")}\n\n"+(0 until checks.length()).joinToString("\n"){i->val c=checks.getJSONObject(i);"${c.getString("name")}: ${c.getString("status")}"+(if(c.has("http_status"))" (HTTP ${c.getInt("http_status")})" else "")}+"\n\nSupport reference: ${result.getString("id")}\nWebsite checks do not submit forms or repeat your last action."
            dialog.setButton(AlertDialog.BUTTON_POSITIVE,"Telegram support"){_,_->support()}
            api.connectionReport(result){saved->if(dialog.isShowing)status.append(if(saved)"\nReport saved for support." else "\nReport is on this phone; the back office could not receive it.")}
        }}
    }
    private val ticker=object:Runnable{override fun run(){if(!resumed)return;expireTabs();api.heartbeat(true)
        val now=System.currentTimeMillis();if(initialized){
            if(now-lastConfig>=300000){lastConfig=now;api.refresh{_,_->expireTabs();checkUpdate();checkMaintenance()}}
            if(now-lastMessageCheck>=(api.configuration.optInt("poll_seconds",60)*1000L))checkMessages("interval")
        };handler.postDelayed(this,api.configuration.optInt("heartbeat_seconds",30)*1000L)}}
    private val expiryTicker=object:Runnable{override fun run(){if(!resumed)return;expireTabs();handler.postDelayed(this,1000)}}
    override fun onResume(){super.onResume();resumed=true;if(::watchdog.isInitialized)watchdog.foreground(true);handler.removeCallbacks(expiryTicker);handler.post(expiryTicker);handler.removeCallbacks(ticker);handler.post(ticker);if(initialized){expireTabs();checkUpdate();checkMaintenance();checkMessages("foreground")};if(::installer.isInitialized&&installer.pendingFile!=null&&packageManager.canRequestPackageInstalls())installer.installIfAllowed()}
    override fun onPause(){if(::watchdog.isInitialized)watchdog.foreground(false);lastBackgroundAt=System.currentTimeMillis();saveSession();resumed=false;handler.removeCallbacks(expiryTicker);handler.removeCallbacks(ticker);api.heartbeat(false);CookieManager.getInstance().flush();super.onPause()}
    override fun onStop(){saveSession();super.onStop()}
    override fun onSaveInstanceState(outState:Bundle){saveSession();super.onSaveInstanceState(outState)}
    override fun onTrimMemory(level:Int){super.onTrimMemory(level);saveSession()}
    override fun onDestroy(){handler.removeCallbacksAndMessages(null);saveSession();tabs.forEach{it.web?.destroy()};sessionStore.close();watchdog.close();api.configurationListener=null;runCatching{getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)};if(::engine.isInitialized)engine.close();io.shutdownNow();fileCallback?.onReceiveValue(null);super.onDestroy()}
}
