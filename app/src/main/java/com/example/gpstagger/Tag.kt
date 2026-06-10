package com.example.gpstagger

/**
 * updatedAt is the epoch-millis of the last deliberate edit, used by the
 * server's last-write-wins sync merge. 0 means "never deliberately edited"
 * (e.g. a freshly-installed default), which always loses against any real
 * edit from another device.
 */
data class Tag(val id: String, val name: String, val updatedAt: Long = 0L)
