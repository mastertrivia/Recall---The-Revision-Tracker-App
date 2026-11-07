package com.goaltracker.shell

/**
 * Shared monitor for the wrapper's JSON data directory.
 *
 * `saveData()` writes one file per key, while an export reads every key to build a
 * snapshot. Without a common lock an export could capture the primary state from
 * before a save and the shadow copy from after it. Every read/write of the data
 * directory takes this monitor, which is narrow enough not to affect anything else.
 */
object DataStoreLock {
    val lock: Any = Any()
}
