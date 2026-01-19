/*
 * Copyright (c) 2025, WSO2 LLC. (http://www.wso2.com) All Rights Reserved.
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

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * This class schedules the collectors to run on specific time.
 */
public class UsageDataCollectorScheduler {

    private static final Log LOG = LogFactory.getLog(UsageDataCollectorScheduler.class);

    // Default Configuration (Time based Mode)
    private static final DayOfWeek DEFAULT_SCHEDULED_DAY = DayOfWeek.WEDNESDAY;
    private static final int DEFAULT_SCHEDULED_HOUR = 2;  // Hour (0-23)
    private static final int DEFAULT_SCHEDULED_MINUTE = 0; // Minute (0-59)

    // Default Configuration (Periodic Mode)
    private static final long DEFAULT_INITIAL_DELAY_SECONDS = 5;
    private static final long DEFAULT_INTERVAL_SECONDS = 3600;
    public static final String HOUR = "hour";
    public static final String MINUTE = "minute";

    // Configuration loaded from identity.xml
    private final boolean isPeriodicMode;
    private final DayOfWeek scheduledDay;
    private final int scheduledHour;
    private final int scheduledMinute;
    private final long initialDelaySeconds;
    private final long intervalSeconds;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> scheduledTask;
    private final UsageDataCollectorInterface collectorService;

    /**
     * Constructor - loads configuration from identity.xml
     */
    public UsageDataCollectorScheduler(UsageDataCollectorInterface collectorService) {

        this.collectorService = collectorService;
        // Load configuration
        ScheduleConfig config = loadConfiguration();
        this.isPeriodicMode = config.isPeriodicMode;
        this.scheduledDay = config.scheduledDay;
        this.scheduledHour = config.scheduledHour;
        this.scheduledMinute = config.scheduledMinute;
        this.initialDelaySeconds = config.initialDelaySeconds;
        this.intervalSeconds = config.intervalSeconds;
    }

    /**
     * Load configuration from identity.xml
     */
    private ScheduleConfig loadConfiguration() {

        ScheduleConfig config = new ScheduleConfig();
        try {
            // Check if periodic mode is enabled
            String usePeriodicMode = IdentityUtil.getProperty("UsageTracking.Scheduler.usePeriodicMode");
            config.isPeriodicMode = Boolean.parseBoolean(usePeriodicMode);

            if (config.isPeriodicMode) {
                // Periodic mode configuration
                config.initialDelaySeconds = parseLong(
                        IdentityUtil.getProperty("UsageTracking.Scheduler.Periodic.InitialDelaySeconds"),
                        DEFAULT_INITIAL_DELAY_SECONDS);

                config.intervalSeconds = parseLong(
                        IdentityUtil.getProperty("UsageTracking.Scheduler.Periodic.IntervalSeconds"),
                        DEFAULT_INTERVAL_SECONDS);
            } else {
                // Time based mode configuration (default)
                config.scheduledDay = parseDayOfWeek(
                        IdentityUtil.getProperty("UsageTracking.Scheduler.TimeBased.Day"),
                        DEFAULT_SCHEDULED_DAY);

                config.scheduledHour = parseInt(
                        IdentityUtil.getProperty("UsageTracking.Scheduler.TimeBased.Hour"),
                        DEFAULT_SCHEDULED_HOUR, HOUR);

                config.scheduledMinute = parseInt(
                        IdentityUtil.getProperty("UsageTracking.Scheduler.TimeBased.Minute"),
                        DEFAULT_SCHEDULED_MINUTE, MINUTE);
            }

        } catch (Exception e) {
            if (LOG.isDebugEnabled()) {
               LOG.debug("Error loading scheduler configuration, using defaults", e);
            }
        }

        return config;
    }

    /**
     * Start the weekly scheduled task
     */
    public void startScheduledTask() {

        if (scheduler != null && !scheduler.isShutdown()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Scheduler already started; skipping re-start");
            }
            return;
        }

