package app.marxistlibrary.reader

import app.marxistlibrary.reader.download.TransferJobs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TransferJobsTest {
    @Test
    fun anUnexpectedFailureIsReportedBeforeFinishingTheAndroidJob() = runBlocking {
        val jobs = TransferJobs(this)
        val failure = IllegalStateException("The retry could not be scheduled")
        val reported = mutableListOf<Exception>()
        val done = CompletableDeferred<Unit>()
        var finishes = 0
        jobs.start(7, failed = { reported += it }, finished = {
            finishes++
            done.complete(Unit)
        }) {
            throw failure
        }

        done.await()
        assertSame(failure, reported.single())
        assertEquals(1, finishes)
    }

    @Test
    fun aStoppedJobCannotFinishItsReplacementWithTheSameId() = runBlocking {
        val jobs = TransferJobs(this)
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val replacementFinished = CompletableDeferred<Unit>()
        val failures = mutableListOf<Exception>()
        var oldFinishes = 0
        jobs.start(7, failed = { failures += it }, finished = { oldFinishes++ }) {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }

        started.await()
        jobs.stop(7)
        jobs.start(7, failed = { failures += it }, finished = { replacementFinished.complete(Unit) }) { yield() }
        cancelled.await()
        replacementFinished.await()
        assertEquals(0, oldFinishes)
        assertEquals(emptyList<Exception>(), failures)
    }
}
