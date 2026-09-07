package ml.melun.mangaview.viewer

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class QualificationMemoryTest {
    @Test fun catalogBaselineDoesNotHideStartupGrowthAndSeparatesViewerResidual() {
        val samples = listOf(
            OwnedPssSample("before-catalog", 0L, mapOf(1 to 100L)),
            OwnedPssSample("before-viewer", 1L, mapOf(1 to 100L, 2 to 300_000L)),
            OwnedPssSample("active", 2L, mapOf(1 to 110L, 2 to 300_000L)),
            OwnedPssSample("after-viewer", 3L, mapOf(1 to 105L, 2 to 300_000L)),
        )
        val errors = QualificationMemoryPolicy.violations(samples, null)
        assertTrue(errors.any { it.contains("PSS rise") })
        assertFalse(errors.any { it.contains("residual") })
    }

    @Test fun adaptiveCeilingAndConservativeFallback() {
        assertEquals(256L * 1_024, QualificationMemoryPolicy.maximumRiseKib(null))
        assertEquals(128L * 1_024, QualificationMemoryPolicy.maximumRiseKib(2L * 1_024 * 1_024 * 1_024))
        assertEquals(768L * 1_024, QualificationMemoryPolicy.maximumRiseKib(32L * 1_024 * 1_024 * 1_024))
    }

    @Test fun isolatedRendererOwnedByPackageIsIncluded() {
        val raw = """
            *APP* UID 10001 ProcessRecord{abcd 123:app.reader/u0a1}
              packageList={app.reader}
            *APP* UID 99001 ProcessRecord{defg 234:webview:sandbox/u0i1}
              packageList={app.reader, com.android.webview}
            *APP* UID 10002 ProcessRecord{hijk 345:other.app/u0a2}
              packageList={other.app}
        """.trimIndent()
        assertEquals(setOf(123, 234), OwnedProcessParser.ownedPids(raw, "app.reader"))
        assertEquals(34567L, OwnedProcessParser.totalPss("TOTAL PSS: 34567 TOTAL RSS: 56789"))
    }

    @Test fun processRecordIdentitySeparatesPidReuseFromAStaleDeadRecord() {
        val before = """
            *APP* UID 99097 ProcessRecord{77ea5ee 14748:com.google.android.webview:sandboxed_process0:2/u0a236i97}
              packageList={app.reader}
              startSeq=424
        """.trimIndent()
        val unchanged = before
        val reused = before.replace("77ea5ee", "deadbeef").replace("startSeq=424", "startSeq=425")

        val beforeIdentity = OwnedProcessParser.ownedProcessIdentities(before, "app.reader").getValue(14748)
        assertEquals(beforeIdentity, OwnedProcessParser.ownedProcessIdentities(unchanged, "app.reader").getValue(14748))
        assertNotEquals(beforeIdentity, OwnedProcessParser.ownedProcessIdentities(reused, "app.reader").getValue(14748))
        assertTrue(OwnedProcessParser.isProcessGone("No process found for: 14748", 14748))
        assertFalse(OwnedProcessParser.isProcessGone("No process found for: 14748", 14749))
        assertFalse(OwnedProcessParser.isProcessGone("** MEMINFO in pid 14748 [webview] **", 14748))
    }

    @Test fun missingPssDecisionRequiresExactDeathEvidenceAndSeparatesReuse() {
        val before = "99097|77ea5ee|424|webview:2"
        val same = before
        val reused = "99097|deadbeef|425|webview:3"
        assertEquals(
            OwnedPssMissingDisposition.PROCESS_EXITED,
            OwnedProcessParser.classifyMissingPss(14748, before, "No process found for: 14748", same),
        )
        assertEquals(
            OwnedPssMissingDisposition.PID_REUSED,
            OwnedProcessParser.classifyMissingPss(14748, before, "No process found for: 14748", reused),
        )
        assertEquals(
            OwnedPssMissingDisposition.LIVE_PSS_MISSING,
            OwnedProcessParser.classifyMissingPss(14748, before, "", same),
        )
        assertEquals(
            OwnedPssMissingDisposition.UNVERIFIED_PSS_MISSING,
            OwnedProcessParser.classifyMissingPss(14748, before, "", null),
        )
        assertEquals(
            OwnedPssMissingDisposition.UNVERIFIED_PSS_MISSING,
            OwnedProcessParser.classifyMissingPss(14748, before, "No process found for: 14749", null),
        )
    }

    @Test fun processChurnIsSeparateFromPssAndDoesNotBecomeAZeroProcess() {
        val sample = OwnedPssSample(
            stage = "active",
            requestedAtNanos = 1_000_000L,
            startedAtNanos = 1_000_000L,
            finishedAtNanos = 2_000_000L,
            processes = mapOf(13865 to 100L),
            processChurn = listOf(OwnedProcessChurn(
                pid = 14748,
                beforeIdentity = "99097|77ea5ee|424|webview:2",
                afterIdentity = null,
                reason = "process-exited-before-pss",
            )),
        )
        assertEquals(100L, sample.totalKib)
        assertFalse(sample.processes.containsKey(14748))
        assertEquals("process-exited-before-pss", sample.processChurn.single().reason)
    }

    @Test fun normalActiveWarmupVariationIsNotMistakenForResidualLeak() {
        val samples = listOf(
            OwnedPssSample("before-viewer", 0, mapOf(1 to 100_000L)),
            OwnedPssSample("active", 1, mapOf(1 to 170_000L)),
            OwnedPssSample("active", 2, mapOf(1 to 110_000L)),
            OwnedPssSample("after-viewer", 3, mapOf(1 to 105_000L)),
        )
        assertTrue(QualificationMemoryPolicy.violations(samples, null).isEmpty())
        assertTrue(QualificationMemoryPolicy.violations(samples.dropLast(1) +
            OwnedPssSample("after-viewer", 4, mapOf(1 to 170_000L)), null).isNotEmpty())
    }

    @Test(timeout = 5_000)
    fun activeCaptureReturnsWhileBlockedAndRecordsEverySkippedRequest() {
        val directory = temporaryDirectory()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val worker = AtomicReference<Thread>()
        val caller = Thread.currentThread()
        val source = OwnedPssMeasurementSource {
            worker.set(Thread.currentThread())
            assertEquals(1, calls.incrementAndGet())
            entered.countDown()
            assertTrue(release.await(2, TimeUnit.SECONDS))
            mapOf(123 to 100L)
        }
        val memory = QualificationMemory(directory, source, totalRamBytes = null)
        try {
            memory.capture("active")
            assertTrue(entered.await(2, TimeUnit.SECONDS))

            memory.capture("active")
            memory.capture("active")

            val skipped = memory.samplesSnapshotForTest().filter { it.outcome == "skipped" }
            assertEquals(2, skipped.size)
            assertTrue(skipped.all {
                it.stage == "active" && it.actualStage == "active-skipped" &&
                    it.reason == "measurement-in-flight" && it.startedAtNanos == null &&
                    it.finishedAtNanos == null && it.requestedAtNanos > 0L
            })
            assertNotNull(worker.get())
            assertNotEquals(caller, worker.get())

            release.countDown()
            memory.finish()
            assertTrue(memory.isSamplerTerminatedForTest())
            assertTrue(directory.resolve("memory.json").readText().contains("requestedAtNanos"))
        } finally {
            release.countDown()
            runCatching { memory.finish() }
            directory.deleteRecursively()
        }
    }

    @Test(timeout = 5_000)
    fun afterBoundaryWaitsForActiveWorkAndKeepsActualPhaseAndMonotonicTimes() {
        val directory = temporaryDirectory()
        val activeEntered = CountDownLatch(1)
        val releaseActive = CountDownLatch(1)
        val calls = AtomicInteger()
        val boundaryDone = CountDownLatch(1)
        val boundaryFailure = AtomicReference<Throwable>()
        val source = OwnedPssMeasurementSource {
            when (calls.incrementAndGet()) {
                1 -> {
                    activeEntered.countDown()
                    assertTrue(releaseActive.await(2, TimeUnit.SECONDS))
                    mapOf(1 to 100L)
                }
                else -> mapOf(1 to 105L)
            }
        }
        val memory = QualificationMemory(directory, source, totalRamBytes = null)
        try {
            memory.capture("active")
            assertTrue(activeEntered.await(2, TimeUnit.SECONDS))
            val boundary = Thread {
                try {
                    memory.capture("after-viewer")
                } catch (failure: Throwable) {
                    boundaryFailure.set(failure)
                } finally {
                    boundaryDone.countDown()
                }
            }
            boundary.start()

            assertFalse(boundaryDone.await(100, TimeUnit.MILLISECONDS))
            val boundaryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (memory.closeBoundaryAtNanosForTest() == null && System.nanoTime() < boundaryDeadline) {
                Thread.yield()
            }
            assertNotNull(memory.closeBoundaryAtNanosForTest())
            memory.capture("active")
            val skippedAfterClose = memory.samplesSnapshotForTest().single {
                it.outcome == "skipped" && it.reason == "viewer-close-boundary"
            }
            assertNull(skippedAfterClose.startedAtNanos)
            assertEquals(1, calls.get())
            releaseActive.countDown()
            assertTrue(boundaryDone.await(2, TimeUnit.SECONDS))
            boundary.join(2_000)
            assertNull(boundaryFailure.get())
            assertEquals(2, calls.get())

            val measured = memory.samplesSnapshotForTest().filter { it.outcome == "measured" }
            assertEquals(listOf("active", "after-viewer"), measured.map { it.stage })
            assertEquals("active-overlaps-close", measured[0].actualStage)
            assertEquals("after-viewer", measured[1].actualStage)
            measured.forEach { sample ->
                assertNotNull(sample.startedAtNanos)
                assertNotNull(sample.finishedAtNanos)
                assertTrue(sample.requestedAtNanos <= sample.startedAtNanos!!)
                assertTrue(sample.startedAtNanos <= sample.finishedAtNanos!!)
            }
            assertTrue(measured[0].finishedAtNanos!! <= measured[1].startedAtNanos!!)
            memory.finish()
            assertTrue(memory.isSamplerTerminatedForTest())
        } finally {
            releaseActive.countDown()
            runCatching { memory.finish() }
            directory.deleteRecursively()
        }
    }

    @Test(timeout = 5_000)
    fun finishSurfacesAsynchronousFailureAndTerminatesWorker() {
        val directory = temporaryDirectory()
        val source = OwnedPssMeasurementSource { throw IllegalStateException("injected PSS failure") }
        val memory = QualificationMemory(directory, source, totalRamBytes = null)
        try {
            memory.capture("active")
            val failure = try {
                memory.finish()
                null
            } catch (thrown: IllegalStateException) {
                thrown
            }
            assertNotNull(failure)
            assertEquals("injected PSS failure", failure!!.cause?.message)
            val failed = memory.samplesSnapshotForTest().single { it.outcome == "failed" }
            assertEquals("active", failed.stage)
            assertTrue(failed.reason.orEmpty().contains("injected PSS failure"))
            assertTrue(memory.isSamplerTerminatedForTest())
        } finally {
            runCatching { memory.finish() }
            directory.deleteRecursively()
        }
    }

    @Test(timeout = 5_000)
    fun measurementCompletingAfterCloseBoundaryIsNotLabeledActive() {
        val directory = temporaryDirectory()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finishDone = CountDownLatch(1)
        val finishFailure = AtomicReference<Throwable>()
        val source = OwnedPssMeasurementSource {
            entered.countDown()
            assertTrue(release.await(2, TimeUnit.SECONDS))
            mapOf(1 to 100L)
        }
        val memory = QualificationMemory(directory, source, totalRamBytes = null)
        try {
            memory.capture("active")
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val finisher = Thread {
                try {
                    memory.finish()
                } catch (failure: Throwable) {
                    finishFailure.set(failure)
                } finally {
                    finishDone.countDown()
                }
            }
            finisher.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!memory.isFinishingForTest() && System.nanoTime() < deadline) Thread.yield()
            assertTrue(memory.isFinishingForTest())
            release.countDown()
            assertTrue(finishDone.await(2, TimeUnit.SECONDS))
            finisher.join(2_000)
            assertNull(finishFailure.get())
            val sample = memory.samplesSnapshotForTest().single { it.outcome == "measured" }
            assertEquals("active-overlaps-close", sample.actualStage)
            assertTrue(memory.isSamplerTerminatedForTest())
        } finally {
            release.countDown()
            runCatching { memory.finish() }
            directory.deleteRecursively()
        }
    }

    @Test(timeout = 5_000)
    fun completedActiveRequestCanBeFollowedWithoutHandoffRejection() {
        val directory = temporaryDirectory()
        val calls = AtomicInteger()
        val source = OwnedPssMeasurementSource {
            mapOf(calls.incrementAndGet() to 100L)
        }
        val memory = QualificationMemory(directory, source, totalRamBytes = null)
        try {
            repeat(100) {
                memory.capture("active")
                memory.awaitActiveForTest()
            }
            assertEquals(100, calls.get())
            assertTrue(memory.samplesSnapshotForTest().all { it.outcome == "measured" })
            memory.finish()
            assertTrue(memory.isSamplerTerminatedForTest())
        } finally {
            runCatching { memory.finish() }
            directory.deleteRecursively()
        }
    }

    @Test(timeout = 10_000)
    fun uninterruptibleMeasurementTimesOutAndDoesNotClaimSamplerClosure() {
        val directory = temporaryDirectory()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val source = OwnedPssMeasurementSource {
            entered.countDown()
            var released = false
            while (!released) {
                try {
                    released = release.await(20, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    // Deliberately ignore cancellation until the test releases this source.
                }
            }
            mapOf(1 to 100L)
        }
        val memory = QualificationMemory(
            directory,
            source,
            totalRamBytes = null,
            activeDrainTimeoutMillis = 100L,
            samplerTerminationTimeoutMillis = 100L,
        )
        try {
            memory.capture("active")
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val startedAt = System.nanoTime()
            val failure = try {
                memory.finish()
                null
            } catch (thrown: IllegalStateException) {
                thrown
            }
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertNotNull(failure)
            assertTrue(elapsedMillis < 4_000L)
            assertFalse(memory.isSamplerTerminatedForTest())
            assertTrue(directory.resolve("memory.json").readText().contains("\"samplerTerminated\": false"))
            assertTrue(memory.samplesSnapshotForTest().any { it.actualStage == "active-timeout" })

            release.countDown()
            val terminationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!memory.isSamplerTerminatedForTest() && System.nanoTime() < terminationDeadline) {
                Thread.yield()
            }
            assertTrue(memory.isSamplerTerminatedForTest())
        } finally {
            release.countDown()
            runCatching { memory.finish() }
            directory.deleteRecursively()
        }
    }

    @Test(timeout = 5_000)
    fun beginViewerCloseIsNonblockingAndRejectsNewActiveRequest() {
        val directory = temporaryDirectory()
        val sourceCalls = AtomicInteger()
        val source = OwnedPssMeasurementSource {
            sourceCalls.incrementAndGet()
            mapOf(1 to 100L)
        }
        val memory = QualificationMemory(directory, source, totalRamBytes = null)
        try {
            val startedAt = System.nanoTime()
            memory.beginViewerClose()
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertTrue(elapsedMillis < 100L)
            memory.capture("active")
            val skipped = memory.samplesSnapshotForTest().single { it.outcome == "skipped" }
            assertEquals("viewer-close-boundary", skipped.reason)
            assertEquals(0, sourceCalls.get())
            memory.finish()
            assertTrue(memory.isSamplerTerminatedForTest())
        } finally {
            runCatching { memory.finish() }
            directory.deleteRecursively()
        }
    }

    private fun temporaryDirectory(): File = File.createTempFile("qualification-memory-test", ".dir").apply {
        delete()
        mkdirs()
    }
}
