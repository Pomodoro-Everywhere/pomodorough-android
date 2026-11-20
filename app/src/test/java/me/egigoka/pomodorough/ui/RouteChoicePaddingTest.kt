package me.egigoka.pomodorough.ui

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.data.iroh.ReplicationMode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * AND-27: at large text the route rows grew past their minimum height and
 * their text touched the top and bottom edges. The rows now keep a top and
 * bottom inset that grows with the text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RouteChoicePaddingTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun largeTextKeepsRouteTextOffTheRowEdges() = assertInsets(fontScale = 1.3f)

    @Test
    fun largestTextKeepsRouteTextOffTheRowEdges() = assertInsets(fontScale = 2f)

    @Test
    fun defaultTextKeepsRouteTextOffTheRowEdges() = assertInsets(fontScale = 1f)

    private fun assertInsets(fontScale: Float) {
        compose.setContent {
            AtFontScale(fontScale) {
                PomodoroughTheme(darkTheme = false) {
                    RouteSwitch(ReplicationMode.OFFLINE, true, CloudAccess.SignInOffered, true, {}, {})
                }
            }
        }
        val title = string(R.string.on_device)
        val detail = string(R.string.no_remote_endpoint)
        val row = bounds(title, merged = true)
        // The title is 14 sp label text; the inset is three quarters of it.
        val inset = with(compose.density) { (14.sp * fontScale * 0.75f).toPx() } - 1f
        val top = bounds(title, merged = false).top - row.top
        val bottom = row.bottom - bounds(detail, merged = false).bottom
        assertTrue("top inset $top px < $inset px at $fontScale", top >= inset)
        assertTrue("bottom inset $bottom px < $inset px at $fontScale", bottom >= inset)
    }

    private fun bounds(text: String, merged: Boolean): Rect =
        compose.onNode(hasText(text), useUnmergedTree = !merged).fetchSemanticsNode().boundsInRoot

    private fun string(id: Int): String = ApplicationProvider.getApplicationContext<Application>().getString(id)
}
