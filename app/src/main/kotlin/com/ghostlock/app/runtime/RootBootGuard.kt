package com.ghostlock.app.runtime

/**
 * Pure check for the root script's persisted status file. The recorded boot id
 * is the only evidence that root was established in this boot; the state field
 * can lag the handoff and must not gate it.
 */
internal object RootBootGuard {
    fun matches(statusText: String?, currentBootId: String): Boolean {
        if (statusText.isNullOrBlank() || currentBootId.isBlank()) return false
        val recorded = statusText.lineSequence()
            .firstOrNull { it.startsWith("boot_id=") }
            ?.substringAfter('=')
            ?.trim()
        return recorded != null && recorded == currentBootId
    }
}
