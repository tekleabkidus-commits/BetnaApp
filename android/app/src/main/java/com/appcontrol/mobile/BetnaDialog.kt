package com.appcontrol.mobile

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/** App-owned presentation; Android consent and installation screens remain system controlled. */
class BetnaDialog private constructor(context:Context, private val sheet:Boolean):Dialog(context) {
    private val host=context as? Activity
    private val actions=mutableMapOf<Int,Button>()
    private lateinit var actionBox:LinearLayout
    private var allowCancel=true
    private var close:Button?=null

    override fun show() {
        val activity=host ?: return
        if(activity.isFinishing||activity.isDestroyed) return
        if(activity is LifecycleOwner&&!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        super.show()
        synchronized(openDialogs){openDialogs.getOrPut(activity){mutableSetOf()}.add(this)}
        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setDimAmount(.38f)
            setGravity(if(sheet) Gravity.BOTTOM else Gravity.CENTER)
            val width=activity.resources.displayMetrics.widthPixels
            setLayout(if(width>BrowserUi.dp(context,640)) BrowserUi.dp(context,520) else width-BrowserUi.dp(context,24),-2)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    override fun dismiss(){
        super.dismiss()
        host?.let{synchronized(openDialogs){openDialogs[it]?.remove(this)}}
    }

    override fun setCancelable(flag:Boolean) {
        allowCancel=flag;super.setCancelable(flag);setCanceledOnTouchOutside(flag)
        close?.visibility=if(flag)View.VISIBLE else View.GONE
    }

    fun getButton(which:Int):Button?=actions[which]
    fun setButton(which:Int,label:String,listener:DialogInterface.OnClickListener?) {
        actions[which]?.let { actionBox.removeView(it) }
        val button=BrowserUi.button(context,label,which==BUTTON_POSITIVE) {
            listener?.onClick(this,which);dismiss()
        }
        actions[which]=button
        actionBox.addView(button,LinearLayout.LayoutParams(-1,-2).apply{topMargin=BrowserUi.dp(context,8)})
    }

    class Builder(private val context:Context) {
        private var title:CharSequence=""
        private var message:CharSequence?=null
        private var view:View?=null
        private var sheet=false
        private var cancelable=true
        private var onCancel:DialogInterface.OnCancelListener?=null
        private var items:Array<String>?=null
        private var itemListener:DialogInterface.OnClickListener?=null
        private var single=-1
        private var multiple:BooleanArray?=null
        private var multiListener:DialogInterface.OnMultiChoiceClickListener?=null
        private val actions=linkedMapOf<Int,Pair<String,DialogInterface.OnClickListener?>>()
        fun setTitle(value:CharSequence)=apply{title=value}
        fun setMessage(value:CharSequence?)=apply{message=value}
        fun setView(value:View)=apply{view=value}
        fun setSheet(value:Boolean=true)=apply{sheet=value}
        fun setCancelable(value:Boolean)=apply{cancelable=value}
        fun setOnCancelListener(value:DialogInterface.OnCancelListener)=apply{onCancel=value}
        fun setPositiveButton(label:String,listener:DialogInterface.OnClickListener?)=apply{actions[BUTTON_POSITIVE]=label to listener}
        fun setNegativeButton(label:String,listener:DialogInterface.OnClickListener?)=apply{actions[BUTTON_NEGATIVE]=label to listener}
        fun setNeutralButton(label:String,listener:DialogInterface.OnClickListener?)=apply{actions[BUTTON_NEUTRAL]=label to listener}
        fun setItems(values:Array<String>,listener:DialogInterface.OnClickListener)=apply{items=values;itemListener=listener;sheet=true}
        fun setSingleChoiceItems(values:Array<String>,checked:Int,listener:DialogInterface.OnClickListener)=apply{items=values;single=checked;itemListener=listener;sheet=true}
        fun setMultiChoiceItems(values:Array<String>,checked:BooleanArray,listener:DialogInterface.OnMultiChoiceClickListener)=apply{items=values;multiple=checked;multiListener=listener;sheet=true}
        fun create():BetnaDialog {
            val dialog=BetnaDialog(context,sheet)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            val column=LinearLayout(context).apply {
                orientation=LinearLayout.VERTICAL
                background=BrowserUi.surface(context,BrowserUi.canvas(context),28)
                setPadding(BrowserUi.dp(context,22),BrowserUi.dp(context,if(sheet)10 else 22),BrowserUi.dp(context,22),BrowserUi.dp(context,22))
            }
            if(sheet) column.addView(View(context).apply{background=BrowserUi.surface(context,BrowserUi.line(context),4)},LinearLayout.LayoutParams(BrowserUi.dp(context,34),BrowserUi.dp(context,4)).apply{gravity=Gravity.CENTER;bottomMargin=BrowserUi.dp(context,14)})
            val header=LinearLayout(context).apply{gravity=Gravity.CENTER_VERTICAL}
            header.addView(BrowserUi.text(context,title,24,true),LinearLayout.LayoutParams(0,-2,1f))
            dialog.close=BrowserUi.button(context,"×",false){dialog.cancel()}.apply{contentDescription="Close";textSize=24f;minWidth=0;setPadding(0,0,0,0);background=BrowserUi.surface(context,BrowserUi.fill(context),14)}
            header.addView(dialog.close,LinearLayout.LayoutParams(BrowserUi.dp(context,44),BrowserUi.dp(context,44)))
            column.addView(header)
            val body=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}
            message?.let { body.addView(BrowserUi.text(context,it,15).apply{setTextColor(BrowserUi.muted);setLineSpacing(BrowserUi.dp(context,3).toFloat(),1f)},LinearLayout.LayoutParams(-1,-2).apply{topMargin=BrowserUi.dp(context,16);bottomMargin=BrowserUi.dp(context,8)}) }
            view?.let { (it.parent as? ViewGroup)?.removeView(it);body.addView(it,LinearLayout.LayoutParams(-1,-2)) }
            items?.forEachIndexed { index,label ->
                if(multiple!=null) {
                    body.addView(CheckBox(context).apply {
                        text=label;isChecked=multiple!!.getOrElse(index){false};minHeight=BrowserUi.dp(context,48);setTextColor(BrowserUi.ink);buttonTintList=android.content.res.ColorStateList.valueOf(BrowserUi.red)
                        setOnCheckedChangeListener { _,checked->multiListener?.onClick(dialog,index,checked) }
                    })
                } else {
                    body.addView(BrowserUi.button(context,if(index==single)"$label  ✓" else label) { itemListener?.onClick(dialog,index);if(single==-1)dialog.dismiss() }.apply{gravity=Gravity.START or Gravity.CENTER_VERTICAL},LinearLayout.LayoutParams(-1,-2).apply{topMargin=BrowserUi.dp(context,7)})
                }
            }
            val scroll=ScrollView(context).apply{isFillViewport=false;addView(body);isVerticalScrollBarEnabled=false}
            column.addView(scroll,LinearLayout.LayoutParams(-1,-2,1f).apply{topMargin=BrowserUi.dp(context,10)})
            dialog.actionBox=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}
            column.addView(dialog.actionBox)
            dialog.setContentView(column)
            actions.toSortedMap(compareBy { when(it){BUTTON_POSITIVE->0;BUTTON_NEGATIVE->1;else->2} }).forEach { (which,action)->dialog.setButton(which,action.first,action.second) }
            dialog.setCancelable(cancelable);dialog.setOnCancelListener(onCancel)
            // Limit long forms on small screens; scroll content instead of losing the action buttons.
            column.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_->
                val max=(context.resources.displayMetrics.heightPixels*.82).toInt()
                if(column.height>max&&scroll.height>0) { scroll.layoutParams=scroll.layoutParams.apply{height=(max-column.height+scroll.height).coerceAtLeast(BrowserUi.dp(context,64));(this as LinearLayout.LayoutParams).weight=0f} }
            }
            return dialog
        }
        fun show():BetnaDialog=create().also{it.show()}
    }
    companion object {
        private val openDialogs=java.util.WeakHashMap<Activity,MutableSet<BetnaDialog>>()
        fun dismissAll(activity:Activity){
            val dialogs=synchronized(openDialogs){openDialogs.remove(activity)?.toList().orEmpty()}
            dialogs.forEach{runCatching{it.setOnDismissListener(null);it.dismiss()}}
        }
        const val BUTTON_POSITIVE=DialogInterface.BUTTON_POSITIVE
        const val BUTTON_NEGATIVE=DialogInterface.BUTTON_NEGATIVE
        const val BUTTON_NEUTRAL=DialogInterface.BUTTON_NEUTRAL
    }
}
