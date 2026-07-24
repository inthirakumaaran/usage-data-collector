/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.usage.data.collector.identity.metering.mau;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.usage.data.collector.identity.metering.common.UsageTrackingException;
import org.wso2.carbon.usage.data.collector.identity.metering.common.config.UsageTrackingConfig;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Scheduled task that flushes the in-memory MAU cache to {@code IDN_MAU_COUNT} and, once
 * per month at month-end, purges rows that have aged out of the retention window.
 * <p>
 * Runs on every node so per-user login rows from all nodes accumulate in the shared table.
 * The distinct-user count is computed and published by the usage-count collector (only on
 * the cluster coordinator) to avoid over-counting across nodes.
 */
public class MAUFlushTask implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(MAUFlushTask.class);

    private final MAUDataAccessObject dao;
    private final AtomicReference<String> lastPurgedMonth = new AtomicReference<>("");

    public MAUFlushTask(MAUDataAccessObject dao) {
        this.dao = dao;
    }

    @Override
    public void run() {
        if (LOG.isDebugEnabled()) {
            LOG.debug("[MAU-Flush] Starting flush cycle.");
        }
        try {
            flushAll();
            MAUCacheManager.getInstance()
                    .purgeExpiredEntries(UsageTrackingConfig.getMauCacheRetentionDays());

            if (isMonthEndFlushTime() && !hasPurgedThisMonth()) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("[MAU-Flush] Month-end detected. Purging aged rows from IDN_MAU_COUNT.");
                }
                try {
                    dao.purgeOldEntries(UsageTrackingConfig.getMauDbRetentionDays());
                    lastPurgedMonth.set(MAUCacheManager.currentMonthYear());
                } catch (UsageTrackingException e) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("[MAU-Flush] Purge of old DB entries failed.", e);
                    }
                }
            }
        } catch (Exception e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("[MAU-Flush] Unexpected error during flush cycle.", e);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void flushAll() {
        Map<String, Map<String, Long>> byMonth =
                MAUCacheManager.getInstance().snapshotGroupedByMonth();
        if (byMonth.isEmpty()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("[MAU-Flush] Cache empty; nothing to flush.");
            }
            return;
        }
        for (Map.Entry<String, Map<String, Long>> entry : byMonth.entrySet()) {
            try {
                dao.batchUpsertLogins(entry.getValue(), entry.getKey());
            } catch (UsageTrackingException e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("[MAU-Flush] Flush failed for month {}.", entry.getKey(), e);
                }
            }
        }
    }

    private static boolean isMonthEndFlushTime() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return now.getDayOfMonth() == now.toLocalDate().lengthOfMonth() && now.getHour() == 23;
    }

    private boolean hasPurgedThisMonth() {
        return MAUCacheManager.currentMonthYear().equals(lastPurgedMonth.get());
    }
}
