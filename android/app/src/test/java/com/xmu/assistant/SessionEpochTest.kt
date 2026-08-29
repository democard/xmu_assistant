package com.xmu.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEpochTest {
    @Test
    fun `new owner rejects old activity completion even when old owner disposes last`() {
        val epoch = SessionEpoch()
        val oldOwner = epoch.attachOwner()
        val oldRequest = epoch.snapshot(oldOwner, "same-cookie")

        val newOwner = epoch.attachOwner()
        val currentRequest = epoch.snapshot(newOwner, "same-cookie")
        epoch.detachOwner(oldOwner)

        assertFalse(epoch.accepts(oldRequest, cookieHeader = "same-cookie", loggedIn = true))
        assertTrue(epoch.accepts(currentRequest, cookieHeader = "same-cookie", loggedIn = true))
        assertTrue(epoch.isOwnerActive(newOwner))
    }

    @Test
    fun `same cookie after logout and relogin rejects the old request`() {
        val epoch = SessionEpoch()
        val owner = epoch.attachOwner()
        val firstLogin = requireNotNull(epoch.beginLoginAttempt(owner, "student", "first-password"))
        assertTrue(epoch.completeLogin(firstLogin, "student", "first-password"))
        val oldRequest = epoch.snapshot(owner, "same-cookie")

        epoch.invalidate(owner)
        val secondLogin = requireNotNull(epoch.beginLoginAttempt(owner, "student", "second-password"))
        assertTrue(epoch.completeLogin(secondLogin, "student", "second-password"))
        val currentRequest = epoch.snapshot(owner, "same-cookie")

        assertFalse(epoch.accepts(oldRequest, cookieHeader = "same-cookie", loggedIn = true))
        assertTrue(epoch.accepts(currentRequest, cookieHeader = "same-cookie", loggedIn = true))
    }

    @Test
    fun `edited credentials reject the captured login result`() {
        val epoch = SessionEpoch()
        val owner = epoch.attachOwner()
        val oldAttempt = requireNotNull(epoch.beginLoginAttempt(owner, "student-a", "password-a"))

        assertFalse(epoch.acceptsLoginAttempt(oldAttempt, "student-b", "password-b"))
        assertFalse(epoch.completeLogin(oldAttempt, "student-b", "password-b"))

        val currentAttempt = requireNotNull(epoch.beginLoginAttempt(owner, "student-b", "password-b"))
        assertFalse(epoch.acceptsLoginAttempt(oldAttempt, "student-a", "password-a"))
        assertTrue(epoch.completeLogin(currentAttempt, "student-b", "password-b"))
    }

    @Test
    fun `inactive session changed cookie and detached owner reject completion`() {
        val epoch = SessionEpoch()
        val owner = epoch.attachOwner()
        val request = epoch.snapshot(owner, "cookie-a")

        assertFalse(epoch.accepts(request, cookieHeader = "", loggedIn = false))
        assertFalse(epoch.accepts(request, cookieHeader = "cookie-b", loggedIn = true))
        assertTrue(epoch.accepts(request, cookieHeader = "cookie-a", loggedIn = true))

        epoch.detachOwner(owner)
        assertFalse(epoch.accepts(request, cookieHeader = "cookie-a", loggedIn = true))
    }

    @Test
    fun `registered owner can reactivate after a newer owner and invalidates newer requests`() {
        val epoch = SessionEpoch()
        val oldOwner = epoch.attachOwner()
        val oldRequest = epoch.snapshot(oldOwner, "cookie-a")
        val newerOwner = epoch.attachOwner()
        val newerRequest = epoch.snapshot(newerOwner, "cookie-b")

        assertFalse(epoch.accepts(oldRequest, cookieHeader = "cookie-a", loggedIn = true))
        assertTrue(epoch.activateOwner(oldOwner))
        val resumedRequest = epoch.snapshot(oldOwner, "cookie-a")

        assertFalse(epoch.accepts(newerRequest, cookieHeader = "cookie-b", loggedIn = true))
        assertTrue(epoch.accepts(resumedRequest, cookieHeader = "cookie-a", loggedIn = true))
        epoch.detachOwner(newerOwner)
        assertTrue(epoch.accepts(resumedRequest, cookieHeader = "cookie-a", loggedIn = true))
    }

    @Test
    fun `same owner activation and ordinary backgrounding preserve in flight request`() {
        val epoch = SessionEpoch()
        val owner = epoch.attachOwner()
        val request = epoch.snapshot(owner, "cookie")

        assertTrue(epoch.activateOwner(owner))

        assertTrue(epoch.accepts(request, cookieHeader = "cookie", loggedIn = true))
    }

    @Test
    fun `detached owner cannot reactivate`() {
        val epoch = SessionEpoch()
        val owner = epoch.attachOwner()
        epoch.detachOwner(owner)

        assertFalse(epoch.activateOwner(owner))
        assertFalse(epoch.isOwnerActive(owner))
    }

    @Test
    fun `is current follows the epoch generation without reading ui state`() {
        val epoch = SessionEpoch()
        val owner = epoch.attachOwner()
        val request = epoch.snapshot(owner, "cookie")

        // 世代未变：请求仍是当前世代
        assertTrue(epoch.isCurrent(request))

        // 登出推进世代后，旧请求不再是当前世代
        epoch.invalidate(owner)
        assertFalse(epoch.isCurrent(request))

        // 重新登录再推进世代，新快照才是当前世代
        val login = requireNotNull(epoch.beginLoginAttempt(owner, "student", "password"))
        assertTrue(epoch.completeLogin(login, "student", "password"))
        val freshRequest = epoch.snapshot(owner, "cookie")
        assertTrue(epoch.isCurrent(freshRequest))
        assertFalse(epoch.isCurrent(request))
    }

    @Test
    fun `is current stays true while the same owner is merely backgrounded`() {
        val epoch = SessionEpoch()
        val owner = epoch.attachOwner()
        val request = epoch.snapshot(owner, "cookie")

        assertTrue(epoch.activateOwner(owner))
        assertTrue(epoch.isCurrent(request))
    }
}
