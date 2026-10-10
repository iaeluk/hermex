package com.iaeluk.hermex.data.repository

import com.iaeluk.hermex.data.db.CacheDao
import com.iaeluk.hermex.data.db.ServerCacheOwnership

class CacheMaintenanceRepository(
    private val cacheDao: CacheDao,
    private val cacheOwnership: ServerCacheOwnership,
) {
    suspend fun clearServer(serverUrl: String) {
        cacheOwnership.invalidateAndClear(serverUrl) {
            cacheDao.clearServer(serverUrl)
        }
    }

    suspend fun maintenance(now: Long = System.currentTimeMillis()) {
        cacheDao.maintenance(now)
    }
}
