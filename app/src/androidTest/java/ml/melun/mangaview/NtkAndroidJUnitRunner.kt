package ml.melun.mangaview

import androidx.test.runner.AndroidJUnitRunner

/**
 * Current-architecture tests own an ActivityScenario per fixture.  No class may retain a shared
 * Activity or alter runner-wide cleanup, so one failure cannot contaminate later tests.
 */
class NtkAndroidJUnitRunner : AndroidJUnitRunner()
