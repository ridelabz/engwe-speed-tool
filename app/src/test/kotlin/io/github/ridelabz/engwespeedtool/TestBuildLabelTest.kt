package io.github.ridelabz.engwespeedtool

import org.junit.Assert.assertEquals
import org.junit.Test

class TestBuildLabelTest {
    @Test
    fun releaseAndDebugBuildsShowNoBanner() {
        assertEquals(TestBuildLabel.None, TestBuildLabel.of(isTestBuild = false, pullRequest = 0))
        assertEquals(TestBuildLabel.None, TestBuildLabel.of(isTestBuild = false, pullRequest = 12))
    }

    @Test
    fun testBuildsAlwaysShowABanner() {
        assertEquals(TestBuildLabel.PullRequest(12), TestBuildLabel.of(isTestBuild = true, pullRequest = 12))
        assertEquals(TestBuildLabel.Local, TestBuildLabel.of(isTestBuild = true, pullRequest = 0))
    }

    @Test
    fun theDebugUnitTestBuildIsNotATestBuild() {
        assertEquals(TestBuildLabel.None, TestBuildLabel.current)
    }
}
