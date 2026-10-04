package com.appcontrol.mobile

object PermissionReminderPolicy {
    enum class Kind { Location, Notifications }
    const val INTERVAL=48*60*60*1000L
    fun next(now:Long,day:Long,installedAt:Long,lastDay:Long,next:Kind,locationGranted:Boolean,notificationsGranted:Boolean,lastLocation:Long,lastNotifications:Long):Kind? {
        if(now-installedAt<INTERVAL||day==lastDay) return null
        val locationDue=!locationGranted&&(lastLocation==0L||now-lastLocation>=INTERVAL)
        val notificationsDue=!notificationsGranted&&(lastNotifications==0L||now-lastNotifications>=INTERVAL)
        return when {
            next==Kind.Location&&locationDue->Kind.Location
            next==Kind.Notifications&&notificationsDue->Kind.Notifications
            locationDue->Kind.Location
            notificationsDue->Kind.Notifications
            else->null
        }
    }
}
