package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.repository.http.AuthInterceptor
import cn.vectory.ocdroid.data.repository.http.CacheControlInterceptor
import cn.vectory.ocdroid.data.repository.http.CachePathSanitizer
import cn.vectory.ocdroid.data.repository.http.DirectoryHeaderInterceptor
import cn.vectory.ocdroid.data.repository.http.OkHttpClientFactory
import cn.vectory.ocdroid.data.repository.http.ResponseSizeGuardInterceptor
import cn.vectory.ocdroid.data.repository.http.SlimapiDebugInterceptor
import cn.vectory.ocdroid.data.repository.http.SlimapiVersionInterceptor
import cn.vectory.ocdroid.data.repository.http.SslConfigFactory
import cn.vectory.ocdroid.data.repository.http.TrafficCountingInterceptor
import cn.vectory.ocdroid.di.AppModule
import cn.vectory.ocdroid.di.ControllerModule
import cn.vectory.ocdroid.di.TofuModule
import cn.vectory.ocdroid.util.TrafficLogger
import cn.vectory.ocdroid.util.TrafficTracker
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.inject.Inject
import javax.inject.Singleton

/**
 * T2A.1-C1: executable ownership/DI guards for the repository network graph.
 *
 * This deliberately does not grep source text. Hilt's two independent
 * construction routes are checked at runtime instead:
 *  - graph-internal leaves have neither an injectable constructor nor a
 *    singleton scope annotation;
 *  - the application modules expose no provider method returning one of those
 *    leaves; and
 *  - the repository owns the one graph instance used to compose them.
 *
 * Direct constructors remain usable by focused unit tests, but there is no
 * alternate Hilt construction path that can silently create a second SSL or
 * host-interceptor graph.
 */
class RepositoryNetworkGraphTest {

    private val graphLeaves = listOf(
        SslConfigFactory::class.java,
        OkHttpClientFactory::class.java,
        DirectoryHeaderInterceptor::class.java,
        SlimapiVersionInterceptor::class.java,
        SlimapiDebugInterceptor::class.java,
        AuthInterceptor::class.java,
        CacheControlInterceptor::class.java,
        CachePathSanitizer::class.java,
        TrafficCountingInterceptor::class.java,
        ResponseSizeGuardInterceptor::class.java,
    )

    private val applicationModules = listOf(
        AppModule::class.java,
        ControllerModule::class.java,
        TofuModule::class.java,
    )

    @Test
    fun `graph internal leaves cannot be independently constructed by Hilt`() {
        graphLeaves.forEach { leaf ->
            assertFalse(
                "${leaf.name} must not have a Hilt injectable constructor",
                leaf.declaredConstructors.any { it.isAnnotationPresent(Inject::class.java) },
            )
            assertFalse(
                "${leaf.name} must not be an independently scoped Hilt singleton",
                leaf.isAnnotationPresent(Singleton::class.java),
            )
        }

        // A provider method is another Hilt construction route even when the
        // return type itself has no @Inject constructor. Inspect compiled
        // module methods instead of relying on source-grep assertions.
        val providedGraphLeaves = applicationModules
            .flatMap { it.declaredMethods.asList() }
            .map { it.returnType }
            .filter { it in graphLeaves }

        assertTrue(
            "no application Hilt module may provide graph-internal leaves; " +
                "found $providedGraphLeaves",
            providedGraphLeaves.isEmpty(),
        )
    }

    @Test
    fun `repository owns the single graph composition boundary`() {
        val repository = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )
        val graphField = OpenCodeRepository::class.java.getDeclaredField("networkGraph")
        graphField.isAccessible = true
        val graph = graphField.get(repository)

        assertEquals(
            "OpenCodeRepository must own RepositoryNetworkGraph rather than receive a leaf",
            RepositoryNetworkGraph::class.java,
            graphField.type,
        )
        assertTrue("repository graph instance must be present", graph is RepositoryNetworkGraph)
        assertFalse(
            "RepositoryNetworkGraph itself must not become a Hilt binding",
            RepositoryNetworkGraph::class.java.declaredConstructors
                .any { it.isAnnotationPresent(Inject::class.java) },
        )
    }

    @Test
    fun `host dependent leaves expose snapshot construction path`() {
        assertTrue(
            "AuthInterceptor must be constructed from immutable HostSnapshot",
            AuthInterceptor::class.java.declaredConstructors.any {
                it.parameterTypes.contentEquals(arrayOf(HostSnapshot::class.java))
            },
        )
        assertTrue(
            "CacheControlInterceptor must be constructed from immutable HostSnapshot",
            CacheControlInterceptor::class.java.declaredConstructors.any {
                it.parameterTypes.contentEquals(
                    arrayOf(HostSnapshot::class.java, CachePathSanitizer::class.java),
                )
            },
        )
        assertTrue(
            "SlimapiVersionInterceptor must be constructed from immutable HostSnapshot",
            SlimapiVersionInterceptor::class.java.declaredConstructors.any {
                it.parameterTypes.contentEquals(arrayOf(HostSnapshot::class.java))
            },
        )
    }
}
