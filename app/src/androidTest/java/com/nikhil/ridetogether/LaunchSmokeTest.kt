package com.nikhil.ridetogether

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one test that has to pass before any other result means anything: the
 * real APK, the real Application class, Firebase and Places initialisation, the
 * real Activity. If this goes red, the build boots to a crash on a phone.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class LaunchSmokeTest {

    @Test
    fun theApplicationClassInitialisesWithoutThrowing() {
        val app = ApplicationProvider.getApplicationContext<RideApp>()
        assertNotNull(app)
        // ServiceLocator.init ran in onCreate; prefs must be usable.
        assertNotNull(ServiceLocator.prefs)
    }

    @Test
    fun theActivityReachesResumedWithoutCrashing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> assertNotNull(activity) }
            assertEquals(
                androidx.lifecycle.Lifecycle.State.RESUMED,
                scenario.state
            )
        }
    }

    @Test
    fun aBuildWithoutKeysReportsItRatherThanFailingSilently() {
        val problems = SetupGate.check(ApplicationProvider.getApplicationContext())
        // On CI without secrets this lists both problems; with secrets, none.
        // Either is fine -- what matters is that it never throws, because it
        // runs before the first frame of the app.
        assertNotNull(problems)
    }
}
