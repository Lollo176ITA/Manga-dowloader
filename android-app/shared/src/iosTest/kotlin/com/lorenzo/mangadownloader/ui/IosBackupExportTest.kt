package com.lorenzo.mangadownloader.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IosBackupExportTest {
    @Test
    fun exportWaitsForTheNativePickerToFinish() = runTest {
        var result: IosBooleanResult? = null
        val export = async { awaitIosBackupExport { result = it } }
        runCurrent()
        assertFalse(export.isCompleted)
        result!!.complete(true)
        export.await()
        assertTrue(export.isCompleted)
    }

    @Test
    fun dismissingThePickerDoesNotReportSuccess() = runTest {
        assertFailsWith<CancellationException> {
            awaitIosBackupExport { it.complete(false) }
        }
    }

    @Test
    fun aCallbackAfterCoroutineCancellationIsHarmless() = runTest {
        var result: IosBooleanResult? = null
        val export = async { awaitIosBackupExport { result = it } }
        runCurrent()
        export.cancel()
        result!!.complete(true)
        assertTrue(export.isCancelled)
    }
}
