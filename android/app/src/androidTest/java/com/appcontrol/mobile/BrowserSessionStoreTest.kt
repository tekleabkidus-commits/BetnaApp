package com.appcontrol.mobile

import android.content.ContextWrapper
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class BrowserSessionStoreTest {
    @Test fun historySurvivesStoreRecreationAndIsEncryptedOnDisk() {
        val original=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(original.cacheDir,"session-test-${UUID.randomUUID()}").apply{mkdirs()}
        val context=object:ContextWrapper(original){override fun getFilesDir()=directory}
        try {
            val first=BrowserSessionStore(context)
            val history=Bundle().apply{putString("selected","main");putString("private-history","https://betna.invalid/game?session=local-only")}
            first.write(history)
            val ready=CountDownLatch(1)
            first.read { ready.countDown() }
            assertTrue(ready.await(10,TimeUnit.SECONDS));first.close()
            assertFalse(File(directory,"browser-session.bin").readBytes().toString(Charsets.ISO_8859_1).contains("local-only"))
            val second=BrowserSessionStore(context);val result=AtomicReference<Bundle?>();val restored=CountDownLatch(1)
            second.read{result.set(it);restored.countDown()}
            assertTrue(restored.await(10,TimeUnit.SECONDS));assertEquals("main",result.get()?.getString("selected"));assertEquals(history.getString("private-history"),result.get()?.getString("private-history"));second.close()
        } finally {directory.deleteRecursively()}
    }
    @Test fun corruptSnapshotFallsBackWithoutCrashingOrClearingOtherData() {
        val original=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(original.cacheDir,"session-test-${UUID.randomUUID()}").apply{mkdirs()}
        val context=object:ContextWrapper(original){override fun getFilesDir()=directory}
        try {
            File(directory,"browser-session.bin").writeBytes(ByteArray(100){42});File(directory,"unrelated").writeText("keep")
            val store=BrowserSessionStore(context);val ready=CountDownLatch(1);var value:Bundle?=Bundle()
            store.read{value=it;ready.countDown()};assertTrue(ready.await(10,TimeUnit.SECONDS));assertNull(value);assertEquals("keep",File(directory,"unrelated").readText());store.close()
        }finally{directory.deleteRecursively()}
    }
}
