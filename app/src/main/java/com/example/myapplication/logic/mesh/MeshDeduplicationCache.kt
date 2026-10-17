package com.example.myapplication.logic.mesh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.*

class MeshDeduplicationCache(private val ttlMs: Long = 5 * 60 * 1000) {
    private val cache = LinkedHashMap<String, Long>()
    private val maxSize = 1000
    private val mutex = Mutex()

    suspend fun isSeen(recordId: String): Boolean = withContext(Dispatchers.Default) {
        mutex.withLock {
            val timestamp = cache[recordId] ?: return@withContext false
            val age = System.currentTimeMillis() - timestamp
            if (age > ttlMs) {
                cache.remove(recordId)
                return@withContext false
            }
            true
        }
    }

    suspend fun markSeen(recordId: String) = withContext(Dispatchers.Default) {
        mutex.withLock {
            cache[recordId] = System.currentTimeMillis()
            if (cache.size > maxSize) {
                val iterator = cache.iterator()
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
    }

    suspend fun clear() = withContext(Dispatchers.Default) {
        mutex.withLock {
            cache.clear()
        }
    }

    suspend fun cleanup() {
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val now = System.currentTimeMillis()
                val iterator = cache.iterator()
                while (iterator.hasNext()) {
                    val (_, timestamp) = iterator.next()
                    if (now - timestamp > ttlMs) {
                        iterator.remove()
                    }
                }
            }
        }
    }
}
