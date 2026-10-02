package com.appcontrol.mobile

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.Button
import android.widget.TextView

object BrowserUi {
    val red=Color.rgb(200,33,56)
    val ink=Color.rgb(31,35,52)
    val muted=Color.rgb(111,119,138)
    fun dp(context:Context,n:Int)=(context.resources.displayMetrics.density*n).toInt()
    fun surface(context:Context,color:Int=Color.WHITE,radius:Int=20)=GradientDrawable().apply{setColor(color);cornerRadius=dp(context,radius).toFloat()}
    fun button(context:Context,label:String,primary:Boolean=false,action:()->Unit)=Button(context).apply{
        text=label;isAllCaps=false;textSize=14f;setTextColor(if(primary)Color.WHITE else ink);typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
        background=RippleDrawable(ColorStateList.valueOf(0x22444444),surface(context,if(primary)red else Color.rgb(242,243,248),12),null)
        minHeight=dp(context,48);setPadding(dp(context,16),0,dp(context,16),0);setOnClickListener{action()}
    }
    fun polish(dialog:AlertDialog){
        dialog.window?.setBackgroundDrawable(surface(dialog.context));dialog.window?.setDimAmount(.42f)
        for(which in listOf(AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL)){
            dialog.getButton(which)?.apply{isAllCaps=false;textSize=14f;setTextColor(if(which==AlertDialog.BUTTON_POSITIVE)red else muted);typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)}
        }
        dialog.findViewById<TextView>(android.R.id.message)?.apply{setTextColor(ink);textSize=16f}
    }
}
