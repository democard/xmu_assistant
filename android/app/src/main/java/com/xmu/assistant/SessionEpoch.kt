package com.xmu.assistant

data class SessionRequest(
    val ownerId: Long,
    val generation: Long,
    val cookieHeader: String,
)

data class SessionOwner internal constructor(val id: Long)

class LoginAttempt internal constructor(
    internal val ownerId: Long,
    internal val generation: Long,
    internal val username: String,
    internal val password: String,
)

class SessionEpoch {
    private val lock = Any()
    private var generation = 0L
    private var nextOwnerId = 0L
    private val registeredOwnerIds = mutableSetOf<Long>()
    private var activeOwnerId: Long? = null

    fun attachOwner(): SessionOwner = synchronized(lock) {
        val owner = SessionOwner(++nextOwnerId)
        registeredOwnerIds += owner.id
        activeOwnerId = owner.id
        generation += 1
        owner
    }

    fun activateOwner(owner: SessionOwner): Boolean = synchronized(lock) {
        if (owner.id !in registeredOwnerIds) return@synchronized false
        if (activeOwnerId == owner.id) return@synchronized true
        activeOwnerId = owner.id
        generation += 1
        true
    }

    fun detachOwner(owner: SessionOwner) {
        synchronized(lock) {
            if (!registeredOwnerIds.remove(owner.id)) return@synchronized
            if (activeOwnerId == owner.id) {
                activeOwnerId = null
                generation += 1
            }
        }
    }

    fun isOwnerActive(owner: SessionOwner): Boolean = synchronized(lock) {
        activeOwnerId == owner.id
    }

    fun beginLoginAttempt(owner: SessionOwner, username: String, password: String): LoginAttempt? = synchronized(lock) {
        if (activeOwnerId != owner.id) return@synchronized null
        generation += 1
        LoginAttempt(owner.id, generation, username, password)
    }

    fun acceptsLoginAttempt(attempt: LoginAttempt, username: String, password: String): Boolean = synchronized(lock) {
        acceptsLoginAttemptLocked(attempt, username, password)
    }

    fun completeLogin(attempt: LoginAttempt, username: String, password: String): Boolean = synchronized(lock) {
        if (!acceptsLoginAttemptLocked(attempt, username, password)) return@synchronized false
        generation += 1
        true
    }

    fun invalidate(owner: SessionOwner): Boolean = synchronized(lock) {
        if (activeOwnerId != owner.id) return@synchronized false
        generation += 1
        true
    }

    fun snapshot(owner: SessionOwner, cookieHeader: String): SessionRequest = synchronized(lock) {
        SessionRequest(owner.id, generation, cookieHeader)
    }

    fun accepts(request: SessionRequest, cookieHeader: String, loggedIn: Boolean): Boolean =
        synchronized(lock) {
            loggedIn &&
                cookieHeader.isNotBlank() &&
                request.ownerId == activeOwnerId &&
                request.generation == generation &&
                request.cookieHeader == cookieHeader
        }

    /**
     * 请求发起时的快照是否仍是当前世代（仅按 epoch 世代判断，不读取 UI 状态）。
     * 用户登出 / 重新登录 / 新活动接管都会推进世代，因此世代不变即说明
     * 请求发起后没有发生任何会话切换，允许继续自动续登。
     */
    fun isCurrent(request: SessionRequest): Boolean = synchronized(lock) {
        request.ownerId == activeOwnerId && request.generation == generation
    }

    private fun acceptsLoginAttemptLocked(attempt: LoginAttempt, username: String, password: String): Boolean =
        attempt.ownerId == activeOwnerId &&
            attempt.generation == generation &&
            attempt.username == username &&
            attempt.password == password
}

internal object ProcessSessionEpoch {
    val instance = SessionEpoch()
}
