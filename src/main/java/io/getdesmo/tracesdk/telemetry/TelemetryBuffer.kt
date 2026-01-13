package io.getdesmo.tracesdk.telemetry

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thread-safe buffer for telemetry samples.
 *
 * Stores samples until they're drained for upload. Automatically drops
 * oldest samples if buffer exceeds [maxSize].
 */
internal class TelemetryBuffer(private val maxSize: Int = 10_000) {

    private val mutex = Mutex()
    private val samples: MutableList<TelemetrySample> = mutableListOf()

    /** Add a sample to the buffer. Drops oldest if over capacity. */
    suspend fun add(sample: TelemetrySample) {
        mutex.withLock {
            samples.add(sample)
            if (samples.size > maxSize) {
                val overflow = samples.size - maxSize
                repeat(overflow) { samples.removeFirst() }
            }
        }
    }

    /** Drains all samples from the buffer and returns them. */
    suspend fun drain(): List<TelemetrySample> {
        return mutex.withLock {
            if (samples.isEmpty()) {
                emptyList()
            } else {
                val snapshot = samples.toList()
                samples.clear()
                snapshot
            }
        }
    }

    /** Returns true if buffer has samples. */
    suspend fun isNotEmpty(): Boolean {
        return mutex.withLock { samples.isNotEmpty() }
    }

    /**
     * Clear all samples from the buffer without returning them.
     *
     * Used to discard stale samples from crashed/killed sessions when starting
     * a new session. This prevents old telemetry from leaking into new sessions.
     */
    suspend fun clear() {
        mutex.withLock { samples.clear() }
    }
}
