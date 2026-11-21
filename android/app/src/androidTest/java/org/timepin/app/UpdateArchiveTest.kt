package org.timepin.app

import android.test.InstrumentationTestCase
import java.io.File

class UpdateArchiveTest : InstrumentationTestCase() {
    fun testDownloadedArchiveIdentity() {
        val ctx = instrumentation.targetContext
        require(ctx.packageName.endsWith(".qa"))
        val download = File.createTempFile("qa-download-", ".part", ctx.cacheDir)
        val pm = ctx.packageManager
        try {
            File(ctx.applicationInfo.sourceDir).copyTo(download, overwrite = true)
            val (archive, installed) = pm.readSignedUpdate(download, ctx.packageName)
            assertEquals(installed.packageName, archive.packageName)
            assertEquals(installed.versionName, archive.versionName)
            assertTrue(runCatching { pm.readSignedUpdate(download, "android") }.exceptionOrNull() is IllegalArgumentException)
            download.writeBytes(byteArrayOf(1, 2, 3))
            assertTrue(runCatching { pm.readSignedUpdate(download, ctx.packageName) }.exceptionOrNull() is IllegalArgumentException)
        } finally {
            download.delete()
        }
    }
}
