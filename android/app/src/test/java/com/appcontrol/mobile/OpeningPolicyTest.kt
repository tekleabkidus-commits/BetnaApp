package com.appcontrol.mobile
import org.junit.Assert.*
import org.junit.Test
class OpeningPolicyTest {
    @Test fun lateOpeningResponseIsSkipped() = assertFalse(OpeningPolicy.canDisplay(true,true,0,5001,5,false))
    @Test fun backgroundResponseIsSkipped() = assertFalse(OpeningPolicy.canDisplay(true,false,0,1000,5,false))
    @Test fun requiredUpdateTakesPriority() = assertFalse(OpeningPolicy.canDisplay(true,true,0,1000,5,true))
    @Test fun intervalMessageDoesNotUseOpeningDeadline() = assertTrue(OpeningPolicy.canDisplay(false,true,0,90000,5,false))
    @Test fun openingTriggerIsExplicit() { assertTrue(OpeningPolicy.isOpening("updated"));assertFalse(OpeningPolicy.isOpening("foreground")) }
}