        // Create scheduler with daemon thread
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "IS-UsageDataCollector-Thread");
            thread.setDaemon(true);
            return thread;
        });

        if (isPeriodicMode) {
            startPeriodicSchedule();
        } else {
            startTimeBasedSchedule();
        }
    }


    /**
     * Calculate initial delay to next scheduled day and time
     */
    private long calculateInitialDelay() {

        ZoneId localZone = ZoneId.systemDefault();
        ZonedDateTime now = ZonedDateTime.now(localZone);

        // Set target day and time
        ZonedDateTime nextRun = now
                .with(scheduledDay)
                .withHour(scheduledHour)
                .withMinute(scheduledMinute)
                .withSecond(0)
                .withNano(0);

        // If the target day/time has already passed this week, move to next week
        if (now.isAfter(nextRun) || now.equals(nextRun)) {
            nextRun = nextRun.plusWeeks(1);
        }

        return ChronoUnit.SECONDS.between(now, nextRun);
    }

    /**
     * Stop the scheduled task
     */
    public void stopScheduledTask() {

        if (scheduledTask != null && !scheduledTask.isCancelled()) {
            scheduledTask.cancel(false);
            LOG.debug("Scheduled task cancelled");
        }

        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                    LOG.debug("Scheduler forced shutdown");
                }
                LOG.debug("Scheduler shutdown completed");
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Scheduler shutdown interrupted", e);
                }
            }
        }
    }

    public void startPeriodicSchedule() {

        scheduledTask = scheduler.scheduleAtFixedRate(
                new UsageDataCollectorTask(collectorService),
                initialDelaySeconds,
                intervalSeconds,
                TimeUnit.SECONDS
        );

        LOG.debug("Periodic usage data collection task scheduled successfully");
    }

    private void startTimeBasedSchedule() {


        // Calculate initial delay to next scheduled day/time
        long initialDelaySeconds = calculateInitialDelay();
        long weekInSeconds = TimeUnit.DAYS.toSeconds(7);

        // Schedule task to run weekly
        scheduledTask = scheduler.scheduleAtFixedRate(
                new UsageDataCollectorTask(collectorService),
                initialDelaySeconds,
                weekInSeconds,  // Repeat every 7 days
                TimeUnit.SECONDS
        );

        LOG.debug("Time based usage data collection task scheduled successfully");
    }

    /**
     * Parse DayOfWeek from string
     */
    private DayOfWeek parseDayOfWeek(String value, DayOfWeek defaultValue) {

        if (StringUtils.isBlank(value)) {
            return defaultValue;
        }

        try {
            return DayOfWeek.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug(String.format("Invalid day of week: %s, using default: %s", value, defaultValue));
            }
            return defaultValue;
        }
    }

    /**
     * Parse integer from string
     */
    private int parseInt(String value, int defaultValue, String propertyType) {

        if (StringUtils.isBlank(value)) {
            return defaultValue;
        }

        try {
            int parsed = Integer.parseInt(value.trim());
            // Validate hour (0-23)
            if (HOUR.equals(propertyType)) {
                if (parsed < 0 || parsed > 23) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug(String.format("Invalid hour: %d, using default: %d", parsed, defaultValue));
                    }
                    return defaultValue;
                }
            }
            // Validate minute (0-59)
            if (MINUTE.equals(propertyType)) {
                if (parsed < 0 || parsed > 59) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug(String.format("Invalid minute: %d, using default: %d", parsed, defaultValue));
                    }
                    return defaultValue;
                }
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
     * Parse long from string
     */
    private long parseLong(String value, long defaultValue) {

        if (StringUtils.isBlank(value)) {
            return defaultValue;
        }

        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed < 0) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug(String.format("Negative value not allowed: %d, using default: %d",
                            parsed, defaultValue));
                }
                return defaultValue;
            }
            return parsed;
        } catch (NumberFormatException e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug(String.format("Invalid number: %s, using default: %s", value, defaultValue));
            }
            return defaultValue;
        }
    }

    /**
     * Configuration holder class
     */
    private static class ScheduleConfig {

        boolean isPeriodicMode = false;
        DayOfWeek scheduledDay = DEFAULT_SCHEDULED_DAY;
        int scheduledHour = DEFAULT_SCHEDULED_HOUR;
        int scheduledMinute = DEFAULT_SCHEDULED_MINUTE;
        long initialDelaySeconds = DEFAULT_INITIAL_DELAY_SECONDS;
        long intervalSeconds = DEFAULT_INTERVAL_SECONDS;
    }
}
