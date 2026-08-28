package me.egigoka.pomodorough.core

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedCoreAssetCacheTest {
    @Test
    fun assetLoadsReuseProcessWideThreadSafeAdapter() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        assertSame(
            SharedCore.fromAssets(context.assets),
            SharedCore.fromAssets(context.assets),
        )
    }
}
