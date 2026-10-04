package com.appcontrol.mobile

import org.junit.Assert.*
import org.junit.Test

class PermissionReminderPolicyTest {
    private val day=86400000L
    @Test fun alternatesEveryTwoDaysPerPermissionAfterInitialRequests() {
        val p=PermissionReminderPolicy
        assertNull(p.next(day,1,0,-1,PermissionReminderPolicy.Kind.Location,false,false,0,0))
        assertEquals(PermissionReminderPolicy.Kind.Location,p.next(2*day,2,0,-1,PermissionReminderPolicy.Kind.Location,false,false,0,0))
        assertEquals(PermissionReminderPolicy.Kind.Notifications,p.next(3*day,3,0,2,PermissionReminderPolicy.Kind.Notifications,false,false,2*day,0))
        assertEquals(PermissionReminderPolicy.Kind.Location,p.next(4*day,4,0,3,PermissionReminderPolicy.Kind.Location,false,false,2*day,3*day))
        assertNull(p.next(4*day,4,0,4,PermissionReminderPolicy.Kind.Notifications,false,false,4*day,3*day))
    }
    @Test fun acceptedPermissionStopsAndMissedLoginDaysDoNotStarveOtherPermission() {
        val p=PermissionReminderPolicy
        assertEquals(PermissionReminderPolicy.Kind.Notifications,p.next(10*day,10,0,9,PermissionReminderPolicy.Kind.Location,true,false,0,0))
        assertEquals(PermissionReminderPolicy.Kind.Location,p.next(12*day,12,0,10,PermissionReminderPolicy.Kind.Location,false,false,2*day,10*day))
        assertNull(p.next(12*day,12,0,10,PermissionReminderPolicy.Kind.Location,true,true,0,0))
    }
    @Test fun clockChangesAndShortIntervalsDoNotRepeatRequests() {
        val p=PermissionReminderPolicy
        assertNull(p.next(5*day,5,0,4,PermissionReminderPolicy.Kind.Location,false,true,4*day,0))
        assertNull(p.next(day,1,2*day,-1,PermissionReminderPolicy.Kind.Location,false,false,2*day,2*day))
    }
}
