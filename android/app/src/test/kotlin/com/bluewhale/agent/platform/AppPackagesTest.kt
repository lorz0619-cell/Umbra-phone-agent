package com.bluewhale.agent.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPackagesTest {
    @Test
    fun detectsOnlyExplicitAppLaunchWording() {
        assertTrue(AppPackages.explicitlyRequestsCandidate("打开QQ应用", "QQ"))
        assertTrue(AppPackages.explicitlyRequestsCandidate("打开浏览器并搜索q", "浏览器"))
        assertFalse(AppPackages.explicitlyRequestsCandidate("QQ最近有什么更新", "QQ"))
    }
}
