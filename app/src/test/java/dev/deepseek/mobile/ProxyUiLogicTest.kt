package dev.deepseek.mobile

import org.junit.Assert.*
import org.junit.Test

class ProxyUiLogicTest {
    @Test
    fun trafficUsesElapsedTimeAndCoreCounterResetsDoNotCreateSpikes() {
        assertNull(proxyTrafficRate(null, ProxyTrafficSample(1000, 10, 20)))
        val first = ProxyTrafficSample(1000, 1000, 2000)
        assertEquals(
            ProxyTrafficRate(500f, 1000f),
            proxyTrafficRate(first, ProxyTrafficSample(3000, 2000, 4000)),
        )
        assertNull(proxyTrafficRate(first, ProxyTrafficSample(4000, 0, 0)))
        assertNull(proxyTrafficRate(first, first))
        assertNull(proxyTrafficRate(first, ProxyTrafficSample(500, 2000, 4000)))
    }

    @Test
    fun unavailableFilterKeepsUnmeasuredNodesAndDelaySortDoesNotPutTimeoutsFirst() {
        val names = listOf("B", "A", "待测", "超时")
        val delays = mapOf("B" to 200, "A" to 20, "超时" to -1)
        assertEquals(listOf("A", "B", "待测", "超时"), sortedProxyNodes(names, delays, "延迟", false))
        assertEquals(listOf("B", "A", "待测"), sortedProxyNodes(names, delays, "默认", true))
        assertEquals(names, sortedProxyNodes(names, emptyMap(), "默认", true))
    }
}
