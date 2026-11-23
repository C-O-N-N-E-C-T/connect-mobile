package com.connect.mobile.data.supabase

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** OSP-75: çevrimiçi + ön plan + oturum -> yenileme; diğer her durumda durdurma. */
@OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher / runCurrent
class AutoRefreshControllerTest {
    private class FakeTarget : AutoRefreshTarget {
        val session = MutableStateFlow(false)
        val calls = mutableListOf<String>()
        var resumeError: Exception? = null
        override val hasSession = session

        override suspend fun resumeAutoRefresh() {
            calls += "start"
            resumeError?.let { throw it }
        }

        override fun pauseAutoRefresh() {
            calls += "stop"
        }
    }

    private val online = MutableStateFlow(false)
    private val foreground = MutableStateFlow(false)
    private val target = FakeTarget()
    private val controller = AutoRefreshController(online, foreground)

    private fun TestScope.started() {
        controller.start(backgroundScope)
        controller.attach(target)
        runCurrent()
    }

    @Test fun offlineStops() = runTest(UnconfinedTestDispatcher()) {
        foreground.value = true
        target.session.value = true
        started()
        assertEquals(listOf("stop"), target.calls)
        assertFalse(controller.isRefreshAllowedNow())
    }

    @Test fun onlineForegroundWithSessionStarts() = runTest(UnconfinedTestDispatcher()) {
        online.value = true
        foreground.value = true
        target.session.value = true
        started()
        assertEquals(listOf("start"), target.calls)
        assertTrue(controller.isRefreshAllowedNow())
    }

    @Test fun noSessionNeverStarts() = runTest(UnconfinedTestDispatcher()) {
        online.value = true
        foreground.value = true
        started()
        foreground.value = false
        foreground.value = true
        online.value = false
        online.value = true
        runCurrent()
        assertFalse(target.calls.toString(), target.calls.contains("start"))
    }

    @Test fun followsConnectivityAndLifecycleTransitions() = runTest(UnconfinedTestDispatcher()) {
        online.value = true
        foreground.value = true
        started()                         // oturum yok -> stop
        target.session.value = true       // giriş yapıldı -> start
        online.value = false              // uçak modu -> stop
        online.value = true               // ağ geri geldi -> start
        foreground.value = false          // arka plan -> stop
        foreground.value = true           // ön plan -> start
        target.session.value = false      // logout -> stop
        runCurrent()
        assertEquals(listOf("stop", "start", "stop", "start", "stop", "start", "stop"), target.calls)
    }

    @Test fun sameDecisionIsNotRepeated() = runTest(UnconfinedTestDispatcher()) {
        foreground.value = true
        target.session.value = true
        started()                         // çevrimdışı -> stop
        foreground.value = false          // hâlâ "çalışmamalı", oturum aynı -> tekrar çağrılmaz
        foreground.value = true
        runCurrent()
        assertEquals(listOf("stop"), target.calls)
    }

    /**
     * O3: çevrimdışıyken oturum yüklenince (false -> true) karar yine "durdur" olsa da yeniden
     * uygulanır; yükleme sırasında başlamış bir yenileme böylece durdurulur.
     */
    @Test fun sessionAppearingWhileOfflineReappliesStop() = runTest(UnconfinedTestDispatcher()) {
        foreground.value = true
        started()                         // oturum yok, çevrimdışı -> stop
        target.session.value = true       // yükleme bitti, hâlâ çevrimdışı -> yine stop
        runCurrent()
        assertEquals(listOf("stop", "stop"), target.calls)
    }

    @Test fun nothingHappensBeforeAGatewayIsAttached() = runTest(UnconfinedTestDispatcher()) {
        online.value = true
        foreground.value = true
        controller.start(backgroundScope)
        runCurrent()
        assertTrue(target.calls.isEmpty())
        assertTrue(controller.isRefreshAllowedNow()) // ilk yükleme kararı için yine de bilinir
    }

    @Test fun aFailingResumeDoesNotKillTheController() = runTest(UnconfinedTestDispatcher()) {
        online.value = true
        foreground.value = true
        target.session.value = true
        target.resumeError = IllegalStateException("No session found")
        started()
        online.value = false
        runCurrent()
        assertEquals(listOf("start", "stop"), target.calls)
    }
}
