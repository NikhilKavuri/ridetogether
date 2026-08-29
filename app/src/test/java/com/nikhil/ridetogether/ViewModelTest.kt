package com.nikhil.ridetogether

import com.nikhil.ridetogether.data.FakeRideRepository
import com.nikhil.ridetogether.data.RideNotFoundException
import com.nikhil.ridetogether.data.model.Destination
import com.nikhil.ridetogether.data.model.RiderLocation
import com.nikhil.ridetogether.ride.RideViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakePrefs : RidePrefs {
    override var displayName: String = ""
    override var lastRideCode: String? = null
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var repo: FakeRideRepository
    private lateinit var prefs: FakePrefs
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = FakeRideRepository()
        prefs = FakePrefs()
        viewModel = MainViewModel(repo, prefs)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `you cannot start a ride without a name`() {
        assertFalse(viewModel.ui.value.canCreate)
        viewModel.updateName("N")
        assertFalse(viewModel.ui.value.canCreate)
        viewModel.updateName("Nikhil")
        assertTrue(viewModel.ui.value.canCreate)
    }

    @Test
    fun `names are capped so they fit on a map marker`() {
        viewModel.updateName("A".repeat(40))
        assertEquals(16, viewModel.ui.value.name.length)
    }

    @Test
    fun `creating a ride moves to the ride screen and remembers the name`() {
        viewModel.updateName("Nikhil")
        viewModel.createRide()

        val screen = viewModel.ui.value.screen
        assertTrue(screen is AppScreen.InRide)
        assertEquals("Nikhil", prefs.displayName)
        assertEquals((screen as AppScreen.InRide).code, prefs.lastRideCode)
    }

    @Test
    fun `join is blocked until the code is a valid length`() {
        viewModel.updateName("Nikhil")
        viewModel.updateCode("ABC")
        assertFalse(viewModel.ui.value.canJoin)
        viewModel.updateCode("ABC234")
        assertTrue(viewModel.ui.value.canJoin)
    }

    @Test
    fun `code entry is normalised as you type`() {
        viewModel.updateCode("abc-234")
        assertEquals("ABC234", viewModel.ui.value.codeInput)
    }

    @Test
    fun `joining a ride that does not exist explains what to do`() {
        viewModel.updateName("Nikhil")
        viewModel.updateCode("ABC234")
        viewModel.joinRide()

        val error = viewModel.ui.value.error
        assertNotNull(error)
        assertTrue(error!!.contains("ABC234"))
        // And it must not have navigated anyway.
        assertTrue(viewModel.ui.value.screen is AppScreen.Home)
        assertFalse(viewModel.ui.value.busy)
    }

    @Test
    fun `a backend failure surfaces instead of hanging on the spinner`() {
        repo.failNextJoin = RideNotFoundException("ZZZZZZ")
        viewModel.updateName("Nikhil")
        viewModel.updateCode("ABC234")
        viewModel.joinRide()

        assertFalse(viewModel.ui.value.busy)
        assertNotNull(viewModel.ui.value.error)
    }

    @Test
    fun `an unreachable database times out instead of spinning forever`() {
        // The real failure this reproduces: Firebase queues the write and waits
        // for a server ack that never arrives, so the call never returns and
        // never throws. Before the timeout this left the button spinning with
        // no error, which reads as "the app is broken".
        repo.hangNextJoin = true
        viewModel.updateName("Nikhil")
        viewModel.updateCode("ABC234")
        viewModel.joinRide()

        assertTrue("should be busy while waiting", viewModel.ui.value.busy)

        dispatcher.scheduler.advanceTimeBy(21_000)
        dispatcher.scheduler.runCurrent()

        assertFalse("spinner must stop", viewModel.ui.value.busy)
        assertNotNull("must explain itself", viewModel.ui.value.error)
        assertTrue(viewModel.ui.value.error!!.contains("Realtime Database"))
    }

    @Test
    fun `a second rider can join the ride the host created`() {
        viewModel.updateName("Nikhil")
        viewModel.createRide()
        val code = (viewModel.ui.value.screen as AppScreen.InRide).code

        // Same backend, a different phone.
        val friend = MainViewModel(FakeRideRepository(uid = "friend-uid"), FakePrefs())
        friend.updateName("Arjun")
        friend.updateCode(code)
        assertTrue(friend.ui.value.canJoin)
    }

    @Test
    fun `leaving a ride clears the remembered code`() {
        viewModel.updateName("Nikhil")
        viewModel.createRide()
        viewModel.exitRide()

        assertTrue(viewModel.ui.value.screen is AppScreen.Home)
        assertNull(prefs.lastRideCode)
    }

    @Test
    fun `an invite link pre-fills the code box`() {
        viewModel.applyInviteLink("ridetogether://join?code=ABC234")
        assertEquals("ABC234", viewModel.ui.value.codeInput)
    }

    @Test
    fun `a junk invite link is ignored rather than half-filling the box`() {
        viewModel.updateCode("ABC234")
        viewModel.applyInviteLink("https://example.com/nope")
        assertEquals("ABC234", viewModel.ui.value.codeInput)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RideViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var repo: FakeRideRepository
    private lateinit var viewModel: RideViewModel
    private lateinit var code: String

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = FakeRideRepository()
        code = runBlocking { repo.createRide("Nikhil").code }
        viewModel = RideViewModel(repo, code)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /**
     * The ride state is a WhileSubscribed flow, so it stays at its initial
     * value until something collects it -- exactly as it would with no screen
     * on top. Tests that read state have to stand in for that screen.
     */
    private fun TestScope.observeState() {
        backgroundScope.launch(dispatcher) { viewModel.state.collect { } }
    }

    @Test
    fun `a friend asking for a break raises an alert`() = runTest {
        repo.simulateRider(code, "friend-uid", "Arjun")
        repo.simulateRiderBreak(code, "friend-uid", "Arjun", onBreak = true)

        val alert = viewModel.alert.value
        assertNotNull(alert)
        assertEquals("Arjun wants to take a break", alert!!.headline())
    }

    @Test
    fun `my own break does not alert me`() = runTest {
        observeState()
        viewModel.requestBreak()
        assertNull(viewModel.alert.value)
    }

    @Test
    fun `an alert can be dismissed`() = runTest {
        repo.simulateRider(code, "friend-uid", "Arjun")
        repo.simulateRiderBreak(code, "friend-uid", "Arjun", onBreak = true)
        assertNotNull(viewModel.alert.value)

        viewModel.dismissAlert()
        assertNull(viewModel.alert.value)
    }

    @Test
    fun `an event replayed from an hour ago does not alert`() = runTest {
        // The Firebase listener replays the last handful of events on reconnect.
        // A break request from before the app was opened must not buzz anyone.
        repo.simulateRider(code, "friend-uid", "Arjun")
        repo.simulateRiderBreak(
            code, "friend-uid", "Arjun",
            onBreak = true,
            at = repo.serverNow() - 60 * 60_000
        )

        assertNull(viewModel.alert.value)
    }

    @Test
    fun `a break ending also alerts, so the group knows to move`() = runTest {
        repo.simulateRider(code, "friend-uid", "Arjun")
        repo.simulateRiderBreak(code, "friend-uid", "Arjun", onBreak = false)

        assertEquals(
            "Arjun is ready to ride again",
            viewModel.alert.value?.headline()
        )
    }

    @Test
    fun `ending a break clears my break flag`() = runTest {
        observeState()
        viewModel.requestBreak()
        assertTrue(viewModel.amIOnBreak)

        viewModel.endBreak()
        assertFalse(viewModel.amIOnBreak)
    }

    @Test
    fun `the roster shows a friend and the distance to them`() = runTest {
        observeState()
        repo.simulateRider(code, "friend-uid", "Arjun")
        repo.publishLocation(code, RiderLocation("me-uid", 17.3850, 78.4867))
        repo.simulateRiderAt(code, "friend-uid", 17.3855, 78.4870)

        val riders = viewModel.state.value.riders
        assertEquals(2, riders.size)
        assertEquals(64.07, riders.first { !it.isMe }.distanceMeters!!, 0.1)
    }

    @Test
    fun `a rider whose phone drops off stops being drawn`() = runTest {
        observeState()
        repo.simulateRider(code, "friend-uid", "Arjun")
        repo.simulateRiderAt(code, "friend-uid", 17.3855, 78.4870)
        assertNotNull(viewModel.state.value.riders.first { !it.isMe }.location)

        repo.simulateRiderDisconnect(code, "friend-uid")
        assertNull(viewModel.state.value.riders.first { !it.isMe }.location)
    }

    @Test
    fun `setting a destination stores it on the ride`() = runTest {
        observeState()
        viewModel.setDestination(Destination("Lonavala", "Maharashtra", 18.7546, 73.4062))
        assertEquals("Lonavala", viewModel.state.value.ride?.destination?.name)
    }

    @Test
    fun `losing the connection is reflected in the state`() = runTest {
        observeState()
        assertTrue(viewModel.state.value.connected)
        repo.setConnected(false)
        assertFalse(viewModel.state.value.connected)
    }
}
