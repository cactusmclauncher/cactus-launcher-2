package com.cactuslauncher.state

import com.cactuslauncher.game.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlin.test.*

class HorizonStateTest {
    private class Service : GameService by unusedService() {
        var gate = CompletableDeferred<HorizonExit>()
        var starts = 0
        var cleanups = 0
        var checks = 0
        var installs = 0
        var resourcesPrepared = true
        var preparationInputs: Pair<String, String>? = null
        var failure: Exception? = null
        val installation = HorizonInstallation("fixture", "26.3", 25, "local-bundle", "")
        override suspend fun horizonInstallation() = installation
        override suspend fun horizonPrepared() = resourcesPrepared
        override suspend fun prepareHorizon(path: String, javaHome: String, onProgress: (HorizonProgress) -> Unit): String {
            preparationInputs = path to javaHome
            resourcesPrepared = true
            return "fixture resources ready"
        }
        override suspend fun horizonMinecraftSource() = ""
        override suspend fun horizonJavaHome() = ""
        override suspend fun runningBuildIds() = emptySet<String>()
        override suspend fun launchHorizon(onRunning: (String) -> Unit): HorizonExit {
            starts++
            failure?.let { throw it }
            onRunning("LocalName")
            try { return gate.await() }
            finally { cleanups++ }
        }
        override suspend fun checkHorizonUpdate(automatic: Boolean, onProgress: (HorizonProgress) -> Unit): HorizonUpdate {
            checks++
            return HorizonUpdate(installation, "fixture", false, "fixture")
        }
        override suspend fun installHorizon(onProgress: (HorizonProgress) -> Unit): HorizonInstallation {
            installs++
            return installation
        }
    }

    @Test fun automaticInstallRequestsResourceAndJdkDownloadWithoutLocalPaths() = runBlocking {
        val service = Service().apply { resourcesPrepared = false }
        val owner = SupervisorJob()
        val state = HorizonState(service, CoroutineScope(coroutineContext + owner))
        try {
            state.source = "/old/manual/source"
            state.javaHome = "/old/manual/java"
            state.installAutomatic()
            state.installAutomatic()
            await { service.installs == 1 && !state.busy }
            assertEquals("" to "", service.preparationInputs)
            assertTrue(state.prepared)
            assertEquals(2, state.installPhase)
            assertNull(state.error)
            assertEquals(0, service.starts)
            state.launch()
            await { state.running }
            service.gate.complete(HorizonExit(0, "fixture-log"))
            await { !state.busy }
            assertEquals(1, service.starts)
        } finally { owner.cancelAndJoin() }
    }

    @Test fun cachedStartupAndLaunchDoNotCheckNetwork() = runBlocking {
        val service = Service()
        val owner = SupervisorJob()
        val state = HorizonState(service, CoroutineScope(coroutineContext + owner))
        try {
            state.startup(false)
            assertTrue(state.prepared)
            assertEquals(0, service.checks)
            state.launch()
            await { state.running }
            assertEquals("LocalName", state.runningProfile)
            assertTrue(state.busy)
            state.launch()
            state.install()
            state.check()
            state.rollback()
            yield()
            assertEquals(1, service.starts)
            assertEquals(0, service.installs)
            assertEquals(0, service.checks)
            service.gate.complete(HorizonExit(0, "fixture-log"))
            await { !state.busy }
            assertFalse(state.running)
            assertNull(state.error)
            assertTrue(state.status.orEmpty().contains("fixture-log"))
        } finally { owner.cancelAndJoin() }
    }

    @Test fun cancellationClearsRunningWithoutAnErrorAndAllowsRestart() = runBlocking {
        val service = Service()
        val owner = SupervisorJob()
        val state = HorizonState(service, CoroutineScope(coroutineContext + owner))
        try {
            state.launch()
            await { state.running }
            state.cancel()
            await { !state.busy }
            assertEquals(1, service.cleanups)
            assertFalse(state.running)
            assertFalse(state.launching)
            assertNull(state.error)
            assertTrue(state.status.orEmpty().contains("остановлен"))
            service.gate = CompletableDeferred()
            state.launch()
            await { state.running }
            service.gate.complete(HorizonExit(101, "crash-log"))
            await { !state.busy }
            assertEquals(2, service.starts)
            assertTrue(state.error.orEmpty().contains("101"))
            assertTrue(state.error.orEmpty().contains("crash-log"))
        } finally { owner.cancelAndJoin() }
    }

    @Test fun launchFailureIsVisibleAndReleasesBusyState() = runBlocking {
        val service = Service().apply { failure = IllegalStateException("missing prepared resources") }
        val owner = SupervisorJob()
        val state = HorizonState(service, CoroutineScope(coroutineContext + owner))
        try {
            state.launch()
            await { service.starts == 1 && !state.busy }
            assertFalse(state.running)
            assertFalse(state.launching)
            assertEquals("missing prepared resources", state.error)
        } finally { owner.cancelAndJoin() }
    }

    private suspend fun await(condition: () -> Boolean) {
        withTimeout(5000) { while (!condition()) yield() }
    }

    companion object {
        private fun unusedService(): GameService = Proxy.newProxyInstance(
            GameService::class.java.classLoader, arrayOf(GameService::class.java),
        ) { _, method, _ -> error("Unexpected service call: ${method.name}") } as GameService
    }
}
