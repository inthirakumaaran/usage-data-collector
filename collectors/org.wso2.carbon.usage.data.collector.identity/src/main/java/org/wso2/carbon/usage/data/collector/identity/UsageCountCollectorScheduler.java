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

package org.wso2.carbon.usage.data.collector.identity;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.usage.data.collector.identity.util.UsageCollectorConstants;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Schedules the usage count collector to run daily around midnight.
 * The exact run time can be configured through identity.xml using the
 * UsageTracking.UsageCountCollector.Hour and UsageTracking.UsageCountCollector.Minute properties.
 */
public class UsageCountCollectorScheduler {

    private static final Log LOG = LogFactory.getLog(UsageCountCollectorScheduler.class);

    // Default run time: 00:30, shortly after midnight so the previous day's counts are complete.
    private static final int DEFAULT_SCHEDULED_HOUR = 0;
    private static final int DEFAULT_SCHEDULED_MINUTE = 30;

    // Default publish cadence: hourly. M2M and agent counts are published on every cycle, so an
    // hourly interval yields hourly usage data by default. Set IntervalSeconds to 0 to switch to
    // the daily midnight schedule, or to a smaller value for faster feedback while testing.
    private static final long DEFAULT_INTERVAL_SECONDS = 3600;
    private static final long INTERVAL_MODE_INITIAL_DELAY_SECONDS = 60;

    private final int scheduledHour;
    private final int scheduledMinute;
    private final long intervalSeconds;
    private final UsageDataCollectorInterface collectorService;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> scheduledTask;

    public UsageCountCollectorScheduler(UsageDataCollectorInterface collectorService) {

        this.collectorService = collectorService;
        this.scheduledHour = parseInt(
                IdentityUtil.getProperty(UsageCollectorConstants.USAGE_COUNT_COLLECTOR_HOUR),
                DEFAULT_SCHEDULED_HOUR, 23);
        this.scheduledMinute = parseInt(
                IdentityUtil.getProperty(UsageCollectorConstants.USAGE_COUNT_COLLECTOR_MINUTE),
                DEFAULT_SCHEDULED_MINUTE, 59);
        this.intervalSeconds = parseLong(
                IdentityUtil.getProperty(UsageCollectorConstants.USAGE_COUNT_COLLECTOR_INTERVAL_SECONDS),
                DEFAULT_INTERVAL_SECONDS);
    }

    /**
     * Start the daily scheduled task.
     */
    public void startScheduledTask() {

        if (scheduler != null && !scheduler.isShutdown()) {
            LOG.debug("Usage count collector scheduler already started; skipping re-start");
            return;
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "IS-UsageCountCollector-Thread");
            thread.setDaemon(true);
            return thread;
        });

        long initialDelaySeconds;
        long periodSeconds;
        if (intervalSeconds > 0) {
            // Interval (testing) mode: run at a fixed cadence.
            initialDelaySeconds = INTERVAL_MODE_INITIAL_DELAY_SECONDS;
            periodSeconds = intervalSeconds;
        } else {
            // Daily mode: run once per day at the configured time.
            initialDelaySeconds = calculateInitialDelay();
            periodSeconds = TimeUnit.DAYS.toSeconds(1);
        }

        scheduledTask = scheduler.scheduleAtFixedRate(
                new UsageDataCollectorTask(collectorService),
                initialDelaySeconds,
                periodSeconds,
                TimeUnit.SECONDS
        );

        if (LOG.isDebugEnabled()) {
            if (intervalSeconds > 0) {
                LOG.debug(String.format("Usage count collection task scheduled every %d seconds "
                        + "(first run in %d seconds)", periodSeconds, initialDelaySeconds));
            } else {
                LOG.debug(String.format("Daily usage count collection task scheduled at %02d:%02d "
                        + "(first run in %d seconds)", scheduledHour, scheduledMinute, initialDelaySeconds));
            }
        }
    }

    /**
     * Stop the scheduled task.
     */
    public void stopScheduledTask() {

        if (scheduledTask != null && !scheduledTask.isCancelled()) {
            scheduledTask.cancel(false);
            LOG.debug("Usage count collector task cancelled");
        }

        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                    LOG.debug("Usage count collector scheduler forced shutdown");
                }
                LOG.debug("Usage count collector scheduler shutdown completed");
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Usage count collector scheduler shutdown interrupted", e);
                }
            }
        }
    }

    /**
     * Calculate the delay until the next scheduled time of day.
     */
    private long calculateInitialDelay() {

        ZoneId localZone = ZoneId.systemDefault();
        ZonedDateTime now = ZonedDateTime.now(localZone);

        ZonedDateTime nextRun = now
                .withHour(scheduledHour)
                .withMinute(scheduledMinute)
                .withSecond(0)
                .withNano(0);

        // If the target time has already passed today, move to tomorrow.
        if (!now.isBefore(nextRun)) {
            nextRun = nextRun.plusDays(1);
        }

        return ChronoUnit.SECONDS.between(now, nextRun);
    }

    /**
     * Parse a bounded integer configuration value, falling back to the default on invalid input.
     */
    private int parseInt(String value, int defaultValue, int maxValue) {

        if (StringUtils.isBlank(value)) {
            return defaultValue;
        }

        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 0 || parsed > maxValue) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug(String.format("Value out of range: %d, using default: %d", parsed, defaultValue));
                }
                return defaultValue;
            }
            return parsed;
        } catch (NumberFormatException e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug(String.format("Invalid number: %s, using default: %d", value, defaultValue));
            }
            return defaultValue;
        }
    }

    /**
     * Parse a long configuration value, falling back to the default on invalid input.
     * Negative values are treated as 0, which disables interval mode.
     */
    private long parseLong(String value, long defaultValue) {

        if (StringUtils.isBlank(value)) {
            return defaultValue;
        }

        try {
            long parsed = Long.parseLong(value.trim());
            return Math.max(parsed, 0);
        } catch (NumberFormatException e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug(String.format("Invalid number: %s, using default: %d", value, defaultValue));
            }
            return defaultValue;
        }
    }
}
