package com.appcontrol.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
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
import androidx.webkit.WebViewCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

// Uses ComponentActivity directly; there is no FragmentActivity permission delegation.
@SuppressLint("InvalidFragmentVersionForActivityResult")
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
    private fun button(label:String,description:String=label,action:()->Unit)=BrowserUi.button(this,label,action=action).apply{contentDescription=description}
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);api=(application as App).api;installer=UpdateInstaller(this,api);sessionStore=BrowserSessionStore(this);watchdog=UiWatchdog(api);vault=PasswordVault(this);locationReporter=LocationReporter(this,api)
        api.configurationListener={if(initialized){processCommands(false);applyVpn(false);expireTabs();checkUpdate();checkMaintenance();locationReporter.stop();if(resumed)locationReporter.start()}}
        (application as App).vpn.listener={ok->if(!ok&&initialized)showVpnBlocked()}
        WindowCompat.setDecorFitsSystemWindows(window,false)
        root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.WHITE)}
        ViewCompat.setOnApplyWindowInsetsListener(root){view,insets->val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets}
        val toolbar=FrameLayout(this).apply{setBackgroundColor(Color.WHITE)}
        val row=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(8),0,dp(8),0)}
        val logo=ImageView(this).apply{setImageResource(R.drawable.betna_logo);scaleType=ImageView.ScaleType.FIT_CENTER;contentDescription="Betna"}
        row.addView(logo,LinearLayout.LayoutParams(dp(28),dp(28)))
        statusText.apply{textSize=11f;setTextColor(BrowserUi.muted);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;setOnClickListener{siteInfo()}}
        row.addView(statusText,LinearLayout.LayoutParams(0,-2,1f))
        fun tool(label:String,desc:String,action:()->Unit)=button(label,desc,action).apply{minWidth=0;minimumWidth=0;textSize=22f;setPadding(0,0,0,0)
            val icon=when(label){"⌂"->R.drawable.ic_browser_home;"↻"->R.drawable.ic_browser_reload;"×"->R.drawable.ic_browser_close;"⋮"->R.drawable.ic_browser_menu;else->null}
            if(icon!=null){text="";setCompoundDrawablesWithIntrinsicBounds(null,getDrawable(icon),null,null);setPadding(0,dp(12),0,dp(12))}
            background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x18333333),BrowserUi.surface(this@MainActivity,Color.TRANSPARENT,10),null)}
        row.addView(tool("⌂","Homepage"){if(!requiredUpdate&&!maintenanceBlocking&&!connectionBlocked)home()},LinearLayout.LayoutParams(dp(44),dp(48)))
        row.addView(tool("↻","Reload page"){if(!requiredUpdate&&!maintenanceBlocking&&!connectionBlocked)selected?.let{it.failed=false;showTab(it);it.view.reload()}},LinearLayout.LayoutParams(dp(44),dp(48)))
        tabButton=tool("1","Choose tabs"){showTabs()}.apply{textSize=16f;background=BrowserUi.surface(this@MainActivity,Color.rgb(244,245,249),12)}
        row.addView(tabButton,LinearLayout.LayoutParams(dp(44),dp(40)))
        row.addView(tool("×","Close selected tab"){if(!requiredUpdate&&!maintenanceBlocking&&!connectionBlocked)selected?.let{closeTab(it,false)}},LinearLayout.LayoutParams(dp(44),dp(48)))
        row.addView(tool("⋮","App options"){options()},LinearLayout.LayoutParams(dp(44),dp(48)))
        toolbar.addView(row,FrameLayout.LayoutParams(-1,dp(56)))
        pageProgress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{max=100;visibility=View.INVISIBLE;progressTintList=android.content.res.ColorStateList.valueOf(BrowserUi.red)}
        toolbar.addView(pageProgress,FrameLayout.LayoutParams(-1,dp(2),Gravity.BOTTOM))
        pageSpinner=ProgressBar(this,null,android.R.attr.progressBarStyleSmall).apply{visibility=View.GONE;contentDescription="Loading website";indeterminateTintList=android.content.res.ColorStateList.valueOf(BrowserUi.red)}
        toolbar.addView(pageSpinner,FrameLayout.LayoutParams(dp(14),dp(14),Gravity.START or Gravity.BOTTOM).apply{leftMargin=dp(36);bottomMargin=dp(7)})
        root.addView(toolbar,LinearLayout.LayoutParams(-1,dp(56)))
        content=FrameLayout(this).apply{setBackgroundColor(Color.rgb(247,248,252))};root.addView(content,LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true){override fun handleOnBackPressed(){when{customView!=null->hideFullscreen();connectionBlocked->moveTaskToBack(true);requiredUpdate->finish();maintenanceBlocking->moveTaskToBack(true);selected?.view?.canGoBack()==true->selected?.view?.goBack();selected?.main==false->selected?.let{closeTab(it,false)};else->moveTaskToBack(true)}}})
        receivePush(intent);showStartup("Connecting…")
        sessionStore.read { snapshot->runOnUiThread{restoredSession=snapshot;sessionLoaded=true;connect()} }
        runCatching{getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)}
        api.preferences.getString("last_crash",null)?.let{api.event("app_crash",code=it);api.preferences.edit().remove("last_crash").apply()}
    }
    override fun onConfigurationChanged(newConfig:Configuration){
        super.onConfigurationChanged(newConfig)
        // Retain the live WebViews, including game state, while the window changes size.
        tabs.forEach{it.web?.invalidate()}
        if(::root.isInitialized){root.requestLayout();ViewCompat.requestApplyInsets(root)}
    }
    private lateinit var locationReporter:LocationReporter
    private lateinit var vault:PasswordVault
    private var unlockAction:(()->Unit)?=null
    private var connectionBlocked=false
    private var appliedVpn=""
    private var vaultPrompt=false
    private var customView:View?=null
    private var customViewCallback:WebChromeClient.CustomViewCallback?=null
    @SuppressLint("InvalidFragmentVersionForActivityResult")
    private val vpnPermission=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
        api.preferences.edit().putBoolean("vpn_permission_asked",true).apply()
        if(result.resultCode==RESULT_OK){applyVpn(true)}else if(api.configuration.optJSONObject("vpn")?.optBoolean("enabled")==true){showVpnBlocked()}else requestLocation()
    }
    @SuppressLint("InvalidFragmentVersionForActivityResult")
    private val locationPermission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){locationReporter.start();api.heartbeat(resumed)}
    @SuppressLint("InvalidFragmentVersionForActivityResult")
    private val vaultUnlock=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
        val action=unlockAction;unlockAction=null
        if(result.resultCode==RESULT_OK)runCatching{action?.invoke()}.onFailure{Toast.makeText(this,"Vault could not be unlocked. Check your phone screen lock and try again.",Toast.LENGTH_LONG).show()}
    }
    private var connecting=false
    private fun connect(){if(connecting||!sessionLoaded)return;connecting=true;api.refresh{ok,error->
        connecting=false
        if(isFinishing)return@refresh
        if(!ok){showStartup(error?:"Unable to connect",true);return@refresh}
        if(initialized){applyVpn(true);processCommands(true);expireTabs();checkUpdate();checkMaintenance();return@refresh}
        val dnsEnabled=api.configuration.optJSONObject("dns")?.optBoolean("enabled",true)?:true
        if(dnsEnabled&&!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)){showStartup("Update Android System WebView to use this app’s connection mode.",true);return@refresh}
        val ready={
            processCommands(true);initialized=true;loadedAt=System.currentTimeMillis();lastConfig=loadedAt
            requestInitialPermissions();restoreTabs()
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
            engine=ConnectionEngine((application as App).dns,{(application as App).vpn.usable()},{(application as App).vpn.websiteSocket()});engine.start()
            val proxy=ProxyConfig.Builder().addProxyRule("http://127.0.0.1:${engine.port}").build()
            ProxyController.getInstance().setProxyOverride(proxy,{handler.post(it)},{applyVpn(true){ok->if(ok)ready()else showVpnBlocked()}})
        }else applyVpn(true){ok->if(ok)ready()else showVpnBlocked()}
    }}
    private fun showStartup(text:String,retry:Boolean=false){
        content.removeAllViews();val column=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(24),dp(30),dp(24),dp(30))}
        column.addView(TextView(this).apply{this.text=text;textSize=20f;gravity=Gravity.CENTER})
        if(!retry)column.addView(ProgressBar(this))
        if(retry){column.addView(button("Retry"){connect()});column.addView(button("Test connection"){testConnection()});column.addView(button("Support"){support()})}
        content.addView(column,FrameLayout.LayoutParams(-1,-1))
    }
    @SuppressLint("SetJavaScriptEnabled") private fun createWebView():WebView=WebView(this).apply{
        importantForAutofill=if(api.preferences.getBoolean("betna_vault",true))View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS else View.IMPORTANT_FOR_AUTOFILL_YES
        settings.javaScriptEnabled=true;settings.domStorageEnabled=true;settings.cacheMode=WebSettings.LOAD_DEFAULT
        setBackgroundColor(Color.WHITE)
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
                if(!(application as App).vpn.usable()){showVpnBlocked();return true}
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
                tab.lastUrl=url;tab.progress=100;if(tab===selected){pageProgress.visibility=View.INVISIBLE;pageSpinner.visibility=View.GONE}
                if(!tab.failed){api.event("page_loaded",host=runCatching{Uri.parse(url).host}.getOrNull(),durationMs=System.currentTimeMillis()-tab.loadingStarted)}
                installPasswordBridge(view);installViewportFix(view);saveSession()
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
            override fun onProgressChanged(view:WebView,value:Int){tabs.find{it.web===view}?.let{it.progress=value;if(it===selected){pageProgress.progress=value;pageProgress.visibility=if(value<100&&!it.failed)View.VISIBLE else View.INVISIBLE;pageSpinner.visibility=if(pageProgress.visibility==View.VISIBLE)View.VISIBLE else View.GONE}}}
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
            override fun onShowCustomView(view:View,callback:CustomViewCallback){if(customView!=null){callback.onCustomViewHidden();return};customView=view;customViewCallback=callback;(view.parent as? ViewGroup)?.removeView(view);addContentView(view,ViewGroup.LayoutParams(-1,-1));androidx.core.view.WindowInsetsControllerCompat(window,root).hide(WindowInsetsCompat.Type.systemBars())}
            override fun onHideCustomView(){hideFullscreen()}
            override fun onPermissionRequest(request:PermissionRequest){request.deny()}
        }
        setDownloadListener{url,userAgent,disposition,mime,_->downloadFile(url,userAgent,disposition,mime)}
    }
    private fun addTab(url:String?,main:Boolean):BrowserTab?{
        if(!main && tabs.size>=(api.configuration.optJSONObject("tabs")?.optInt("max_tabs",8)?:8)){Toast.makeText(this,"Close a tab before opening another.",Toast.LENGTH_LONG).show();return null}
        if(connectionBlocked)return null
        val tab=BrowserTab(UUID.randomUUID().toString(),main,lastUrl=url);tabs.add(tab);showTab(tab)
        if(url!=null)tab.view.loadUrl(url)
        if(!main)api.event("tab_opened",host=url?.let{Uri.parse(it).host})
        saveSession();return tab
    }
    private fun showTab(tab:BrowserTab){
        if(requiredUpdate||maintenanceBlocking||connectionBlocked)return
        selected=tab;tab.lastActivity=System.currentTimeMillis();content.removeAllViews();(tab.view.parent as? ViewGroup)?.removeView(tab.view)
        content.addView(tab.view,FrameLayout.LayoutParams(-1,-1));tabButton.text="${tabs.size}"
        pageProgress.progress=tab.progress;pageProgress.visibility=if(tab.progress<100&&!tab.failed)View.VISIBLE else View.INVISIBLE;pageSpinner.visibility=if(pageProgress.visibility==View.VISIBLE)View.VISIBLE else View.GONE;statusText.text=Uri.parse(tab.web?.url?:tab.lastUrl?:"").host?:"Betna"
        if(tab.failed)showError(tab)
    }
    private fun home(){val main=tabs.firstOrNull{it.main}?:return;main.failed=false;showTab(main);main.view.loadUrl(api.configuration.optString("website_url",BuildConfig.WEBSITE_URL))}
    private fun closeTab(tab:BrowserTab,expired:Boolean){
        if(tab.main)return
        val wasSelected=selected===tab;tabs.remove(tab);tab.web?.let{(it.parent as? ViewGroup)?.removeView(it);it.stopLoading();it.destroy()};tab.web=null
        api.event(if(expired)"tab_expired" else "tab_closed")
        if(wasSelected)tabs.firstOrNull{it.main}?.let{showTab(it)}
        tabButton.text="${tabs.size}";saveSession()
    }
    private fun expireTabs(){val config=api.configuration.optJSONObject("tabs")?:return;val now=System.currentTimeMillis()
        tabs.toList().forEach{tab->if(TabPolicy.expired(tab.main,config.optBoolean("auto_close",true),tab.openedAt,tab.lastActivity,now,config.optInt("timeout_minutes",60),config.optString("basis","opened")))closeTab(tab,true)}
    }
    private fun showTabs(){if(requiredUpdate||maintenanceBlocking||connectionBlocked)return;expireTabs();val snapshot=tabs.toList();AlertDialog.Builder(this).setTitle("Open tabs").setItems(snapshot.map{(if(it.main)"Home · " else "")+it.title+"\n"+(Uri.parse(it.web?.url?:it.lastUrl?:"").host?:"")}.toTypedArray()){_,index->snapshot.getOrNull(index)?.let{if(it in tabs)showTab(it)}}.setNegativeButton("Close",null).show()}
    private fun showError(tab:BrowserTab){
        if(tab!==selected)return;content.removeAllViews();pageProgress.visibility=View.INVISIBLE;pageSpinner.visibility=View.GONE
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
    private fun navigate(url:String){val uri=runCatching{Uri.parse(url)}.getOrNull()?:return;if(uri.scheme !in listOf("http","https")){external(uri);return};if(connectionBlocked||uri.userInfo!=null||(!BuildConfig.DEBUG&&uri.scheme!="https"))return
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
                    val client=api.websiteClient.newBuilder().addNetworkInterceptor{chain->
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
    private fun options(){
        val items=arrayOf("Back","Forward","Saved passwords","Save current login","Find in page","Share page","Clear temporary files","Location permission","Notifications","Telegram support","Test connection","Download Betna","About Betna")
        val dialog=AlertDialog.Builder(this).setTitle("Betna browser").setItems(items){_,which->when(which){
            0->if(!connectionBlocked)selected?.view?.takeIf{it.canGoBack()}?.goBack()
            1->if(!connectionBlocked)selected?.view?.takeIf{it.canGoForward()}?.goForward()
            2->passwordAccounts(false)
            3->captureCurrentLogin()
            4->{val input=EditText(this).apply{hint="Search this page";setSingleLine()};val d=AlertDialog.Builder(this).setTitle("Find in page").setView(input).setPositiveButton("Find"){_,_->selected?.view?.findAllAsync(input.text.toString())}.setNeutralButton("Next"){_,_->selected?.view?.findNext(true)}.setNegativeButton("Close",null).show();BrowserUi.polish(d)}
            5->selected?.view?.url?.let{startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,it),"Share website"))}
            6->AlertDialog.Builder(this).setTitle("Clear temporary files?").setMessage("Login cookies, saved passwords and open tabs are preserved. The next visit may download more data.").setPositiveButton("Clear"){_,_->clearTemporaryFiles()}.setNegativeButton("Cancel",null).show()
            7->requestLocation(true)
            8->AlertDialog.Builder(this).setTitle("Notifications").setMultiChoiceItems(arrayOf("Promotional messages"),booleanArrayOf(api.promotions)){_,_,on->api.promotions=on;api.heartbeat(resumed)}.setPositiveButton("Done",null).setNeutralButton("Android permission"){_,_->if(Build.VERSION.SDK_INT>=33)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)}.show()
            9->support();10->testConnection()
            11->api.configuration.optString("download_page_url").takeIf{it.startsWith("https://")}?.let{external(Uri.parse(it))}
            12->AlertDialog.Builder(this).setTitle("Betna ${BuildConfig.VERSION_NAME}").setMessage("Installation: ${api.preferences.getString("installation_id","Not connected")}\nConnection: ${api.preferences.getString("vpn_status","off")}\nPasswords remain encrypted on this phone.\n\nDevice recognition: Betna uses a hashed, app-scoped Android identifier to group repeat installations in device reports. This does not identify your website account or restore browser data after uninstalling.").setPositiveButton("Close",null).show()
        }}.show();BrowserUi.polish(dialog)
    }
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
        column.addView(TextView(this).apply{text=message.optString("body");textSize=16f;setTextColor(BrowserUi.ink);setLineSpacing(dp(3).toFloat(),1f);setPadding(0,dp(12),0,dp(16))})
        val image=message.optString("image_url").takeIf{it.startsWith("https://")}
        if(image!=null){val imageView=ImageView(this).apply{adjustViewBounds=true;maxHeight=dp(200)};column.addView(imageView,0);io.execute{runCatching{api.client.newCall(okhttp3.Request.Builder().url(image).build()).execute().use{response->val source=response.body?.source()?:return@use;source.request(2_000_001);require(source.buffer.size<=2_000_000);val bytes=source.buffer.readByteArray();val opts=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true};android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,opts);require(opts.outWidth in 1..10000&&opts.outHeight in 1..10000);opts.inJustDecodeBounds=false;opts.inSampleSize=maxOf(1,maxOf(opts.outWidth,opts.outHeight)/1000);val bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,opts);runOnUiThread{imageView.setImageBitmap(bitmap)}}}}}
        val optOut=CheckBox(this).apply{text="Do not show again"};if(message.optBoolean("allow_opt_out",true))column.addView(optOut)
        var clicked=false
        val dialog=AlertDialog.Builder(this).setTitle(message.optString("title")).setView(column).setNegativeButton("Close",null)
        val action=message.optString("action_url");if(action.startsWith("http"))dialog.setPositiveButton(message.optString("button_text","Open")){_,_->clicked=true;api.event("campaign_clicked",id);navigate(action)}
        val built=dialog.create();built.setOnDismissListener{messageOpen=false;if(!clicked)api.event("campaign_dismissed",id);if(optOut.isChecked)api.optOut(id)}
        built.show();BrowserUi.polish(built)
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
        updateDialog=builder.create().also{dialog->dialog.show();BrowserUi.polish(dialog);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=false;status.text="Downloading and verifying update…"
            installer.download(release){message,_->status.text=message;dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=true}
        };dialog.setOnDismissListener{updateDialog=null}}
        api.event("update_prompted")
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);if(intent.action==Intent.ACTION_MAIN&&initialized&&System.currentTimeMillis()-lastBackgroundAt>30000)beginOpening("app_open");receivePush(intent);pendingPushUrl?.let{if(initialized&&!requiredUpdate)navigate(it)};pendingPushUrl=null}
    private fun receivePush(intent:Intent){intent.getStringExtra("push_delivery")?.let{api.event("notification_opened",it);intent.removeExtra("push_delivery")};pendingPushUrl=intent.getStringExtra("push_url")?.takeIf{it.startsWith("https://")||it.startsWith("http://")};intent.removeExtra("push_url")}
    private fun openingWindow()=api.configuration.optJSONObject("campaigns")?.optInt("opening_window_seconds",5)?.coerceIn(2,30)?:5
    private fun beginOpening(trigger:String){
        if(initialized){processCommands(true);applyVpn(true)}
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
        maintenanceDialog=builder.create().also{it.show();BrowserUi.polish(it);it.setOnDismissListener{if(maintenanceBlocking&&!isFinishing){handler.post{maintenanceRevision=-1;checkMaintenance()}}}}
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
            if(now-lastConfig>=60000){lastConfig=now;api.refresh{_,_->expireTabs();checkUpdate();checkMaintenance()}}
            if(now-lastMessageCheck>=(api.configuration.optInt("poll_seconds",60)*1000L))checkMessages("interval")
        };handler.postDelayed(this,api.configuration.optInt("heartbeat_seconds",30)*1000L)}}
    private val expiryTicker=object:Runnable{override fun run(){if(!resumed)return;expireTabs();handler.postDelayed(this,1000)}}
    override fun onResume(){super.onResume();resumed=true;if(::watchdog.isInitialized)watchdog.foreground(true);handler.removeCallbacks(expiryTicker);handler.post(expiryTicker);handler.removeCallbacks(ticker);handler.post(ticker);if(initialized){if((application as App).vpn.required&&!(application as App).vpn.usable())showVpnBlocked();locationReporter.start();expireTabs();checkUpdate();checkMaintenance();checkMessages("foreground")};if(::installer.isInitialized&&installer.pendingFile!=null&&packageManager.canRequestPackageInstalls())installer.installIfAllowed()}
    override fun onPause(){if(::locationReporter.isInitialized)locationReporter.stop();api.event("app_background",durationMs=System.currentTimeMillis()-loadedAt);if(::watchdog.isInitialized)watchdog.foreground(false);lastBackgroundAt=System.currentTimeMillis();saveSession();resumed=false;handler.removeCallbacks(expiryTicker);handler.removeCallbacks(ticker);api.heartbeat(false);CookieManager.getInstance().flush();super.onPause()}
    override fun onStop(){saveSession();super.onStop()}
    override fun onSaveInstanceState(outState:Bundle){saveSession();super.onSaveInstanceState(outState)}
    override fun onTrimMemory(level:Int){super.onTrimMemory(level);saveSession()}
    override fun onDestroy(){handler.removeCallbacksAndMessages(null);saveSession();tabs.forEach{it.web?.destroy()};sessionStore.close();watchdog.close();api.configurationListener=null;(application as App).vpn.listener=null;locationReporter.stop();runCatching{getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)};if(::engine.isInitialized)engine.close();io.shutdownNow();fileCallback?.onReceiveValue(null);super.onDestroy()}
    private fun hideFullscreen(){customView?.let{(it.parent as? ViewGroup)?.removeView(it)};customView=null;customViewCallback?.onCustomViewHidden();customViewCallback=null;androidx.core.view.WindowInsetsControllerCompat(window,root).show(WindowInsetsCompat.Type.systemBars())}
    private fun siteInfo(){val uri=Uri.parse(selected?.view?.url?:"");AlertDialog.Builder(this).setTitle(uri.host?:"Betna").setMessage("Website: ${uri.scheme}://${uri.host}\nVPN: ${api.preferences.getString("vpn_status","off")}\nConnection settings are managed by Betna.").setPositiveButton("Close",null).show()}
    private fun requestInitialPermissions(){
        api.enrollVpn((application as App).vpn.publicKey())
        if(!api.preferences.getBoolean("vpn_permission_asked",false)){
            val intent=android.net.VpnService.prepare(this)
            if(intent!=null)AlertDialog.Builder(this).setTitle("Betna secure connection").setMessage("Allow Betna to establish its secure connection when enabled by the administrator. It applies to Betna traffic.").setPositiveButton("Continue"){_,_->vpnPermission.launch(intent)}.setNegativeButton("Later"){_,_->api.preferences.edit().putBoolean("vpn_permission_asked",true).apply();requestLocation()}.setOnCancelListener{api.preferences.edit().putBoolean("vpn_permission_asked",true).apply()}.show()
            else{api.preferences.edit().putBoolean("vpn_permission_asked",true).apply();requestLocation()}
        }else requestLocation()
    }
    private fun requestLocation(manual:Boolean=false){
        if(api.configuration.optJSONObject("location")?.optBoolean("enabled",true)==false)return
        if(locationReporter.permission()!="denied"){locationReporter.start();return}
        if(!manual&&api.preferences.getBoolean("location_asked",false))return
        api.preferences.edit().putBoolean("location_asked",true).apply()
        val dialog=AlertDialog.Builder(this).setTitle("Share your approximate location?").setMessage("Betna uses location during app use for regional usage reports and connection support. You can decline and continue using the app.").setPositiveButton("Allow location"){_,_->locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION))}.setNegativeButton("Not now"){_,_->api.preferences.edit().putString("location_permission","denied").apply()}.show();BrowserUi.polish(dialog)
    }
    private fun applyVpn(opening:Boolean,callback:((Boolean)->Unit)?=null){
        val settings=api.configuration.optJSONObject("vpn")?:JSONObject().put("enabled",false)
        val mode=settings.toString()
        if(!opening&&settings.optString("apply","next_open")=="next_open"&&appliedVpn.isNotBlank()&&mode!=appliedVpn){callback?.invoke(!connectionBlocked);return}
        if(mode==appliedVpn&&(application as App).vpn.usable()){callback?.invoke(true);return}
        api.enrollVpn((application as App).vpn.publicKey())
        if(settings.optBoolean("enabled")&&android.net.VpnService.prepare(this)!=null){connectionBlocked=true;showVpnBlocked();callback?.invoke(false);return}
        if(settings.optBoolean("enabled")){connectionBlocked=true;if(initialized)showStartup("Establishing secure connection…")}
        (application as App).vpn.configure(settings){ok->
            appliedVpn=if(ok)mode else "";connectionBlocked=!ok
            if(ok&&initialized)selected?.let{showTab(it)}
            if(!ok)showVpnBlocked()
            callback?.invoke(ok)
        }
    }
    private fun showVpnBlocked(){
        connectionBlocked=true;content.removeAllViews()
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(28),dp(24),dp(28),dp(24))}
        box.addView(TextView(this).apply{text="Secure connection required";textSize=23f;setTextColor(BrowserUi.ink);gravity=Gravity.CENTER})
        box.addView(TextView(this).apply{text="Betna is waiting for its required VPN connection. Your open tabs are retained. Contact support if the connection remains unavailable.";textSize=15f;setTextColor(BrowserUi.muted);gravity=Gravity.CENTER;setPadding(0,dp(16),0,dp(24))})
        box.addView(BrowserUi.button(this,"Retry connection",true){connectionBlocked=false;if(!initialized)connect()else api.refresh{_,_->applyVpn(true)}})
        if(android.net.VpnService.prepare(this)!=null)box.addView(button("Allow secure connection"){android.net.VpnService.prepare(this)?.let{vpnPermission.launch(it)}})
        box.addView(button("Telegram support"){support()});content.addView(box,FrameLayout.LayoutParams(-1,-1))
    }
    private fun clearTemporaryFiles():Boolean=runCatching{
        (tabs.firstOrNull()?.web?:WebView(this).also{it.clearCache(true);it.destroy();return@runCatching}).clearCache(true)
    }.isSuccess.also{if(it){api.event("cache_cleared");Toast.makeText(this,"Temporary files cleared. Your login is preserved.",Toast.LENGTH_SHORT).show()}}
    private fun processCommands(opening:Boolean){
        val commands=api.configuration.optJSONArray("commands")?:return
        for(i in 0 until commands.length()){
            val c=commands.getJSONObject(i);val id=c.optString("id")
            if(id.isBlank()||c.optString("type")!="clear_cache"||c.optLong("expires_at",Long.MAX_VALUE)<=System.currentTimeMillis()/1000)continue
            val key="cache_command_$id"
            if(api.preferences.contains(key)){api.commandResult(id,api.preferences.getBoolean(key,false));continue}
            if(c.optString("timing")=="next_open"&&!opening)continue
            val ok=clearTemporaryFiles();api.preferences.edit().putBoolean(key,ok).apply();api.commandResult(id,ok)
        }
    }
    private fun origin(url:String?):String?=runCatching{val u=Uri.parse(url);if(u.scheme!="https"||u.host==null||u.userInfo!=null)null else "https://${u.host!!.lowercase()}"+(if(u.port>0&&u.port!=443)":${u.port}" else "")}.getOrNull()
    private fun trustedOrigin(value:String):Boolean {
        if(value==origin(api.configuration.optString("website_url",BuildConfig.WEBSITE_URL)))return true
        val backups=api.configuration.optJSONArray("backup_domains")?:return false
        return (0 until backups.length()).any{origin(backups.optString(it))==value}
    }
    private fun installPasswordBridge(web:WebView){
        if(!api.preferences.getBoolean("betna_vault",true)||!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))return
        val current=origin(web.url)?:return;if(!trustedOrigin(current))return
        runCatching{WebViewCompat.removeWebMessageListener(web,"betnaPasswords")}
        WebViewCompat.addWebMessageListener(web,"betnaPasswords",setOf(current)){view,message,source,isMainFrame,_->
            if(!isMainFrame||origin(source.toString())!=current||!trustedOrigin(current))return@addWebMessageListener
            val data=runCatching{JSONObject(message.data?:"")}.getOrNull()?:return@addWebMessageListener
            if(data.optString("kind")=="available"){
                if(api.preferences.getBoolean("vault_has_accounts",false)&&view===selected?.web&&content.findViewWithTag<View>("password-suggestion")==null){
                    val card=LinearLayout(this).apply{tag="password-suggestion";gravity=Gravity.CENTER_VERTICAL;background=BrowserUi.surface(this@MainActivity,Color.WHITE,14);elevation=dp(8).toFloat();setPadding(dp(6),dp(4),dp(6),dp(4))}
                    card.addView(BrowserUi.button(this,"Use a saved Betna login",true){content.removeView(card);passwordAccounts(true)},LinearLayout.LayoutParams(0,dp(44),1f))
                    card.addView(button("×","Dismiss login suggestion"){content.removeView(card)},LinearLayout.LayoutParams(dp(48),dp(44)))
                    content.addView(card,FrameLayout.LayoutParams(-1,-2,Gravity.TOP).apply{setMargins(dp(12),dp(12),dp(12),0)})
                };return@addWebMessageListener
            }
            if(data.optString("kind")=="attempt")api.event("login_attempt",host=source.host)
            if(origin(view.url)==current)offerSave(current,data.optString("username"),data.optString("password"))
        }
        web.evaluateJavascript("""(()=>{if(window.__betnaPasswordCapture)return;window.__betnaPasswordCapture=true;let last=0;const send=()=>{const p=document.querySelector('input[type=password]');if(!p||!p.value||p.autocomplete==='new-password'||Date.now()-last<3000)return;const form=p.closest('form');if(form&&new URL(form.action||location.href,location.href).origin!==location.origin)return;const box=form||document;const u=box.querySelector('input[autocomplete=username],input[type=email],input[type=tel],input[name*=user],input[name*=phone],input[type=text]');last=Date.now();window.betnaPasswords?.postMessage(JSON.stringify({kind:'attempt',username:u?.value||'',password:p.value}));};let suggested=false;const suggest=()=>{if(!suggested&&document.querySelector('input[type=password]')){suggested=true;window.betnaPasswords?.postMessage(JSON.stringify({kind:'available'}))}};new MutationObserver(suggest).observe(document.documentElement,{childList:true,subtree:true});suggest();document.addEventListener('submit',send,true);document.addEventListener('click',e=>{const b=e.target.closest('button,input[type=submit]');if(b&&b.type!=='button')send()},true)})()""",null)
    }
    private fun offerSave(from:String,user:String,password:String){
        if(vaultPrompt||password.isBlank()||password.length>1024||user.length>254||api.preferences.getBoolean("never_save_$from",false))return
        vaultPrompt=true
        val dialog=AlertDialog.Builder(this).setTitle("Save your Betna login?").setMessage("${user.ifBlank{"Account"}}\n$from\nStored encrypted on this phone.").setPositiveButton("Save"){_,_->unlockVault{vault.save(from,user,password);api.preferences.edit().putBoolean("vault_has_accounts",true).apply();Toast.makeText(this,"Login saved on this phone",Toast.LENGTH_SHORT).show()}}.setNegativeButton("Not now",null).setNeutralButton("Never for this site"){_,_->api.preferences.edit().putBoolean("never_save_$from",true).apply()}.create()
        dialog.setOnDismissListener{vaultPrompt=false};dialog.show();dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);BrowserUi.polish(dialog)
    }
    private fun unlockVault(action:()->Unit){
        val keyguard=getSystemService(android.app.KeyguardManager::class.java)
        if(!keyguard.isDeviceSecure){Toast.makeText(this,"Set a phone PIN or screen lock before saving passwords.",Toast.LENGTH_LONG).show();return}
        val intent=keyguard.createConfirmDeviceCredentialIntent("Unlock Betna passwords","Confirm your identity to access your saved logins.")?:return
        unlockAction=action;vaultUnlock.launch(intent)
    }
    private fun passwordAccounts(fill:Boolean){unlockVault{
        val rows=vault.read();val current=origin(selected?.view?.url)
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),dp(12))}
        box.addView(TextView(this).apply{text="Saved only on this phone. Uninstalling or clearing app data removes this vault.";setTextColor(BrowserUi.muted);textSize=13f})
        val dialog=AlertDialog.Builder(this).setTitle(if(fill)"Choose saved login" else "Your saved passwords").setView(ScrollView(this).apply{addView(box)}).setNegativeButton("Close",null).create()
        for(i in 0 until rows.length()){
            val a=rows.getJSONObject(i);box.addView(button(a.optString("username").ifBlank{"Account"}+" · "+Uri.parse(a.getString("origin")).host){
                dialog.dismiss();val target=origin(selected?.view?.url)
                val menu=AlertDialog.Builder(this).setTitle(a.optString("username","Account")).setItems(arrayOf("Fill this page","Reveal password","Edit login","Delete")){_,choice->when(choice){
                    0->{if(target==null||!trustedOrigin(target)){Toast.makeText(this,"Open the configured Betna website to use this login.",Toast.LENGTH_LONG).show()}else{
                        val doFill={unlockVault{val fresh=vault.read();val found=(0 until fresh.length()).map{fresh.getJSONObject(it)}.firstOrNull{it.getString("id")==a.getString("id")};if(found!=null&&origin(selected?.view?.url)==target)fillLogin(target,found)}}
                        if(target!=a.getString("origin"))AlertDialog.Builder(this).setTitle("Use login on the new website?").setMessage("From: ${a.getString("origin")}\nTo: $target\nThe website must accept the same account.").setPositiveButton("Use saved login"){_,_->doFill()}.setNegativeButton("Cancel",null).show()else doFill()
                    }}
                    1->unlockVault{val row=vault.read();val fresh=(0 until row.length()).map{row.getJSONObject(it)}.firstOrNull{it.getString("id")==a.getString("id")};val d=AlertDialog.Builder(this).setTitle("Saved password").setMessage(fresh?.optString("password")?:"Unavailable").setPositiveButton("Close",null).show();d.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);handler.postDelayed({d.dismiss()},20000)}
                    2->editLogin(a.getString("origin"),a.optString("username"),a.getString("id"))
                    3->unlockVault{vault.delete(a.getString("id"));Toast.makeText(this,"Saved login deleted",Toast.LENGTH_SHORT).show()}
                }}.show();BrowserUi.polish(menu)
            })
        }
        box.addView(button("Add a login"){dialog.dismiss();editLogin(current?:origin(api.configuration.optString("website_url",BuildConfig.WEBSITE_URL))?:"https://betna.bet","")})
        box.addView(button("Password saving method"){dialog.dismiss();AlertDialog.Builder(this).setTitle("Save passwords with").setSingleChoiceItems(arrayOf("Betna private vault","Android password manager"),if(api.preferences.getBoolean("betna_vault",true))0 else 1){d,n->api.preferences.edit().putBoolean("betna_vault",n==0).apply();tabs.forEach{it.web?.importantForAutofill=if(n==0)View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS else View.IMPORTANT_FOR_AUTOFILL_YES;it.web?.let{v->if(n==1&&WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))WebViewCompat.removeWebMessageListener(v,"betnaPasswords")else installPasswordBridge(v)}};d.dismiss()}.show()})
        dialog.show();dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);BrowserUi.polish(dialog)
    }}
    private fun editLogin(from:String,user:String,id:String?=null){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),0,dp(20),dp(12))}
        val username=EditText(this).apply{hint="Username or phone";setText(user);setSingleLine()}
        val password=EditText(this).apply{hint="Password";inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD}
        box.addView(username);box.addView(password)
        val d=AlertDialog.Builder(this).setTitle("Save login · ${Uri.parse(from).host}").setView(box).setPositiveButton("Save"){_,_->val u=username.text.toString();val pw=password.text.toString();if(pw.isNotBlank())unlockVault{vault.save(from,u,pw);api.preferences.edit().putBoolean("vault_has_accounts",true).apply();if(id!=null&&u!=user)vault.delete(id)}}.setNegativeButton("Cancel",null).show();d.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);BrowserUi.polish(d)
    }
    private fun captureCurrentLogin(){
        val web=selected?.view?:return;val from=origin(web.url)?:return
        if(!trustedOrigin(from)){Toast.makeText(this,"Password saving is available on the configured Betna website.",Toast.LENGTH_LONG).show();return}
        web.evaluateJavascript("""(()=>{const p=document.querySelector('input[type=password]'),u=document.querySelector('input[autocomplete=username],input[type=email],input[type=tel],input[name*=user],input[name*=phone],input[type=text]');return JSON.stringify({username:u?.value||'',password:p?.value||''})})()"""){raw->if(origin(web.url)!=from)return@evaluateJavascript;val value=runCatching{JSONObject(org.json.JSONTokener(raw).nextValue() as String)}.getOrNull();if(value?.optString("password").isNullOrBlank())editLogin(from,value?.optString("username")?:"")else offerSave(from,value!!.optString("username"),value.optString("password"))}
    }
    private fun fillLogin(target:String,account:JSONObject){
        val web=selected?.view?:return;if(origin(web.url)!=target)return
        val data=JSONObject().put("origin",target).put("username",account.getString("username")).put("password",account.getString("password"))
        web.evaluateJavascript("""(()=>{const a=$data;if(location.origin!==a.origin)return false;const p=document.querySelector('input[type=password]');if(!p)return false;const form=p.closest('form');if(form&&new URL(form.action||location.href,location.href).origin!==location.origin)return false;const u=(form||document).querySelector('input[autocomplete=username],input[type=email],input[type=tel],input[name*=user],input[name*=phone],input[type=text]');const set=(e,v)=>{Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(e,v);e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}))};if(u)set(u,a.username);set(p,a.password);return true})()"""){result->Toast.makeText(this,if(result=="true")"Login filled. Continue on the website." else "This login form needs manual entry.",Toast.LENGTH_LONG).show()}
    }
    private fun installViewportFix(web:WebView){
        val from=origin(web.url)?:return;if(!trustedOrigin(from))return
        // The WebView fills native insets. Site-specific padding patches must be narrowly configured, not guessed.
        web.evaluateJavascript("""(()=>{if(!document.querySelector('meta[name=viewport]')){const m=document.createElement('meta');m.name='viewport';m.content='width=device-width, initial-scale=1';document.head.append(m)}document.documentElement.style.setProperty('--betna-viewport-height',window.innerHeight+'px')})()""",null)
    }

}
