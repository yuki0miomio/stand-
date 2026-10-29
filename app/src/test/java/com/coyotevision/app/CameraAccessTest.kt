package com.coyotevision.app

import org.junit.Assert.*
import org.junit.Test

class CameraAccessTest {
    @Test fun openingAndReturningWithoutATapCannotStartEvenWhenPermissionExists() {
        val access = CameraAccess()
        assertFalse(access.canStart)
        repeat(3) {
            access.enterForeground()
            assertFalse(access.requested)
            assertFalse(access.canStart)
            access.permissionResult(true) // Permission alone must never create intent.
            assertFalse(access.canStart)
            access.leaveForeground()
        }
    }

    @Test fun explicitTapWithExistingPermissionStartsOnlyInForeground() {
        val access = CameraAccess()
        access.request(true)
        assertFalse(access.canStart)
        access.enterForeground()
        access.request(true)
        assertTrue(access.canStart)
        assertTrue(access.accepts(access.generation))
    }

    @Test fun permissionPromptCanPauseAndResumeWithoutLosingExplicitIntent() {
        val access = CameraAccess()
        access.enterForeground()
        access.request(false)
        assertTrue(access.permissionPending)
        assertFalse(access.canStart)
        access.leaveForeground()
        assertTrue(access.requested)
        access.enterForeground()
        assertFalse(access.canStart)
        access.permissionResult(true)
        assertTrue(access.canStart)
    }

    @Test fun permissionResultWhilePausedCannotBindUntilForegroundReturns() {
        val access = CameraAccess()
        access.enterForeground(); access.request(false); access.leaveForeground()
        access.permissionResult(true)
        assertFalse(access.canStart)
        access.enterForeground()
        assertTrue(access.canStart)
    }

    @Test fun denialRequiresANewTapAndDoesNotAutoRetry() {
        val access = CameraAccess()
        access.enterForeground(); access.request(false)
        access.permissionResult(false)
        assertFalse(access.requested)
        assertFalse(access.permissionPending)
        assertFalse(access.canStart)
        access.enterForeground()
        access.permissionResult(true)
        assertFalse(access.canStart)
        access.request(true)
        assertTrue(access.canStart)
    }

    @Test fun backgroundRevokesCameraIntentAndInvalidatesLateInitializationAndFrames() {
        val access = CameraAccess()
        access.enterForeground(); access.request(true)
        val ticket = access.generation
        assertTrue(access.accepts(ticket))
        access.leaveForeground()
        assertFalse(access.accepts(ticket))
        access.enterForeground()
        assertFalse(access.canStart)
        assertFalse(access.accepts(ticket))
        access.request(true)
        assertFalse(access.accepts(ticket))
        assertTrue(access.accepts(access.generation))
    }

    @Test fun closingDuringStartupPreventsStaleCallbacksFromOpeningAgain() {
        val access = CameraAccess()
        access.enterForeground(); access.request(true)
        val old = access.generation
        access.close()
        assertFalse(access.accepts(old))
        access.request(true)
        assertTrue(access.canStart)
        assertFalse(access.accepts(old))
    }

    @Test fun canceledPermissionOrRecreatedScreenCannotBeOpenedByAnOldResult() {
        val access = CameraAccess()
        access.enterForeground(); access.request(false); access.close()
        access.permissionResult(true)
        assertFalse(access.canStart)
        val recreated = CameraAccess()
        recreated.enterForeground(); recreated.permissionResult(true)
        assertFalse(recreated.canStart)
    }

    @Test fun repeatedTapDuringPermissionPromptDoesNotQueueAnotherRequest() {
        val access = CameraAccess()
        access.enterForeground(); access.request(false)
        val generation = access.generation
        access.request(false)
        assertEquals(generation, access.generation)
        assertTrue(access.permissionPending)
    }
}
