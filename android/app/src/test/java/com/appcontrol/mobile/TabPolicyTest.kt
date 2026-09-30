package com.appcontrol.mobile
import org.junit.Assert.*
import org.junit.Test
class TabPolicyTest {
    @Test fun mainNeverExpires() = assertFalse(TabPolicy.expired(true,true,0,0,9_000_000,60,"opened"))
    @Test fun exactHourClosesEvenSelected() = assertTrue(TabPolicy.expired(false,true,0,3_599_999,3_600_000,60,"opened"))
    @Test fun activityBasisExtends() = assertFalse(TabPolicy.expired(false,true,0,3_599_999,3_600_000,60,"activity"))
    @Test fun disabledDoesNotClose() = assertFalse(TabPolicy.expired(false,false,0,0,9_000_000,60,"opened"))
}
