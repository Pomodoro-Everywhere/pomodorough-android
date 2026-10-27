package me.egigoka.pomodorough.ui

import android.app.Application
import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.data.AppState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class R53A02NetworkRoomContentHeightsTest {
    @get:Rule val compose = createComposeRule()

    // Pairwise coverage of width, scale, resource locale, and layout direction.
    @Test fun english240Scale1Ltr() = assertRoomActions(240, 1f, LayoutDirection.Ltr)
    @Test fun english320Scale1Rtl() = assertRoomActions(320, 1f, LayoutDirection.Rtl)
    @Test fun english240Scale2Rtl() = assertRoomActions(240, 2f, LayoutDirection.Rtl)
    @Test fun english320Scale2Ltr() = assertRoomActions(320, 2f, LayoutDirection.Ltr)

    @Test @Config(qualifiers = "ar-rXB")
    fun pseudo240Scale1Rtl() = assertRoomActions(240, 1f, LayoutDirection.Rtl)
    @Test @Config(qualifiers = "ar-rXB")
    fun pseudo320Scale1Ltr() = assertRoomActions(320, 1f, LayoutDirection.Ltr)
    @Test @Config(qualifiers = "ar-rXB")
    fun pseudo240Scale2Ltr() = assertRoomActions(240, 2f, LayoutDirection.Ltr)
    @Test @Config(qualifiers = "ar-rXB")
    fun pseudo320Scale2Rtl() = assertRoomActions(320, 2f, LayoutDirection.Rtl)

    private fun assertRoomActions(width: Int, scale: Float, direction: LayoutDirection) {
        val created = mutableListOf<String>()
        val joined = mutableListOf<String>()
        renderNetwork(width, scale, direction, created::add, joined::add)
        listOf(R.string.join_room, R.string.create_iroh_room).forEach { id ->
            val text = roomActionLabel(id)
            val control = compose.onNodeWithText(text).performScrollTo()
            control.assertIsDisplayed().assertIsEnabled().assertHasClickAction()
            assertGlyphFit(text)
            assertTrue("$text minimum touch height", control.fetchSemanticsNode().size.height >= 48)
            control.performClick()
        }
        assertEquals(listOf("Study"), created)
        assertEquals(listOf("pomodorough1-test-invite"), joined)
    }

    private fun roomActionLabel(id: Int): String {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val text = context.getString(id)
        if (context.resources.configuration.locales[0].toLanguageTag() == "ar-XB") {
            val english = if (id == R.string.join_room) "JOIN ROOM" else "CREATE IROH ROOM"
            assertFalse("ar-XB must load pseudo-localized resources", text == english)
        }
        return text
    }

    private fun assertGlyphFit(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val control = compose.onNodeWithText(text).fetchSemanticsNode()
        val label = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        assertFalse("$text truncated", layout.multiParagraph.didExceedMaxLines)
        // Bidi pseudo-locales can overhang horizontally; pin complete line height, not hasVisualOverflow.
        assertTrue("$text paragraph=${layout.multiParagraph.height}, box=${layout.size}",
            layout.multiParagraph.height <= layout.size.height + 0.5f)
        for (line in 0 until layout.lineCount) {
            assertFalse("$text line $line ellipsized", layout.isLineEllipsized(line))
            assertTrue("$text line $line bottom", layout.getLineBottom(line) <= layout.size.height + 0.5f)
        }
        val labelBounds = label.boundsInRoot
        val controlBounds = control.boundsInRoot
        assertTrue("$text top inside button", labelBounds.top >= controlBounds.top - 0.5f)
        assertTrue("$text bottom inside button", labelBounds.bottom <= controlBounds.bottom + 0.5f)
        assertTrue("$text left inside button", labelBounds.left >= controlBounds.left - 0.5f)
        assertTrue("$text right inside button", labelBounds.right <= controlBounds.right + 0.5f)
    }

    private fun renderNetwork(
        width: Int,
        scale: Float,
        direction: LayoutDirection,
        onCreate: (String) -> Unit,
        onJoin: (String) -> Unit,
    ) {
        val draft = RoomNameDraftState().apply { onChange("Study") }
        compose.setContent {
            val config = Configuration(LocalConfiguration.current).apply { fontScale = scale }
            CompositionLocalProvider(
                LocalConfiguration provides config,
                LocalDensity provides Density(1f, scale),
                LocalLayoutDirection provides direction,
            ) {
                MaterialTheme {
                    Column(Modifier.width(width.dp).verticalScroll(rememberScrollState())) {
                        NetworkSection(
                            AppState(), true,
                            NetworkActions({}, onCreate, onJoin, {}, {}, {}, {}, {}),
                            draft, "pomodorough1-test-invite", {},
                        )
                    }
                }
            }
        }
    }
}
