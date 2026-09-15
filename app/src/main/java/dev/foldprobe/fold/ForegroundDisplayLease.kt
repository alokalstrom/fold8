package dev.foldprobe.fold

/** A main-thread heartbeat may extend a live lease, but cannot resurrect an expired one. */
class ForegroundDisplayLease {
    @Volatile private var expiresAt = 0L
    fun begin(now: Long) { expiresAt = now + 3_000 }
    fun renew(now: Long) { if (valid(now)) expiresAt = now + 3_000 }
    fun valid(now: Long) = expiresAt != 0L && now < expiresAt
    fun clear() { expiresAt = 0L }
}
