package com.appcontrol.mobile

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebResourceError
import android.webkit.RenderProcessGoneDetail
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class BrowserStabilityTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private var activeScenario:ActivityScenario<MainActivity>?=null
    private var historyWriter:java.util.concurrent.ExecutorService?=null
    private val site="""<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"><style>
        *{box-sizing:border-box}html,body{margin:0;height:100%;font-family:sans-serif;background:#f6f8f2;color:#283523}body{display:flex;flex-direction:column}.brand{background:#ffd52a;padding:18px 22px;font-size:25px;font-weight:bold;color:#7d321d}main{padding:24px 22px;flex:1}h1{font-size:24px}p{font-size:15px;color:#687663;line-height:1.5}input{display:block;padding:14px;border:1px solid #dae0d5;border-radius:12px;margin:12px 0;width:100%;font-size:16px;background:white}button{padding:15px;background:#ffd52a;color:#613c1c;border:0;border-radius:12px;width:100%;font-size:16px}nav{background:#ffd52a;display:flex;justify-content:space-around;padding:17px 6px;font-size:13px;color:#7d321d}a{display:block;margin-top:20px;color:#6d4824}
        </style></head><body><div class="brand">BETNA</div><main><h1 id="heading">Welcome back</h1><p>Website fixture for browser testing.</p><form id="login" onsubmit="return false"><input autocomplete="username" id="user" placeholder="Phone number"><input autocomplete="current-password" type="password" id="password" placeholder="Password"><button type="button" id="submit">Sign in</button></form><a id="external" href="https://other.test/">Open external page</a></main><nav><span>Sports</span><span>Games</span><span>Deposit</span><span>PromoCode</span><span>TV</span></nav><script>window.gameCheckpoint=73;</script></body></html>"""

    @Before fun prepareIsolatedPreview(){
        assumeTrue(BuildConfig.DEBUG)
        context.getSharedPreferences("appcontrol",Context.MODE_PRIVATE).edit().putBoolean("vpn_permission_asked",true).putBoolean("initial_location_done",true).putBoolean("initial_notifications_done",true).apply()
        listOf("browser-session.bin","browser-session.bin.bak","browser-session.bin.new").forEach{File(context.filesDir,it).delete()}
        val api=(context.applicationContext as App).api
        api.configuration.remove("release");api.configuration.remove("maintenance")
        api.configuration.put("website_url","about:blank").put("dns",JSONObject().put("enabled",false))
    }

    @After fun finishPreviewAndDrainItsHistoryWrites(){
        activeScenario?.close();activeScenario=null
        // Production history writes finish asynchronously after Activity destruction.
        // Drain that writer before the next test deletes its isolated preview history.
        historyWriter?.let{assertTrue(it.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS))};historyWriter=null
    }

    private fun waitFor(condition:()->Boolean){
        val until=SystemClock.uptimeMillis()+15000
        while(SystemClock.uptimeMillis()<until){if(condition())return;SystemClock.sleep(80)}
        fail("Expected browser state was not reached")
    }
    private fun selectedWeb(activity:MainActivity):WebView?{
        val tab=MainActivity::class.java.getDeclaredField("selected").apply{isAccessible=true}.get(activity)?:return null
        return tab.javaClass.getDeclaredField("web").apply{isAccessible=true}.get(tab) as? WebView
    }
    private fun start():ActivityScenario<MainActivity>{
        val scenario=ActivityScenario.launch(MainActivity::class.java);activeScenario=scenario
        scenario.onActivity{activity->
            val store=MainActivity::class.java.getDeclaredField("sessionStore").apply{isAccessible=true}.get(activity)
            historyWriter=store.javaClass.getDeclaredField("executor").apply{isAccessible=true}.get(store) as java.util.concurrent.ExecutorService
        }
        waitFor{var ready=false;scenario.onActivity{ready=selectedWeb(it)!=null};ready}
        scenario.onActivity{activity->
            activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            val api=(activity.application as App).api
            api.configuration.put("website_url",BuildConfig.WEBSITE_URL);api.configurationListener?.invoke()
            val web=selectedWeb(activity)!!;val original=web.webViewClient
            val fixture=BuildConfig.WEBSITE_URL.trimEnd('/')+"/__betna_fixture__?id="+UUID.randomUUID()
            // Use a real HTTPS navigation origin. loadData can expose an opaque message origin.
            // Keep the production callbacks and origin checks intact while supplying offline HTML.
            web.webViewClient=object:WebViewClient(){
                override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse?=
                    if(request.url.toString()==fixture)WebResourceResponse("text/html","UTF-8",site.byteInputStream()) else original.shouldInterceptRequest(view,request)
                override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest)=original.shouldOverrideUrlLoading(view,request)
                override fun onPageStarted(view:WebView,url:String?,icon:Bitmap?)=original.onPageStarted(view,url,icon)
                override fun onPageFinished(view:WebView,url:String?)=original.onPageFinished(view,url)
                override fun onReceivedError(view:WebView,request:WebResourceRequest,error:WebResourceError)=original.onReceivedError(view,request,error)
                override fun onRenderProcessGone(view:WebView,detail:RenderProcessGoneDetail)=original.onRenderProcessGone(view,detail)
            }
            web.loadUrl(fixture)
        }
        waitFor{js(scenario,"window.gameCheckpoint") == "73"}
        return scenario
    }
    private fun js(scenario:ActivityScenario<MainActivity>,script:String):String?{
        val result=AtomicReference<String?>();val latch=java.util.concurrent.CountDownLatch(1)
        scenario.onActivity{activity->selectedWeb(activity)?.evaluateJavascript(script){result.set(it);latch.countDown()}?:latch.countDown()}
        assertTrue(latch.await(5,java.util.concurrent.TimeUnit.SECONDS));return result.get()
    }
    private fun find(view:View,description:String):View?{
        if(view.contentDescription?.toString()==description)return view
        if(view is ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i),description)?.let{return it}
        return null
    }
    private fun screenshot(name:String){
        instrumentation.waitForIdleSync();SystemClock.sleep(200)
        val bitmap=instrumentation.uiAutomation.takeScreenshot() ?: throw AssertionError("The emulator display must be awake for visual review")
        val folder=context.getExternalFilesDir("ui-review")!!;folder.mkdirs()
        File(folder,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    private fun invoke(activity:MainActivity,name:String,value:JSONObject){
        MainActivity::class.java.getDeclaredMethod(name,JSONObject::class.java).apply{isAccessible=true}.invoke(activity,value)
    }

    @Test fun rotationAndBackgroundPreserveTheLiveGameAndSingleTabHidesChooser(){
        start().use{scenario->
            val web=AtomicReference<WebView>()
            scenario.onActivity{activity->
                web.set(selectedWeb(activity));assertFalse(find(activity.window.decorView,"Choose between 1 tabs")!!.isShown)
                val content=MainActivity::class.java.getDeclaredField("content").apply{isAccessible=true}.get(activity) as View
                assertTrue(web.get().height>0);assertEquals(content.height,web.get().height)
                activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            SystemClock.sleep(800)
            scenario.onActivity{assertSame(web.get(),selectedWeb(it))}
            assertEquals("73",js(scenario,"window.gameCheckpoint"))
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            assertEquals("73",js(scenario,"window.gameCheckpoint"))
        }
    }

    @Test fun rendererCrashShowsRecoveryAndKeepsActivityAlive(){
        start().use{scenario->
            scenario.onActivity{selectedWeb(it)!!.loadUrl("chrome://crash")}
            waitFor{var recovered=false;scenario.onActivity{recovered=selectedWeb(it)==null&&!it.isFinishing&&!it.isDestroyed};recovered}
            waitFor{instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Retry")?.isNotEmpty()==true}
            screenshot("renderer-recovery")
        }
    }

    @Test fun registrationAndLoginBridgeCaptureValuesWithoutSendingPasswordsToTelemetry(){
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
        start().use{scenario->
            js(scenario,"document.getElementById('user').value='SyntheticAccount';document.getElementById('password').value='SyntheticPrivatePassword';document.getElementById('submit').click()")
            waitFor{var captured=false;scenario.onActivity{captured=MainActivity::class.java.getDeclaredField("pendingLogin").apply{isAccessible=true}.get(it)!=null};captured}
            SystemClock.sleep(250)
            val events=File(context.filesDir,"events.json").takeIf{it.exists()}?.readText().orEmpty()
            assertFalse(events.contains("SyntheticPrivatePassword"));assertFalse(events.contains("SyntheticAccount"))
            SystemClock.sleep(1300)
            js(scenario,"const p=document.getElementById('password');p.autocomplete='new-password';p.value='SyntheticRegistration';const c=p.cloneNode();c.id='confirm';c.value=p.value;p.form.append(c);document.getElementById('submit').click()")
            waitFor{var captured=false;scenario.onActivity{activity->val pending=MainActivity::class.java.getDeclaredField("pendingLogin").apply{isAccessible=true}.get(activity);captured=pending!=null&&pending.javaClass.getDeclaredField("registration").apply{isAccessible=true}.getBoolean(pending)};captured}
        }
    }

    @Test fun updateAndMaintenanceDoNotFinishTheApp(){
        start().use{scenario->
            scenario.onActivity{activity->
                val api=(activity.application as App).api
                api.configuration.put("maintenance",JSONObject().put("enabled",true).put("blocking",true).put("title","We will be right back").put("message","Your tabs are kept while the website takes a short break."))
                api.configurationListener?.invoke();assertFalse(activity.isFinishing)
            }
            screenshot("maintenance")
            scenario.onActivity{activity->
                val api=(activity.application as App).api;api.configuration.remove("maintenance")
                api.configuration.put("release",JSONObject().put("version_code",999).put("version_name","Test update").put("required",true).put("notes","Your tabs and saved logins stay on this phone."))
                api.configurationListener?.invoke();assertFalse(activity.isFinishing)
            }
            screenshot("required-update")
            scenario.onActivity{activity->(activity.application as App).api.configuration.remove("release");BetnaDialog.dismissAll(activity)}
        }
    }

    @Test fun renderApprovedFooterMenuBannerPopupAndTabs(){
        start().use{scenario->
            screenshot("footer")
            scenario.onActivity{find(it.window.decorView,"More options")!!.performClick()}
            screenshot("more-menu")
            scenario.onActivity{BetnaDialog.dismissAll(it)}
            val message=JSONObject().put("delivery_id",UUID.randomUUID().toString()).put("expires_at",System.currentTimeMillis()/1000+600).put("title","Something good is waiting").put("body","Keep an eye on Betna for the latest announcements.").put("allow_opt_out",true).put("type","banner")
            scenario.onActivity{invoke(it,"displayMessage",message)};screenshot("announcement-banner")
            scenario.onActivity{activity->
                val content=MainActivity::class.java.getDeclaredField("content").apply{isAccessible=true}.get(activity) as ViewGroup
                content.findViewWithTag<View>("campaign-banner")?.let{content.removeView(it)}
                message.put("delivery_id",UUID.randomUUID().toString()).put("type","popup")
                invoke(activity,"displayMessage",message)
            };screenshot("announcement-popup")
            scenario.onActivity{BetnaDialog.dismissAll(it)}
            js(scenario,"document.getElementById('external').click()")
            waitFor{var shown=false;scenario.onActivity{shown=find(it.window.decorView,"Choose between 2 tabs")?.isShown==true};shown}
            scenario.onActivity{find(it.window.decorView,"Choose between 2 tabs")!!.performClick()};screenshot("tabs")
        }
    }
}
