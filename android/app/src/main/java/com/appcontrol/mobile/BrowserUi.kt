package com.appcontrol.mobile

import android.content.res.Configuration
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.EditText

object BrowserUi {
    val red=Color.rgb(200,33,56)
    var ink=Color.rgb(31,35,52)
        private set
    var muted=Color.rgb(111,119,138)
        private set
    fun dark(context:Context)=context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    fun refresh(context:Context){ink=if(dark(context))Color.rgb(246,246,249) else Color.rgb(31,35,52);muted=if(dark(context))Color.rgb(173,175,188) else Color.rgb(111,119,138)}
    fun canvas(context:Context)=if(dark(context))Color.rgb(29,30,36) else Color.WHITE
    fun fill(context:Context)=if(dark(context))Color.rgb(42,43,52) else Color.rgb(243,244,248)
    fun line(context:Context)=if(dark(context))Color.rgb(66,67,76) else Color.rgb(222,224,232)
    fun text(context:Context,label:CharSequence,size:Int=15,bold:Boolean=false)=TextView(context).apply{text=label;textSize=size.toFloat();setTextColor(ink);typeface=Typeface.create(if(bold)"sans-serif-medium" else "sans-serif",Typeface.NORMAL)}
    fun field(context:Context,label:String,password:Boolean=false)=EditText(context).apply{
        hint=label;textSize=16f;setTextColor(ink);setHintTextColor(muted);setSingleLine()
        background=surface(context,fill(context),13);minHeight=dp(context,52);setPadding(dp(context,14),dp(context,12),dp(context,14),dp(context,12))
        if(password)inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    fun dp(context:Context,n:Int)=(context.resources.displayMetrics.density*n).toInt()
    fun surface(context:Context,color:Int=Color.WHITE,radius:Int=20)=GradientDrawable().apply{setColor(color);cornerRadius=dp(context,radius).toFloat()}
    fun button(context:Context,label:String,primary:Boolean=false,action:()->Unit)=Button(context).apply{
        text=label;isAllCaps=false;textSize=14f;setTextColor(if(primary)Color.WHITE else ink);typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
        background=RippleDrawable(ColorStateList.valueOf(0x22444444),surface(context,if(primary)red else fill(context),15),null)
        minHeight=dp(context,48);setPadding(dp(context,16),dp(context,10),dp(context,16),dp(context,10));setOnClickListener{action()}
    }
    fun polish(dialog:BetnaDialog){
        for(which in listOf(BetnaDialog.BUTTON_POSITIVE,BetnaDialog.BUTTON_NEGATIVE,BetnaDialog.BUTTON_NEUTRAL)){
            dialog.getButton(which)?.apply{isAllCaps=false;textSize=14f;setTextColor(if(which==BetnaDialog.BUTTON_POSITIVE)red else muted);typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)}
        }
        dialog.getButton(BetnaDialog.BUTTON_POSITIVE)?.setTextColor(Color.WHITE)
    }
}
