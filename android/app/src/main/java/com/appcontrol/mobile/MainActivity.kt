package com.appcontrol.mobile

import android.Manifest
import android.annotation.SuppressLint
import com.appcontrol.mobile.BetnaDialog as AlertDialog
import com.appcontrol.mobile.BetnaToast as Toast
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
    private lateinit var footer:FrameLayout
    private lateinit var backButton:Button
    private lateinit var tabButton:Button
    private var destroyed=false
    private fun alive()=!destroyed&&!isFinishing&&!isDestroyed
    private fun onUi(action:()->Unit){runOnUiThread{if(alive())action()}}
    private fun inBackground(action:()->Unit){if(alive()&&!io.isShutdown)runCatching{io.execute(action)}}
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
        override fun onAvailable(network:Network){handler.post{if(!alive())return@post;if(initialized)statusText.text="Connection available" else if(sessionLoaded)connect()}}
        override fun onLost(network:Network){handler.post{if(!alive())return@post;statusText.text="Connection changed · use Test connection if needed"}}
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
            if(history!=null&&runCatching{created.restoreState(history)}.getOrNull()==null){failed=true}
            return created
        }
    }
    // ComponentActivity does not use FragmentActivity or its legacy permission handling.
    @SuppressLint("InvalidFragmentVersionForActivityResult")
    private val notificationPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){if(alive()){api.heartbeat(resumed);permissionComplete()}}
    @SuppressLint("InvalidFragmentVersionForActivityResult")
    private val filePicker=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){result->fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode,result.data));fileCallback=null}
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun button(label:String,description:String=label,action:()->Unit)=BrowserUi.button(this,label,action=action).apply{contentDescription=description}
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);api=(application as App).api;installer=UpdateInstaller(this,api);sessionStore=BrowserSessionStore(this);watchdog=UiWatchdog(api);vault=PasswordVault(this);locationReporter=LocationReporter(this,api)
        api.configurationListener={if(alive()&&initialized){processCommands(false);applyVpn(false);expireTabs();checkUpdate();checkMaintenance();tabs.forEach{it.web?.let{view->installPasswordBridge(view)}};locationReporter.stop();if(resumed)locationReporter.start()}}
        (application as App).vpn.listener={ok->if(alive()&&!ok&&initialized)showVpnBlocked()}
        WindowCompat.setDecorFitsSystemWindows(window,false)
        BrowserUi.refresh(this)
        root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(BrowserUi.canvas(this@MainActivity))}
        content=FrameLayout(this).apply{setBackgroundColor(BrowserUi.fill(this@MainActivity))}
        root.addView(content,LinearLayout.LayoutParams(-1,0,1f))
        footer=FrameLayout(this)
        buildFooter()
        root.addView(footer,LinearLayout.LayoutParams(-1,-2))
        ViewCompat.setOnApplyWindowInsetsListener(root){view,insets->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom)
            footer.visibility=if(insets.isVisible(WindowInsetsCompat.Type.ime())||customView!=null)View.GONE else View.VISIBLE
            insets
        }
        setContentView(root)
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true){override fun handleOnBackPressed(){when{customView!=null->hideFullscreen();connectionBlocked->moveTaskToBack(true);requiredUpdate->moveTaskToBack(true);maintenanceBlocking->moveTaskToBack(true);selected?.web?.canGoBack()==true->selected?.web?.goBack();selected?.main==false->selected?.let{closeTab(it,false)};else->moveTaskToBack(true)}}})
        receivePush(intent);showStartup("Connecting…")
        sessionStore.read { snapshot->onUi{restoredSession=snapshot;sessionLoaded=true;connect()} }
        runCatching{getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)}
        CrashDiagnostics.reportPreviousExit(this,api)
    }
    private fun buildFooter(){
        if(!::footer.isInitialized)return
        footer.removeAllViews();footer.setBackgroundColor(BrowserUi.canvas(this))
        val column=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(6),dp(12),dp(6))}
        val address=LinearLayout(this).apply{gravity=Gravity.CENTER;minimumHeight=dp(26)}
        (statusText.parent as? ViewGroup)?.removeView(statusText)
        statusText.setTextColor(BrowserUi.muted);statusText.maxLines=1;statusText.ellipsize=android.text.TextUtils.TruncateAt.END;statusText.gravity=Gravity.CENTER
        statusText.contentDescription="Website and connection information";statusText.setOnClickListener{siteInfo()}
        address.addView(statusText,LinearLayout.LayoutParams(0,dp(28),1f))
        pageSpinner=ProgressBar(this,null,android.R.attr.progressBarStyleSmall).apply{visibility=View.GONE;contentDescription="Loading website";indeterminateTintList=android.content.res.ColorStateList.valueOf(BrowserUi.red)}
        address.addView(pageSpinner,LinearLayout.LayoutParams(dp(16),dp(16)))
        column.addView(address)
        val row=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(48)}
        fun tool(icon:Int,label:String,action:()->Unit)=button("",label,action).apply{
            minWidth=0;minimumWidth=0;setPadding(0,dp(12),0,dp(12))
            setCompoundDrawablesWithIntrinsicBounds(null,getDrawable(icon),null,null)
            background=BrowserUi.surface(this@MainActivity,android.graphics.Color.TRANSPARENT,12)
        }
        backButton=tool(R.drawable.ic_browser_back,"Back"){browserBack()}
        row.addView(backButton,LinearLayout.LayoutParams(0,dp(48),1f))
        row.addView(tool(R.drawable.ic_browser_home,"Homepage"){if(canBrowse())home()},LinearLayout.LayoutParams(0,dp(48),1f))
        row.addView(tool(R.drawable.ic_browser_reload,"Reload page"){if(canBrowse())selected?.let{retryTab(it)}},LinearLayout.LayoutParams(0,dp(48),1f))
        tabButton=tool(R.drawable.ic_browser_tabs,"Choose tabs"){showTabs()}
        row.addView(tabButton,LinearLayout.LayoutParams(0,dp(48),1f))
        row.addView(tool(R.drawable.ic_browser_menu,"More options"){options()},LinearLayout.LayoutParams(0,dp(48),1f))
        column.addView(row);footer.addView(column,FrameLayout.LayoutParams(-1,-2))
        pageProgress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{max=100;visibility=View.INVISIBLE;progressTintList=android.content.res.ColorStateList.valueOf(BrowserUi.red)}
        footer.addView(pageProgress,FrameLayout.LayoutParams(-1,dp(2),Gravity.TOP))
        androidx.core.view.WindowInsetsControllerCompat(window,root).apply{isAppearanceLightStatusBars=!BrowserUi.dark(this@MainActivity);isAppearanceLightNavigationBars=!BrowserUi.dark(this@MainActivity)}
        updateTools()
    }
    private fun canBrowse()=alive()&&initialized&&!requiredUpdate&&!maintenanceBlocking&&!connectionBlocked
    private fun updateTools(){
        if(!::tabButton.isInitialized)return
        tabButton.visibility=if(tabs.size>1)View.VISIBLE else View.GONE
        tabButton.contentDescription="Choose between ${tabs.size} tabs"
        backButton.isEnabled=canBrowse()&&(selected?.web?.canGoBack()==true||selected?.main==false)
        backButton.alpha=if(backButton.isEnabled)1f else .35f
        selected?.let{pageProgress.progress=it.progress;pageProgress.visibility=if(it.progress<100&&!it.failed)View.VISIBLE else View.INVISIBLE;pageSpinner.visibility=if(pageProgress.visibility==View.VISIBLE)View.VISIBLE else View.GONE}
    }
    private fun browserBack(){if(!canBrowse())return;selected?.let{if(it.web?.canGoBack()==true)it.web?.goBack()else if(!it.main)closeTab(it,false)}}
    private fun retryTab(tab:BrowserTab){
        if(!canBrowse())return
        api.event("retry");val live=tab.web;tab.failed=false;showTab(tab)
        if(tab.failed)return
        if(live!=null)live.reload() else if(tab.web?.url==null)tab.lastUrl?.let{tab.view.loadUrl(it)}
    }
    override fun onConfigurationChanged(newConfig:Configuration){
        super.onConfigurationChanged(newConfig)
        // Retain the live WebViews, including game state, while the window changes size.
        BrowserUi.refresh(this);buildFooter()
        tabs.forEach{it.web?.invalidate()}
        if(::root.isInitialized){root.setBackgroundColor(BrowserUi.canvas(this));content.setBackgroundColor(BrowserUi.fill(this));root.requestLayout();ViewCompat.requestApplyInsets(root)}
    }
    private lateinit var locationReporter:LocationReporter
    private lateinit var vault:PasswordVault
    private var unlockAction:(()->Unit)?=null
    private var connectionBlocked=false
    private var appliedVpn=""
    private var vaultPrompt=false
    private var permissionFlow=false
    private var initialPermissionsStarted=false
    private var permissionContinuation:(()->Unit)?=null
    private data class PendingLogin(val tabId:String,val from:String,val user:String,val password:String,val capturedAt:Long,val registration:Boolean)
    private var pendingLogin:PendingLogin?=null
    private val passwordScript by lazy{assets.open("password-capture.js").bufferedReader().use{it.readText()}}
    private val bridgeOrigins=java.util.WeakHashMap<WebView,Set<String>>()
    private val documentScripts=java.util.WeakHashMap<WebView,androidx.webkit.ScriptHandler>()

    private var customView:View?=null
    private var customViewCallback:WebChromeClient.CustomViewCallback?=null
    @SuppressLint("InvalidFragmentVersionForActivityResult")
    private val vpnPermission=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
        permissionFlow=false;api.preferences.edit().putBoolean("vpn_permission_asked",true).apply()
        if(!alive())return@registerForActivityResult
        if(result.resultCode==RESULT_OK){if(initialized)applyVpn(true)else connect()}else if(api.configuration.optJSONObject("vpn")?.optBoolean("enabled")==true){showVpnBlocked()}
        initialPermissionsStarted=false;requestInitialPermissions()
    }
    @SuppressLint("InvalidFragmentVersionForActivityResult")
    private val locationPermission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){if(alive()){if(resumed)locationReporter.start();api.heartbeat(resumed);permissionComplete()}}
    @SuppressLint("InvalidFragmentVersionForActivityResult")
    private val vaultUnlock=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
        val action=unlockAction;unlockAction=null
        if(result.resultCode==RESULT_OK)runCatching{action?.invoke()}.onFailure{Toast.makeText(this,"Vault could not be unlocked. Check your phone screen lock and try again.",Toast.LENGTH_LONG).show()}
    }
    private var connecting=false
    private fun connect(){if(!alive()||connecting||!sessionLoaded)return;connecting=true;api.refresh{ok,error->
        connecting=false
        if(!alive())return@refresh
        if(!ok){showStartup(error?:"Unable to connect",true);return@refresh}
        if(initialized){applyVpn(true);processCommands(true);expireTabs();checkUpdate();checkMaintenance();return@refresh}
        val dnsEnabled=api.configuration.optJSONObject("dns")?.optBoolean("enabled",true)?:true
        if(dnsEnabled&&!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)){showStartup("Update Android System WebView to use this app’s connection mode.",true);return@refresh}
        val ready:()->Unit=ready@{
            if(!alive())return@ready
            processCommands(true);initialized=true;loadedAt=System.currentTimeMillis();lastConfig=loadedAt
            restoreTabs()
            if(tabs.none{it.main})addTab(api.configuration.optString("website_url",BuildConfig.WEBSITE_URL),true)
            api.event("startup",durationMs=System.currentTimeMillis()-launchStartedAt)
            checkUpdate();checkMaintenance();requestInitialPermissions()
            val previous=api.preferences.getInt("last_launch_version",0)
            val trigger=if(previous==0)"first_open" else if(previous!=BuildConfig.VERSION_CODE)"updated" else "app_open"
            api.sessionTrigger=trigger
            api.preferences.edit().putInt("last_launch_version",BuildConfig.VERSION_CODE).apply()
            beginOpening(trigger)
            pendingPushUrl?.let{if(!requiredUpdate&&!maintenanceBlocking)navigate(it)};pendingPushUrl=null
        }
        if(WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)){
            if(::engine.isInitialized)engine.close()
            engine=ConnectionEngine((application as App).dns,{(application as App).vpn.usable()},{(application as App).vpn.websiteSocket()});engine.start()
            val proxy=ProxyConfig.Builder().addProxyRule("http://127.0.0.1:${engine.port}").build()
            ProxyController.getInstance().setProxyOverride(proxy,{handler.post(it)},{applyVpn(true){ok->if(ok)ready()else showVpnBlocked()}})
        }else applyVpn(true){ok->if(ok)ready()else showVpnBlocked()}
    }}
    private fun showStartup(text:String,retry:Boolean=false){
        if(!alive())return
        content.removeAllViews();val column=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(24),dp(30),dp(24),dp(30))}
        column.addView(TextView(this).apply{this.text=text;textSize=20f;gravity=Gravity.CENTER})
        if(!retry)column.addView(ProgressBar(this))
        if(retry){column.addView(button("Retry"){connect()});column.addView(button("Test connection"){testConnection()});column.addView(button("Support"){support()})}
        content.addView(column,FrameLayout.LayoutParams(-1,-1))
    }
    @SuppressLint("SetJavaScriptEnabled") private fun createWebView():WebView=WebView(this).apply{
        installPasswordBridge(this)
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
                val mainHost=Uri.parse(view.url?:api.configuration.optString("website_url",BuildConfig.WEBSITE_URL)).host
                if(tab.main && !uri.host.equals(mainHost,true)){addTab(uri.toString(),false);return true}
                return false
            }
            override fun onPageStarted(view:WebView,url:String?,icon:Bitmap?){tabs.find{it.web===view}?.let{it.failed=false;it.lastUrl=url;it.progress=0;it.loadingStarted=System.currentTimeMillis();if(it===selected){pageProgress.progress=0;pageProgress.visibility=View.VISIBLE;pageSpinner.visibility=View.VISIBLE;statusText.text=Uri.parse(url).host?:"Loading…"}}}
            override fun onPageFinished(view:WebView,url:String?){
                val tab=tabs.find{it.web===view}?:return;CookieManager.getInstance().flush()
                tab.lastUrl=url;tab.progress=100;if(tab===selected){pageProgress.visibility=View.INVISIBLE;pageSpinner.visibility=View.GONE}
                updateTools();
                if(!tab.failed){api.event("page_loaded",host=runCatching{Uri.parse(url).host}.getOrNull(),durationMs=System.currentTimeMillis()-tab.loadingStarted)}
                installPasswordBridge(view);installViewportFix(view);saveSession()
            }
            override fun onReceivedError(view:WebView,request:WebResourceRequest,error:WebResourceError){if(request.isForMainFrame){val tab=tabs.find{it.web===view}?:return;tab.failed=true;api.event("page_failed",host=request.url.host,code="WEBVIEW_${error.errorCode}");if(tab===selected)showError(tab)}}
            override fun onReceivedHttpError(view:WebView,request:WebResourceRequest,response:WebResourceResponse){if(request.isForMainFrame && response.statusCode>=500){val tab=tabs.find{it.web===view}?:return;tab.failed=true;api.event("page_failed",host=request.url.host,code="HTTP_${response.statusCode}");if(tab===selected)showError(tab)}}
            override fun onReceivedSslError(view:WebView,ssl:SslErrorHandler,error:SslError){ssl.cancel();tabs.find{it.web===view}?.let{tab->tab.failed=true;api.event("page_failed",host=Uri.parse(error.url).host,code="TLS_ERROR");if(tab===selected)showError(tab)}}
            override fun onRenderProcessGone(view:WebView,detail:RenderProcessGoneDetail):Boolean {
                val tab=tabs.find{it.web===view}
                // The renderer is gone: use the last live checkpoint, never query the dead WebView.
                tab?.let{it.failed=true;it.web=null;it.progress=100}
                documentScripts.remove(view);bridgeOrigins.remove(view)
                runCatching{(view.parent as? ViewGroup)?.removeView(view)}
                runCatching{view.destroy()}
                pendingLogin=null
                api.event("renderer_failed",code=if(detail.didCrash())"RENDERER_CRASH" else "RENDERER_MEMORY")
                if(alive()&&tab!=null&&tab===selected)showError(tab)
                return true
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
            override fun onShowCustomView(view:View,callback:CustomViewCallback){footer.visibility=View.GONE;if(customView!=null){callback.onCustomViewHidden();return};customView=view;customViewCallback=callback;(view.parent as? ViewGroup)?.removeView(view);addContentView(view,ViewGroup.LayoutParams(-1,-1));androidx.core.view.WindowInsetsControllerCompat(window,root).hide(WindowInsetsCompat.Type.systemBars())}
            override fun onHideCustomView(){hideFullscreen()}
            override fun onPermissionRequest(request:PermissionRequest){request.deny()}
        }
        setDownloadListener{url,userAgent,disposition,mime,_->downloadFile(url,userAgent,disposition,mime)}
    }
    private fun addTab(url:String?,main:Boolean):BrowserTab?{
        if(!main && tabs.size>=(api.configuration.optJSONObject("tabs")?.optInt("max_tabs",8)?:8)){Toast.makeText(this,"Close a tab before opening another.",Toast.LENGTH_LONG).show();return null}
        if(connectionBlocked)return null
        val tab=BrowserTab(UUID.randomUUID().toString(),main,lastUrl=url);tabs.add(tab);showTab(tab)
        if(url!=null&&!tab.failed)tab.web?.loadUrl(url)
        if(!main)api.event("tab_opened",host=url?.let{Uri.parse(it).host})
        saveSession();return tab
    }
    private fun showTab(tab:BrowserTab){
        if(!alive()||requiredUpdate||maintenanceBlocking||connectionBlocked)return
        selected=tab;tab.lastActivity=System.currentTimeMillis();content.removeAllViews()
        statusText.text=Uri.parse(tab.lastUrl?:"").host?:"Betna"
        updateTools()
        if(tab.failed){showError(tab);return}
        val web=runCatching{tab.view}.getOrElse{tab.failed=true;api.event("page_failed",code="WEBVIEW_UNAVAILABLE");showError(tab);return}
        if(tab.failed){showError(tab);return}
        (web.parent as? ViewGroup)?.removeView(web)
        content.addView(web,FrameLayout.LayoutParams(-1,-1))
        statusText.text=Uri.parse(web.url?:tab.lastUrl?:"").host?:"Betna"
        updateTools()
    }
    private fun home(){val main=tabs.firstOrNull{it.main}?:return;main.failed=false;showTab(main);main.view.loadUrl(api.configuration.optString("website_url",BuildConfig.WEBSITE_URL))}
    private fun closeTab(tab:BrowserTab,expired:Boolean){
        if(tab.main)return
        val wasSelected=selected===tab;tabs.remove(tab);tab.web?.let{(it.parent as? ViewGroup)?.removeView(it);it.stopLoading();it.destroy()};tab.web?.let{documentScripts.remove(it);bridgeOrigins.remove(it)};tab.web=null
        api.event(if(expired)"tab_expired" else "tab_closed")
        if(wasSelected)tabs.firstOrNull{it.main}?.let{showTab(it)}
        updateTools();saveSession()
    }
    private fun expireTabs(){val config=api.configuration.optJSONObject("tabs")?:return;val now=System.currentTimeMillis()
        tabs.toList().forEach{tab->if(TabPolicy.expired(tab.main,config.optBoolean("auto_close",true),tab.openedAt,tab.lastActivity,now,config.optInt("timeout_minutes",60),config.optString("basis","opened")))closeTab(tab,true)}
    }
    private fun showTabs(){
        if(!canBrowse()||tabs.size<2)return
        expireTabs();if(tabs.size<2)return
        val column=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val dialog=AlertDialog.Builder(this).setTitle("Your tabs").setSheet().setView(column).create()
        tabs.toList().forEach{tab->
            val row=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;background=BrowserUi.surface(this@MainActivity,if(tab===selected)BrowserUi.fill(this@MainActivity) else BrowserUi.canvas(this@MainActivity),18);setPadding(dp(6),dp(5),dp(4),dp(5))}
            val host=Uri.parse(tab.web?.url?:tab.lastUrl?:"").host?:"Website"
            row.addView(button((if(tab.main)"Home · " else "")+tab.title+"\n"+host){dialog.dismiss();if(tab in tabs)showTab(tab)},LinearLayout.LayoutParams(0,-2,1f))
            if(!tab.main)row.addView(button("×","Close ${tab.title}"){closeTab(tab,false);dialog.dismiss();if(tabs.size>1)showTabs()},LinearLayout.LayoutParams(dp(48),dp(48)))
            column.addView(row,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
        }
        dialog.show()
    }
    private fun showError(tab:BrowserTab){
        if(!alive()||tab!==selected||requiredUpdate||maintenanceBlocking||connectionBlocked)return;content.removeAllViews();pageProgress.visibility=View.INVISIBLE;pageSpinner.visibility=View.GONE
        val network=getSystemService(ConnectivityManager::class.java);val available=network.getNetworkCapabilities(network.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true
        val code=UUID.randomUUID().toString().take(8)
        val column=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(24),dp(24),dp(24),dp(24))}
        column.addView(TextView(this).apply{text=if(available)"Website could not be reached" else "No internet connection detected";textSize=22f;gravity=Gravity.CENTER})
        column.addView(TextView(this).apply{text="Your last action has not been automatically repeated.\nSupport reference: $code";gravity=Gravity.CENTER;setPadding(0,dp(14),0,dp(20))})
        api.event("page_failed",host=Uri.parse(tab.web?.url?:tab.lastUrl?:"").host,code="REF_$code")
        column.addView(BrowserUi.button(this,"Retry",true){retryTab(tab)})
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
            inBackground{
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
                onUi{result.onSuccess{file->AlertDialog.Builder(this).setTitle("Download complete").setMessage("Open or share this file to save a permanent copy.").setPositiveButton("Open"){_,_->
                    try{val fileUri=androidx.core.content.FileProvider.getUriForFile(this,"$packageName.files",file);startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(fileUri,mime?:"application/octet-stream").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}catch(_:Exception){Toast.makeText(this,"No app is available to open this file.",Toast.LENGTH_LONG).show()}
                }.setNeutralButton("Share / save"){_,_->val fileUri=androidx.core.content.FileProvider.getUriForFile(this,"$packageName.files",file);startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime?:"application/octet-stream").putExtra(Intent.EXTRA_STREAM,fileUri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Save or share"))}.setNegativeButton("Close",null).show()}.onFailure{Toast.makeText(this,"Download failed. Please retry from the website.",Toast.LENGTH_LONG).show()}}
            }
        }.setNegativeButton("Cancel",null).show()
    }
    private fun options(){
        val items=arrayOf("Back","Forward","Saved passwords","Save current login","Find in page","Share page","Clear temporary files","Location permission","Notifications","Telegram support","Test connection","Download Betna","About Betna")
        val dialog=AlertDialog.Builder(this).setTitle("Betna browser").setItems(items){_,which->when(which){
            0->if(!connectionBlocked)selected?.web?.takeIf{it.canGoBack()}?.goBack()
            1->if(!connectionBlocked)selected?.web?.takeIf{it.canGoForward()}?.goForward()
            2->passwordAccounts(false)
            3->captureCurrentLogin()
            4->{val input=EditText(this).apply{hint="Search this page";setSingleLine()};val d=AlertDialog.Builder(this).setTitle("Find in page").setView(input).setPositiveButton("Find"){_,_->selected?.web?.findAllAsync(input.text.toString())}.setNeutralButton("Next"){_,_->selected?.web?.findNext(true)}.setNegativeButton("Close",null).show();BrowserUi.polish(d)}
            5->selected?.web?.url?.let{startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,it),"Share website"))}
            6->AlertDialog.Builder(this).setTitle("Clear temporary files?").setMessage("Login cookies, saved passwords and open tabs are preserved. The next visit may download more data.").setPositiveButton("Clear"){_,_->clearTemporaryFiles()}.setNegativeButton("Cancel",null).show()
            7->requestLocation(true)
            8->AlertDialog.Builder(this).setTitle("Notifications").setMultiChoiceItems(arrayOf("Promotional messages"),booleanArrayOf(api.promotions)){_,_,on->api.promotions=on;api.heartbeat(resumed)}.setPositiveButton("Done",null).setNeutralButton("Android permission"){_,_->if(Build.VERSION.SDK_INT>=33)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)}.show()
            9->support();10->testConnection()
            11->api.configuration.optString("download_page_url").takeIf{it.startsWith("https://")}?.let{external(Uri.parse(it))}
            12->AlertDialog.Builder(this).setTitle("Betna ${BuildConfig.VERSION_NAME}").setMessage("Installation: ${api.preferences.getString("installation_id","Not connected")}\nConnection: ${api.preferences.getString("vpn_status","off")}\nPasswords remain encrypted on this phone.\n\nDevice recognition: Betna uses a hashed, app-scoped Android identifier to group repeat installations in device reports. This does not identify your website account or restore browser data after uninstalling.").setPositiveButton("Close",null).show()
        }}.show();BrowserUi.polish(dialog)
    }
    private fun checkMessages(trigger:String){
        if(!alive()||!resumed||permissionFlow||vaultPrompt||messageOpen||requiredUpdate||maintenanceBlocking||messageRequestPending)return
        messageRequestPending=true
        lastMessageCheck=System.currentTimeMillis();api.messages(trigger,(System.currentTimeMillis()-loadedAt)/1000){message->messageRequestPending=false;if(message!=null&&!messageOpen&&OpeningPolicy.canDisplay(message.optBoolean("opening_only"),resumed,loadedAt,System.currentTimeMillis(),openingWindow(),requiredUpdate||maintenanceBlocking||permissionFlow)){displayMessage(message);if(OpeningPolicy.isOpening(trigger))openingShown=true}else if(message!=null)api.event("campaign_failed",message.optString("delivery_id"),code="OPENING_WINDOW_EXPIRED")}
    }
    private fun displayMessage(message:JSONObject){
        if(!alive()||!resumed||permissionFlow||vaultPrompt||requiredUpdate||maintenanceBlocking)return
        val id=message.optString("delivery_id");if(id.isBlank())return
        val seen=getSharedPreferences("campaign-dedup",MODE_PRIVATE)
        if(seen.contains(id)||message.optLong("expires_at")<=System.currentTimeMillis()/1000)return
        if(message.optBoolean("opening_only")&&message.optLong("opening_deadline")<=System.currentTimeMillis()/1000){api.event("campaign_failed",id,code="OPENING_WINDOW_EXPIRED");return}
        val banner=message.optString("type")=="banner"
        val column=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(4),dp(8),dp(4),dp(8))}
        val label=BrowserUi.text(this,"BETNA · "+if(banner)"ANNOUNCEMENT" else "FOR YOU",11,true).apply{setTextColor(BrowserUi.red);setPadding(0,dp(4),0,dp(10))};column.addView(label)
        if(banner)column.addView(BrowserUi.text(this,message.optString("title"),19,true))
        column.addView(BrowserUi.text(this,message.optString("body"),15).apply{setTextColor(BrowserUi.muted);setLineSpacing(dp(3).toFloat(),1f);setPadding(0,dp(10),0,dp(14))})
        val optOut=CheckBox(this).apply{text="Do not show this again";textSize=12f;setTextColor(BrowserUi.muted);buttonTintList=android.content.res.ColorStateList.valueOf(BrowserUi.red);minHeight=dp(44)}
        if(message.optBoolean("allow_opt_out",true))column.addView(optOut)
        var clicked=false;var dismissed=false
        val finish={if(!dismissed){dismissed=true;messageOpen=false;if(!clicked)api.event("campaign_dismissed",id);if(optOut.isChecked)api.optOut(id)}}
        val action=message.optString("action_url")
        val open={clicked=true;api.event("campaign_clicked",id);navigate(action)}
        if(banner){
            val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;tag="campaign-banner";setPadding(dp(16),dp(12),dp(16),dp(12));background=BrowserUi.surface(this@MainActivity,BrowserUi.canvas(this@MainActivity),22);elevation=dp(10).toFloat()}
            val header=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
            header.addView(ImageView(this).apply{setImageResource(R.drawable.betna_logo);contentDescription="Betna"},LinearLayout.LayoutParams(dp(28),dp(28)))
            header.addView(BrowserUi.text(this,"Betna",13,true),LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(9)})
            header.addView(button("×","Dismiss announcement"){content.removeView(scrollBanner(card));finish()},LinearLayout.LayoutParams(dp(44),dp(44)))
            card.addView(header);card.addView(column)
            if(action.startsWith("https://")||(BuildConfig.DEBUG&&action.startsWith("http://")))card.addView(BrowserUi.button(this,message.optString("button_text","Open"),true){clicked=true;content.removeView(scrollBanner(card));finish();open()})
            val scroll=ScrollView(this).apply{addView(card);isVerticalScrollBarEnabled=false;elevation=dp(10).toFloat();tag="campaign-banner"}
            content.addView(scroll,FrameLayout.LayoutParams(-1,-2,Gravity.TOP).apply{setMargins(dp(12),dp(12),dp(12),dp(12));height=minOf(dp(310),(content.height*.5).toInt().coerceAtLeast(dp(180)))})
            // Removal on navigation counts as a dismissal and never expires the website tab.
            scroll.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{override fun onViewAttachedToWindow(v:View){};override fun onViewDetachedFromWindow(v:View){finish()}})
        }else{
            val builder=AlertDialog.Builder(this).setTitle(message.optString("title")).setView(column).setNegativeButton("Maybe later",null)
            if(action.startsWith("https://")||(BuildConfig.DEBUG&&action.startsWith("http://")))builder.setPositiveButton(message.optString("button_text","Open")){_,_->open()}
            val dialog=builder.create();dialog.setOnDismissListener{finish()};dialog.show()
            if(!dialog.isShowing)return
        }
        val image=message.optString("image_url").takeIf{it.startsWith("https://")}
        if(image!=null){
            val imageView=ImageView(this).apply{adjustViewBounds=true;maxHeight=dp(if(banner)100 else 200);scaleType=ImageView.ScaleType.CENTER_CROP;contentDescription="Announcement image";clipToOutline=true;background=BrowserUi.surface(this@MainActivity,BrowserUi.fill(this@MainActivity),16)};column.addView(imageView,1)
            inBackground{runCatching{api.client.newCall(okhttp3.Request.Builder().url(image).build()).execute().use{response->
                require(response.isSuccessful);val source=response.body?.source()?:return@use;source.request(2_000_001);require(source.buffer.size<=2_000_000)
                val bytes=source.buffer.readByteArray();val opts=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true};android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,opts);require(opts.outWidth in 1..10000&&opts.outHeight in 1..10000);opts.inJustDecodeBounds=false;opts.inSampleSize=maxOf(1,maxOf(opts.outWidth,opts.outHeight)/1000)
                val bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,opts);onUi{if(imageView.isAttachedToWindow)imageView.setImageBitmap(bitmap)}
            }}}
        }
        seen.edit().putLong(id,System.currentTimeMillis()).apply();messageOpen=true
        api.event(if(banner)"banner_displayed" else "popup_displayed",id)
    }
    private fun scrollBanner(card:View):View=(card.parent as? ScrollView)?:card
    private fun checkUpdate(){
        if(!alive())return
        val release=api.configuration.optJSONObject("release")?.takeIf{it.optInt("version_code")>BuildConfig.VERSION_CODE}
        val previously=requiredUpdate;requiredUpdate=release?.optBoolean("required",false)==true
        if(!requiredUpdate&&previously)selected?.let{showTab(it)}
        if(release==null){updateDialog?.dismiss();updateDialog=null;return}
        if(!resumed||permissionFlow||vaultPrompt)return
        if(requiredUpdate){maintenanceDialog?.setOnDismissListener(null);maintenanceDialog?.dismiss();maintenanceDialog=null;showUpdateBlocked()}
        if(updateDialog?.isShowing==true)return
        val key="remind_${release.optInt("version_code")}";val now=System.currentTimeMillis()
        if(!requiredUpdate&&now<api.preferences.getLong(key,0))return
        val column=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        column.addView(ImageView(this).apply{setImageResource(R.drawable.betna_logo);scaleType=ImageView.ScaleType.FIT_CENTER;contentDescription="Betna"},LinearLayout.LayoutParams(dp(60),dp(60)).apply{topMargin=dp(12);bottomMargin=dp(12)})
        column.addView(BrowserUi.text(this,"BETNA ${release.optString("version_name")}",12,true).apply{setTextColor(BrowserUi.red)})
        val status=BrowserUi.text(this,release.optString("notes","A new version of Betna is ready."),15).apply{setPadding(0,dp(12),0,dp(14));setTextColor(BrowserUi.muted)};column.addView(status)
        val builder=AlertDialog.Builder(this).setTitle(if(requiredUpdate)"A fresh start for Betna" else "A better Betna is ready").setView(column).setPositiveButton("Download update",null).setCancelable(!requiredUpdate)
        if(!requiredUpdate)builder.setNegativeButton("Later"){_,_->api.preferences.edit().putLong(key,now+release.optLong("remind_hours",24).coerceIn(1,168)*3600000).apply()}
        else builder.setNeutralButton("Telegram support"){_,_->support()}
        val dialog=builder.create();updateDialog=dialog;dialog.show()
        if(!dialog.isShowing){updateDialog=null;return}
        val download=dialog.getButton(AlertDialog.BUTTON_POSITIVE)?:return
        download.setOnClickListener{
            download.isEnabled=false;status.text="Downloading and checking your update…"
            installer.download(release){message,_->if(alive()&&dialog.isShowing){status.text=message;download.isEnabled=true}}
        }
        dialog.setOnDismissListener{if(updateDialog===dialog)updateDialog=null}
        api.event("update_prompted")
    }
    private fun showUpdateBlocked(){
        content.removeAllViews();updateTools()
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(28),dp(24),dp(28),dp(24))}
        box.addView(BrowserUi.text(this,"Update required",24,true).apply{gravity=Gravity.CENTER})
        box.addView(BrowserUi.text(this,"Install the latest Betna version to continue. Your open tabs and saved logins stay on this phone.",15).apply{gravity=Gravity.CENTER;setTextColor(BrowserUi.muted);setPadding(0,dp(16),0,dp(22))})
        box.addView(BrowserUi.button(this,"Update Betna",true){checkUpdate()});box.addView(button("Telegram support"){support()})
        content.addView(box,FrameLayout.LayoutParams(-1,-1))
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
        if(!alive()||!resumed||openingShown||System.currentTimeMillis()-loadedAt>=openingWindow()*1000L)return
        checkMessages(trigger)
        handler.postDelayed({pollOpening(trigger)},1000)
    }
    private fun saveSession(){
        if(destroyed||!initialized||!::sessionStore.isInitialized||savingSession)return
        if(api.configuration.optJSONObject("tabs")?.optBoolean("preserve_session",true)==false){sessionStore.clear();return}
        savingSession=true
        try {
            val snapshot=Bundle();snapshot.putInt("format",1);snapshot.putString("selected",selected?.id)
            val items=ArrayList<Bundle>()
            tabs.forEach{tab->val item=Bundle();item.putString("id",tab.id);item.putBoolean("main",tab.main);item.putLong("opened",tab.openedAt);item.putLong("activity",tab.lastActivity);item.putString("title",tab.title);item.putString("url",runCatching{tab.web?.url}.getOrNull()?:tab.lastUrl)
                val history=tab.web?.let{view->val live=Bundle();if(runCatching{view.saveState(live)}.getOrNull()!=null)live.also{tab.saved=it}else tab.saved}?:tab.saved
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
        if(!alive())return
        val notice=api.configuration.optJSONObject("maintenance");val enabled=notice?.optBoolean("enabled",false)==true
        if(!enabled){val previously=maintenanceBlocking;maintenanceBlocking=false;maintenanceDialog?.dismiss();maintenanceDialog=null;maintenanceRevision=-1;if(previously)selected?.let{showTab(it)};return}
        maintenanceBlocking=notice!!.optBoolean("blocking",false)
        if(requiredUpdate||!resumed||permissionFlow||vaultPrompt)return
        val revision=api.configuration.optLong("configuration_id",api.configuration.optLong("revision"))
        if(maintenanceBlocking){
            content.removeAllViews();updateTools()
            val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(28),dp(24),dp(28),dp(24))}
            box.addView(BrowserUi.text(this,notice.optString("title","Taking a short break"),24,true).apply{gravity=Gravity.CENTER})
            box.addView(BrowserUi.text(this,notice.optString("message"),15).apply{gravity=Gravity.CENTER;setTextColor(BrowserUi.muted);setPadding(0,dp(16),0,dp(22))})
            box.addView(BrowserUi.button(this,"Check again",true){api.refresh{_,_->checkMaintenance()}});box.addView(button("Telegram support"){support()})
            content.addView(box,FrameLayout.LayoutParams(-1,-1));return
        }
        if(maintenanceRevision==revision)return
        maintenanceDialog?.dismiss();maintenanceRevision=revision
        maintenanceDialog=AlertDialog.Builder(this).setTitle(notice.optString("title","Service notice")).setMessage(notice.optString("message")).setNeutralButton("Telegram support"){_,_->support()}.setPositiveButton("Continue",null).show()
    }
    private fun testConnection(){
        val status=TextView(this).apply{text="Testing the back office, DNS, website and phone capabilities…";setPadding(dp(20),dp(20),dp(20),dp(20))}
        val dialog=AlertDialog.Builder(this).setTitle("Test connection").setView(status).setNegativeButton("Close",null).create();dialog.show()
        inBackground{val result=ConnectionDiagnostics(this,api,(application as App).dns).run();runOnUiThread{
            if(!alive())return@runOnUiThread
            val checks=result.getJSONArray("checks");status.text="Network: ${result.getString("network")}\n\n"+(0 until checks.length()).joinToString("\n"){i->val c=checks.getJSONObject(i);"${c.getString("name")}: ${c.getString("status")}"+(if(c.has("http_status"))" (HTTP ${c.getInt("http_status")})" else "")}+"\n\nSupport reference: ${result.getString("id")}\nWebsite checks do not submit forms or repeat your last action."
            dialog.setButton(AlertDialog.BUTTON_POSITIVE,"Telegram support"){_,_->support()}
            api.connectionReport(result){saved->if(dialog.isShowing)status.append(if(saved)"\nReport saved for support." else "\nReport is on this phone; the back office could not receive it.")}
        }}
    }
    private val ticker=object:Runnable{override fun run(){if(!alive()||!resumed)return;expireTabs();api.heartbeat(true)
        val now=System.currentTimeMillis();if(initialized){
            if(now-lastConfig>=60000){lastConfig=now;api.refresh{_,_->expireTabs();checkUpdate();checkMaintenance()}}
            if(now-lastMessageCheck>=(api.configuration.optInt("poll_seconds",60)*1000L))checkMessages("interval")
        };handler.postDelayed(this,api.configuration.optInt("heartbeat_seconds",30)*1000L)}}
    private val expiryTicker=object:Runnable{override fun run(){if(!alive()||!resumed)return;expireTabs();handler.postDelayed(this,1000)}}
    override fun onResume(){
        super.onResume();resumed=true
        if(::watchdog.isInitialized)watchdog.foreground(true)
        handler.removeCallbacks(expiryTicker);handler.post(expiryTicker);handler.removeCallbacks(ticker);handler.post(ticker)
        if(initialized){
            if((application as App).vpn.required&&!(application as App).vpn.usable())showVpnBlocked()
            locationReporter.start();expireTabs();checkUpdate();checkMaintenance()
            if(!permissionFlow){val next=permissionContinuation;permissionContinuation=null;next?.invoke();requestInitialPermissions()}
            checkMessages("foreground")
        }
        if(::installer.isInitialized&&installer.pendingFile!=null)installer.installIfAllowed()
        updateTools()
    }
    override fun onPause(){
        if(::locationReporter.isInitialized)locationReporter.stop()
        if(::api.isInitialized){api.event("app_background",durationMs=System.currentTimeMillis()-loadedAt);api.heartbeat(false)}
        if(::watchdog.isInitialized)watchdog.foreground(false)
        lastBackgroundAt=System.currentTimeMillis();saveSession();resumed=false
        handler.removeCallbacks(expiryTicker);handler.removeCallbacks(ticker)
        runCatching{CookieManager.getInstance().flush()};super.onPause()
    }
    override fun onStop(){saveSession();super.onStop()}
    override fun onSaveInstanceState(outState:Bundle){saveSession();super.onSaveInstanceState(outState)}
    override fun onTrimMemory(level:Int){super.onTrimMemory(level);saveSession()}
    override fun onDestroy(){
        saveSession();destroyed=true;resumed=false;pendingLogin=null;unlockAction=null;permissionContinuation=null
        handler.removeCallbacksAndMessages(null)
        updateDialog?.setOnDismissListener(null);updateDialog?.dismiss();maintenanceDialog?.setOnDismissListener(null);maintenanceDialog?.dismiss()
        tabs.forEach{tab->val web=tab.web;tab.web=null;if(web!=null)runCatching{(web.parent as? ViewGroup)?.removeView(web);web.destroy()}}
        if(::sessionStore.isInitialized)sessionStore.close()
        if(::watchdog.isInitialized)watchdog.close()
        if(::api.isInitialized)api.configurationListener=null
        (application as App).vpn.listener=null
        if(::locationReporter.isInitialized)locationReporter.stop()
        runCatching{getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)}
        if(::engine.isInitialized)runCatching{engine.close()}
        if(::installer.isInitialized)installer.close()
        AlertDialog.dismissAll(this)
        io.shutdownNow();fileCallback?.onReceiveValue(null);fileCallback=null;super.onDestroy()
    }
    private fun hideFullscreen(){footer.visibility=View.VISIBLE;customView?.let{(it.parent as? ViewGroup)?.removeView(it)};customView=null;customViewCallback?.onCustomViewHidden();customViewCallback=null;androidx.core.view.WindowInsetsControllerCompat(window,root).show(WindowInsetsCompat.Type.systemBars())}
    private fun siteInfo(){val uri=Uri.parse(selected?.web?.url?:selected?.lastUrl?:"");AlertDialog.Builder(this).setTitle(uri.host?:"Betna").setMessage("Website: ${uri.scheme}://${uri.host}\nVPN: ${api.preferences.getString("vpn_status","off")}\nConnection settings are managed by Betna.").setPositiveButton("Close",null).show()}
    private fun notificationsGranted()=androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()
    private fun locationGranted()=locationReporter.permission()!="denied"
    private fun requestInitialPermissions(){
        if(!alive()||!resumed||requiredUpdate||maintenanceBlocking||permissionFlow||initialPermissionsStarted)return
        val prefs=api.preferences
        if(!prefs.contains("permission_installed_at"))prefs.edit().putLong("permission_installed_at",System.currentTimeMillis()).apply()
        initialPermissionsStarted=true
        api.enrollVpn((application as App).vpn.publicKey())
        if(!prefs.getBoolean("vpn_permission_asked",false)){
            val intent=android.net.VpnService.prepare(this)
            if(intent!=null){
                permissionFlow=true
                AlertDialog.Builder(this).setTitle("Betna secure connection").setSheet().setMessage("Allow a secure connection for Betna when enabled by its administrator. Android will ask for your consent.")
                    .setPositiveButton("Continue"){_,_->prefs.edit().putBoolean("vpn_permission_asked",true).apply();vpnPermission.launch(intent)}
                    .setNegativeButton("Not now"){_,_->prefs.edit().putBoolean("vpn_permission_asked",true).apply();permissionFlow=false;initialPermissionsStarted=false;requestInitialPermissions()}
                    .setOnCancelListener{prefs.edit().putBoolean("vpn_permission_asked",true).apply();permissionFlow=false;initialPermissionsStarted=false;requestInitialPermissions()}.show()
                return
            }
            prefs.edit().putBoolean("vpn_permission_asked",true).apply()
        }
        permissionFlow=false
        if(!prefs.getBoolean("initial_location_done",prefs.getBoolean("location_asked",false))&&!locationGranted()&&api.configuration.optJSONObject("location")?.optBoolean("enabled",true)!=false){
            prefs.edit().putBoolean("initial_location_done",true).apply()
            explainPermission(PermissionReminderPolicy.Kind.Location){initialPermissionsStarted=false;requestInitialPermissions()};return
        }
        prefs.edit().putBoolean("initial_location_done",true).apply()
        if(!prefs.getBoolean("initial_notifications_done",false)&&!notificationsGranted()){
            prefs.edit().putBoolean("initial_notifications_done",true).apply()
            explainPermission(PermissionReminderPolicy.Kind.Notifications){initialPermissionsStarted=false;requestInitialPermissions()};return
        }
        prefs.edit().putBoolean("initial_notifications_done",true).apply()
        permissionFlow=false
    }
    private fun permissionComplete(){permissionFlow=false;val next=permissionContinuation;permissionContinuation=null;if(resumed)next?.invoke()else permissionContinuation=next}
    private fun requestLocation(manual:Boolean=false){
        if(!alive()||!resumed||permissionFlow)return
        if(api.configuration.optJSONObject("location")?.optBoolean("enabled",true)==false){Toast.makeText(this,"Location reporting is disabled by Betna.",Toast.LENGTH_SHORT).show();return}
        if(locationGranted()){locationReporter.start();Toast.makeText(this,"Location permission is enabled.",Toast.LENGTH_SHORT).show();return}
        explainPermission(PermissionReminderPolicy.Kind.Location){}
    }
    private fun explainPermission(kind:PermissionReminderPolicy.Kind,next:()->Unit){
        if(!alive()||!resumed||permissionFlow)return
        permissionFlow=true;permissionContinuation=next
        val location=kind==PermissionReminderPolicy.Kind.Location
        val permission=if(location)Manifest.permission.ACCESS_COARSE_LOCATION else Manifest.permission.POST_NOTIFICATIONS
        val prefs=api.preferences
        val asked=prefs.getBoolean(if(location)"location_runtime_asked" else "notification_runtime_asked",false)
        val settings=(asked&&!shouldShowRequestPermissionRationale(permission))||(!location&&Build.VERSION.SDK_INT<33)
        val now=System.currentTimeMillis()
        prefs.edit().putLong(if(location)"permission_last_location" else "permission_last_notifications",now).putBoolean(if(location)"location_asked" else "notifications_asked",true).apply()
        val title=if(location)"Share approximate location" else "Stay connected with Betna"
        val purpose=if(location)"Share location while using Betna for regional usage reports and connection support. Approximate location is enough." else "Allow notifications for updates and Betna announcements. Promotional messages can be disabled separately in More."
        AlertDialog.Builder(this).setTitle(title).setSheet().setMessage(purpose+"\n\nYou can choose Not now and continue using Betna."+(if(settings)" Enable this permission in Android settings." else ""))
            .setPositiveButton(if(settings)"Open Settings" else if(location)"Allow location" else "Allow notifications"){_,_->
                if(settings){
                    permissionFlow=false;permissionContinuation=next
                    runCatching{startActivity(Intent(if(location)android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS else android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply{if(location)data=Uri.parse("package:$packageName")else putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,packageName)})}
                    handler.postDelayed({if(alive()&&resumed){permissionContinuation=null;next()}},400)
                }else{
                    prefs.edit().putBoolean(if(location)"location_runtime_asked" else "notification_runtime_asked",true).apply()
                    if(location)locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION))else if(Build.VERSION.SDK_INT>=33)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)else permissionComplete()
                }
            }.setNegativeButton("Not now"){_,_->permissionComplete()}.setOnCancelListener{permissionComplete()}.show()
    }
    private fun remindPermissionsAfterLogin(){
        if(!alive()||!resumed||permissionFlow||requiredUpdate||maintenanceBlocking)return
        val prefs=api.preferences;val now=System.currentTimeMillis();val day=java.time.LocalDate.now().toEpochDay()
        val next=if(prefs.getString("permission_next","location")=="notifications")PermissionReminderPolicy.Kind.Notifications else PermissionReminderPolicy.Kind.Location
        val kind=PermissionReminderPolicy.next(now,day,prefs.getLong("permission_installed_at",now),prefs.getLong("permission_last_day",-1),next,locationGranted()||api.configuration.optJSONObject("location")?.optBoolean("enabled",true)==false,notificationsGranted(),prefs.getLong("permission_last_location",0),prefs.getLong("permission_last_notifications",0))?:return
        prefs.edit().putLong("permission_last_day",day).putString("permission_next",if(kind==PermissionReminderPolicy.Kind.Location)"notifications" else "location").apply()
        explainPermission(kind){}
    }
    private fun applyVpn(opening:Boolean,callback:((Boolean)->Unit)?=null){
        if(!alive())return
        val settings=api.configuration.optJSONObject("vpn")?:JSONObject().put("enabled",false)
        val mode=settings.toString()
        if(!opening&&settings.optString("apply","next_open")=="next_open"&&appliedVpn.isNotBlank()&&mode!=appliedVpn){callback?.invoke(!connectionBlocked);return}
        if(mode==appliedVpn&&(application as App).vpn.usable()){callback?.invoke(true);return}
        api.enrollVpn((application as App).vpn.publicKey())
        if(settings.optBoolean("enabled")&&android.net.VpnService.prepare(this)!=null){connectionBlocked=true;showVpnBlocked();callback?.invoke(false);return}
        if(settings.optBoolean("enabled")){connectionBlocked=true;if(initialized)showStartup("Establishing secure connection…")}
        (application as App).vpn.configure(settings){ok->
            if(!alive())return@configure
            appliedVpn=if(ok)mode else "";connectionBlocked=!ok
            if(ok&&initialized)selected?.let{showTab(it)}
            if(!ok)showVpnBlocked()
            callback?.invoke(ok)
        }
    }
    private fun showVpnBlocked(){
        if(!alive())return
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
    private fun trustedOrigins():Set<String>{
        val origins=mutableSetOf<String>();origin(api.configuration.optString("website_url",BuildConfig.WEBSITE_URL))?.let{origins.add(it)}
        api.configuration.optJSONArray("backup_domains")?.let{backups->for(i in 0 until backups.length())origin(backups.optString(i))?.let{origins.add(it)}}
        return origins
    }
    private fun installPasswordBridge(web:WebView){
        if(!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))return
        if(!api.preferences.getBoolean("betna_vault",true)){
            runCatching{WebViewCompat.removeWebMessageListener(web,"betnaPasswords")};documentScripts.remove(web)?.remove();bridgeOrigins.remove(web);return
        }
        val origins=trustedOrigins();if(origins.isEmpty())return
        if(bridgeOrigins[web]!=origins){
            runCatching{WebViewCompat.removeWebMessageListener(web,"betnaPasswords")};documentScripts.remove(web)?.remove()
            WebViewCompat.addWebMessageListener(web,"betnaPasswords",origins){view,message,source,_,_->
                val current=origin(view.url);val sourceOrigin=origin(source.toString())
                // Same-origin frames are supported. Unrelated sites and cross-origin frames never receive vault access.
                if(!alive()||current==null||current!=sourceOrigin||!trustedOrigin(current))return@addWebMessageListener
                val data=runCatching{JSONObject(message.data?:"")}.getOrNull()?:return@addWebMessageListener
                val tab=tabs.find{it.web===view}?:return@addWebMessageListener
                when(data.optString("kind")){
                    "attempt"->{
                        val user=data.optString("username");val password=data.optString("password")
                        if(user.length<=254&&password.length in 1..1024){pendingLogin=PendingLogin(tab.id,current,user,password,System.currentTimeMillis(),data.optBoolean("registration"));api.event("login_attempt",host=source.host)}
                    }
                    "state","ready"->{
                        if(data.optBoolean("hasPassword")){if(!data.optBoolean("registration"))suggestPasswords(view)}else completeLogin(tab,current)
                    }
                    "form_resolved"->completeLogin(tab,current)
                }
            }
            bridgeOrigins[web]=origins
            if(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))documentScripts[web]=WebViewCompat.addDocumentStartJavaScript(web,passwordScript,origins)
        }
        if(origin(web.url)?.let{it in origins}==true)web.evaluateJavascript(passwordScript,null)
    }
    private fun completeLogin(tab:BrowserTab,current:String){
        val pending=pendingLogin?:return
        val age=System.currentTimeMillis()-pending.capturedAt
        if(pending.tabId!=tab.id||pending.from!=current||age<1200||age>10*60*1000)return
        pendingLogin=null
        api.event("login_detected",host=Uri.parse(current).host,code="FORM_RESOLVED")
        if(tab===selected&&resumed){
            offerSave(pending.from,pending.user,pending.password)
            if(!vaultPrompt)remindPermissionsAfterLogin()
        }
    }
    private fun suggestPasswords(view:WebView){
        if(!resumed||view!==selected?.web||!api.preferences.getBoolean("vault_has_accounts",false)||content.findViewWithTag<View>("password-suggestion")!=null)return
        val card=LinearLayout(this).apply{tag="password-suggestion";gravity=Gravity.CENTER_VERTICAL;background=BrowserUi.surface(this@MainActivity,BrowserUi.canvas(this@MainActivity),18);elevation=dp(8).toFloat();setPadding(dp(6),dp(4),dp(6),dp(4))}
        card.addView(BrowserUi.button(this,"Use saved login",true){content.removeView(card);passwordAccounts(true)},LinearLayout.LayoutParams(0,-2,1f))
        card.addView(button("×","Dismiss login suggestion"){content.removeView(card)},LinearLayout.LayoutParams(dp(48),dp(48)))
        content.addView(card,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply{setMargins(dp(12),0,dp(12),dp(12))})
    }
    private fun offerSave(from:String,user:String,password:String){
        if(!alive()||!resumed||permissionFlow||requiredUpdate||vaultPrompt||password.isBlank()||password.length>1024||user.length>254||api.preferences.getBoolean("never_save_$from",false))return
        vaultPrompt=true
        val dialog=AlertDialog.Builder(this).setTitle("Save your Betna login?").setMessage("${user.ifBlank{"Account"}}\n$from\nStored encrypted on this phone.").setPositiveButton("Save"){_,_->unlockVault{vault.save(from,user,password);api.preferences.edit().putBoolean("vault_has_accounts",true).apply();Toast.makeText(this,"Login saved on this phone",Toast.LENGTH_SHORT).show()}}.setNegativeButton("Not now",null).setNeutralButton("Never for this site"){_,_->api.preferences.edit().putBoolean("never_save_$from",true).apply()}.create()
        dialog.setOnDismissListener{vaultPrompt=false;handler.postDelayed({if(alive()&&resumed)remindPermissionsAfterLogin()},400)};dialog.show();dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);BrowserUi.polish(dialog)
    }
    private fun unlockVault(action:()->Unit){
        if(!alive()||!resumed)return
        val keyguard=getSystemService(android.app.KeyguardManager::class.java)
        if(!keyguard.isDeviceSecure){Toast.makeText(this,"Set a phone PIN or screen lock before saving passwords.",Toast.LENGTH_LONG).show();return}
        val intent=keyguard.createConfirmDeviceCredentialIntent("Unlock Betna passwords","Confirm your identity to access your saved logins.")?:return
        unlockAction=action;vaultUnlock.launch(intent)
    }
    private fun passwordAccounts(fill:Boolean){unlockVault{
        val rows=vault.read();val current=origin(selected?.web?.url)
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),dp(12))}
        box.addView(TextView(this).apply{text="Saved only on this phone. Uninstalling or clearing app data removes this vault.";setTextColor(BrowserUi.muted);textSize=13f})
        val dialog=AlertDialog.Builder(this).setTitle(if(fill)"Choose saved login" else "Your saved passwords").setSheet().setView(ScrollView(this).apply{addView(box)}).setNegativeButton("Close",null).create()
        for(i in 0 until rows.length()){
            val a=rows.getJSONObject(i);box.addView(button(a.optString("username").ifBlank{"Account"}+" · "+Uri.parse(a.getString("origin")).host){
                dialog.dismiss();val target=origin(selected?.web?.url)
                val menu=AlertDialog.Builder(this).setTitle(a.optString("username","Account")).setItems(arrayOf("Fill this page","Reveal password","Edit login","Delete")){_,choice->when(choice){
                    0->{if(target==null||!trustedOrigin(target)){Toast.makeText(this,"Open the configured Betna website to use this login.",Toast.LENGTH_LONG).show()}else{
                        val doFill={unlockVault{val fresh=vault.read();val found=(0 until fresh.length()).map{fresh.getJSONObject(it)}.firstOrNull{it.getString("id")==a.getString("id")};if(found!=null&&origin(selected?.web?.url)==target)fillLogin(target,found)}}
                        if(target!=a.getString("origin"))AlertDialog.Builder(this).setTitle("Use login on the new website?").setMessage("From: ${a.getString("origin")}\nTo: $target\nThe website must accept the same account.").setPositiveButton("Use saved login"){_,_->doFill()}.setNegativeButton("Cancel",null).show()else doFill()
                    }}
                    1->unlockVault{val row=vault.read();val fresh=(0 until row.length()).map{row.getJSONObject(it)}.firstOrNull{it.getString("id")==a.getString("id")};val d=AlertDialog.Builder(this).setTitle("Saved password").setMessage(fresh?.optString("password")?:"Unavailable").setPositiveButton("Close",null).show();d.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);handler.postDelayed({if(alive())d.dismiss()},20000)}
                    2->editLogin(a.getString("origin"),a.optString("username"),a.getString("id"))
                    3->unlockVault{vault.delete(a.getString("id"));Toast.makeText(this,"Saved login deleted",Toast.LENGTH_SHORT).show()}
                }}.show();BrowserUi.polish(menu)
            })
        }
        box.addView(button("Add a login"){dialog.dismiss();editLogin(current?:origin(api.configuration.optString("website_url",BuildConfig.WEBSITE_URL))?:"https://betna.bet","")})
        box.addView(button("Password saving method"){dialog.dismiss();AlertDialog.Builder(this).setTitle("Save passwords with").setSingleChoiceItems(arrayOf("Betna private vault","Android password manager"),if(api.preferences.getBoolean("betna_vault",true))0 else 1){d,n->api.preferences.edit().putBoolean("betna_vault",n==0).apply();tabs.forEach{it.web?.importantForAutofill=if(n==0)View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS else View.IMPORTANT_FOR_AUTOFILL_YES;it.web?.let{v->installPasswordBridge(v)}};d.dismiss()}.show()})
        dialog.show();dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);BrowserUi.polish(dialog)
    }}
    private fun editLogin(from:String,user:String,id:String?=null){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),0,dp(20),dp(12))}
        val username=EditText(this).apply{hint="Username or phone";setText(user);setSingleLine()}
        val password=EditText(this).apply{hint="Password";inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD}
        box.addView(username);box.addView(password)
        val d=AlertDialog.Builder(this).setTitle("Save login · ${Uri.parse(from).host}").setSheet().setView(box).setPositiveButton("Save"){_,_->val u=username.text.toString();val pw=password.text.toString();if(pw.isNotBlank())unlockVault{vault.save(from,u,pw);api.preferences.edit().putBoolean("vault_has_accounts",true).apply();if(id!=null&&u!=user)vault.delete(id)}}.setNegativeButton("Cancel",null).show();d.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);BrowserUi.polish(d)
    }
    private fun captureCurrentLogin(){
        val web=selected?.web?:return;val from=origin(web.url)?:return
        if(!trustedOrigin(from)){Toast.makeText(this,"Open the configured Betna website to save a login.",Toast.LENGTH_LONG).show();return}
        installPasswordBridge(web)
        web.evaluateJavascript("JSON.stringify(window.__betnaCredentials?.read()||null)"){raw->
            if(!alive()||origin(web.url)!=from)return@evaluateJavascript
            val value=runCatching{JSONObject(org.json.JSONTokener(raw).nextValue() as String)}.getOrNull()
            val pending=pendingLogin?.takeIf{it.from==from&&it.tabId==selected?.id&&System.currentTimeMillis()-it.capturedAt<10*60*1000}
            when{
                !value?.optString("password").isNullOrBlank()->offerSave(from,value!!.optString("username"),value.optString("password"))
                pending!=null->offerSave(pending.from,pending.user,pending.password)
                else->editLogin(from,value?.optString("username")?:"")
            }
        }
    }
    private fun fillLogin(target:String,account:JSONObject){
        val web=selected?.web?:return;if(origin(web.url)!=target||!trustedOrigin(target))return
        val data=JSONObject().put("origin",target).put("username",account.getString("username")).put("password",account.getString("password"))
        web.evaluateJavascript("""(()=>{const account=$data;const fill=(w)=>{try{if(w.location.origin!==account.origin)return false;if(w.__betnaCredentials?.fill(account))return true;for(let i=0;i<w.frames.length;i++)if(fill(w.frames[i]))return true}catch(_){}return false};return fill(window)})()"""){result->if(alive())Toast.makeText(this,if(result=="true")"Login filled. Continue on the website." else "This form needs manual entry. Your login is still in Saved passwords.",Toast.LENGTH_LONG).show()}
    }
    private fun installViewportFix(web:WebView){
        val from=origin(web.url)?:return;if(!trustedOrigin(from))return
        // The WebView fills native insets. Site-specific padding patches must be narrowly configured, not guessed.
        web.evaluateJavascript("""(()=>{if(!document.querySelector('meta[name=viewport]')){const m=document.createElement('meta');m.name='viewport';m.content='width=device-width, initial-scale=1';document.head.append(m)}document.documentElement.style.setProperty('--betna-viewport-height',window.innerHeight+'px')})()""",null)
    }

}
