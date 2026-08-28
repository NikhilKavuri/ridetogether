package com.nikhil.ridetogether

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nikhil.ridetogether.ui.HomeScreen
import com.nikhil.ridetogether.ui.HomeTags
import com.nikhil.ridetogether.ui.theme.RideTogetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device tests for the home screen.
 *
 * These drive the composable directly with a state object rather than
 * launching MainActivity, so they need neither a Firebase project nor a Maps
 * key and cannot fail for reasons unrelated to the UI.
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(
        state: HomeUiState = HomeUiState(),
        onNameChange: (String) -> Unit = {},
        onCodeChange: (String) -> Unit = {},
        onCreate: () -> Unit = {},
        onJoin: () -> Unit = {}
    ) {
        compose.setContent {
            RideTogetherTheme {
                HomeScreen(
                    state = state,
                    onNameChange = onNameChange,
                    onCodeChange = onCodeChange,
                    onCreate = onCreate,
                    onJoin = onJoin,
                    onDismissError = {}
                )
            }
        }
    }

    @Test
    fun bothActionsAreDisabledUntilANameIsEntered() {
        show(HomeUiState(name = ""))

        compose.onNodeWithTag(HomeTags.CREATE).assertIsNotEnabled()
        compose.onNodeWithTag(HomeTags.JOIN).assertIsNotEnabled()
        compose.onNodeWithText("Enter a name to get started").assertExists()
    }

    @Test
    fun aNameEnablesStartingARideButNotJoiningOne() {
        show(HomeUiState(name = "Nikhil"))

        compose.onNodeWithTag(HomeTags.CREATE).assertIsEnabled()
        // No code typed yet, so there is nothing to join.
        compose.onNodeWithTag(HomeTags.JOIN).assertIsNotEnabled()
    }

    @Test
    fun aNameAndAFullCodeEnableJoining() {
        show(HomeUiState(name = "Nikhil", codeInput = "ABC234"))
        compose.onNodeWithTag(HomeTags.JOIN).assertIsEnabled()
    }

    @Test
    fun typingAName_reachesTheCallback() {
        val typed = StringBuilder()
        show(onNameChange = { typed.append(it) })

        compose.onNodeWithTag(HomeTags.NAME).performTextInput("Nikhil")
        compose.waitForIdle()

        assertTrue("nothing was typed", typed.isNotEmpty())
    }

    @Test
    fun tappingStartFiresExactlyOnce() {
        var taps = 0
        show(HomeUiState(name = "Nikhil"), onCreate = { taps++ })

        compose.onNodeWithTag(HomeTags.CREATE).performClick()
        compose.waitForIdle()

        assertEquals(1, taps)
    }

    @Test
    fun aDisabledButtonDoesNotFire() {
        var taps = 0
        show(HomeUiState(name = ""), onCreate = { taps++ })

        compose.onNodeWithTag(HomeTags.CREATE).performClick()
        compose.waitForIdle()

        assertEquals(0, taps)
    }

    @Test
    fun anErrorIsShownToTheRider() {
        show(HomeUiState(name = "Nikhil", error = "No active ride with code ABC234."))

        compose.onNodeWithTag(HomeTags.ERROR).assertExists()
        compose.onNodeWithText("No active ride with code ABC234.").assertExists()
    }

    @Test
    fun theBusyStateReplacesTheButtonLabelWithASpinner() {
        show(HomeUiState(name = "Nikhil", busy = true))

        // Label gone means the progress indicator took its place, and the
        // button is disabled so a second tap cannot create a second ride.
        compose.onNodeWithText("Start a ride").assertDoesNotExist()
        compose.onNodeWithTag(HomeTags.CREATE).assertIsNotEnabled()
    }
}
