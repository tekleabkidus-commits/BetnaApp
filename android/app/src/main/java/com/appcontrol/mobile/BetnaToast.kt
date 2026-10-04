package com.appcontrol.mobile

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class BetnaToast private constructor(private val context:Context,private val label:CharSequence,private val duration:Int){
    fun show(){
        val activity=context as? Activity?:return
        if(activity.isFinishing||activity.isDestroyed)return
        val frame=activity.findViewById<FrameLayout>(android.R.id.content)?:return
        frame.findViewWithTag<View>("betna-notice")?.let{frame.removeView(it)}
        val notice=BrowserUi.text(context,label,14).apply{
            tag="betna-notice";gravity=Gravity.CENTER_VERTICAL
            setPadding(BrowserUi.dp(context,18),BrowserUi.dp(context,14),BrowserUi.dp(context,18),BrowserUi.dp(context,14))
            background=BrowserUi.surface(context,BrowserUi.canvas(context),18);elevation=BrowserUi.dp(context,12).toFloat()
        }
        val insets=ViewCompat.getRootWindowInsets(frame)
        val bottom=insets?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())?.bottom?:0
        frame.addView(notice,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply{
            setMargins(BrowserUi.dp(context,16),0,BrowserUi.dp(context,16),bottom+BrowserUi.dp(context,if(insets?.isVisible(WindowInsetsCompat.Type.ime())==true)12 else 96))
        })
        notice.announceForAccessibility(label)
        Handler(Looper.getMainLooper()).postDelayed({if(!activity.isDestroyed&&notice.parent===frame)frame.removeView(notice)},if(duration==LENGTH_LONG)5000 else 3000)
    }
    companion object{
        const val LENGTH_SHORT=0
        const val LENGTH_LONG=1
        fun makeText(context:Context,label:CharSequence,duration:Int)=BetnaToast(context,label,duration)
    }
}
