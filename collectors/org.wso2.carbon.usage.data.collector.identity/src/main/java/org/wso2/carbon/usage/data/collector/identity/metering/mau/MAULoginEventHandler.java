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
import org.wso2.carbon.identity.application.authentication.framework.model.AuthenticatedUser;
import org.wso2.carbon.identity.base.IdentityRuntimeException;
import org.wso2.carbon.identity.core.bean.context.MessageContext;
import org.wso2.carbon.identity.core.handler.InitConfig;
import org.wso2.carbon.identity.event.IdentityEventException;
import org.wso2.carbon.identity.event.bean.ModuleConfiguration;
import org.wso2.carbon.identity.event.event.Event;
import org.wso2.carbon.identity.event.handler.AbstractEventHandler;
import org.wso2.carbon.usage.data.collector.identity.metering.common.config.UsageTrackingConfig;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * IS event handler that listens for {@code AUTHENTICATION_SUCCESS} events and
 * asynchronously records each login in {@link MAUCacheManager}.
 * <p>
 * The cache-to-DB flush scheduler is started inside {@link #init} — after the IS framework
 * has supplied {@code deployment.toml} properties — so the configured interval is always
 * used. Every unique human user who successfully authenticates at least once in a calendar
 * month is counted once toward that month's MAU.
 * <p>
 * Logins by agent identities (users in the configured agent userstore domain) are excluded —
 * they are tracked separately as {@code AGENT_LOGIN} by the agent handler.
 */
public class MAULoginEventHandler extends AbstractEventHandler {

    private static final Logger LOG = LoggerFactory.getLogger(MAULoginEventHandler.class);
    private static final String EVENT_AUTHENTICATION_SUCCESS = "AUTHENTICATION_SUCCESS";

    private final ExecutorService cacheWriter =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "mau-cache-writer");
                t.setDaemon(true);
                return t;
            });

    private final ScheduledExecutorService scheduler;
    private final MAUFlushTask flushTask;
    private final AtomicBoolean schedulerStarted = new AtomicBoolean(false);

    public MAULoginEventHandler(ScheduledExecutorService scheduler, MAUFlushTask flushTask) {
        this.scheduler = scheduler;
        this.flushTask = flushTask;
    }

    @Override
    public String getName() {
        return "mauLoginEventHandler";
    }

    @Override
    public int getPriority(MessageContext messageContext) {
        return 200;
    }

    @Override
    public void init(InitConfig configuration) throws IdentityRuntimeException {
        super.init(configuration);
        if (this.configs instanceof ModuleConfiguration) {
            Properties props = ((ModuleConfiguration) this.configs).getModuleProperties();
            if (props != null && !props.isEmpty()) {
                applyConfig(UsageTrackingConfig.stripHandlerPrefix(getName(), props));
            } else if (LOG.isDebugEnabled()) {
                LOG.debug("[MAU] No handler properties in deployment.toml; using defaults.");
            }
        } else if (LOG.isDebugEnabled()) {
            LOG.debug("[MAU] configs is not ModuleConfiguration; using defaults.");
        }
        startSchedulerOnce();
    }

    @Override
    public void handleEvent(Event event) throws IdentityEventException {
        if (event == null || !EVENT_AUTHENTICATION_SUCCESS.equals(event.getEventName())) {
            return;
        }

        Map<String, Object> eventProps = event.getEventProperties();
        if (eventProps == null) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("[MAU] AUTHENTICATION_SUCCESS event has null properties. Skipping.");
            }
            return;
        }

        AuthenticatedUser user = extractAuthenticatedUser(eventProps);
        if (user == null) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("[MAU] Unable to extract AuthenticatedUser from event. Skipping.");
            }
            return;
        }

        // Agent identities are counted separately (AGENT_LOGIN); exclude their logins from MAU.
        String userStoreDomain = user.getUserStoreDomain();
        if (UsageTrackingConfig.getAgentUserStoreDomain().equalsIgnoreCase(userStoreDomain)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("[MAU] Skipping agent-userstore login for MAU: userStoreDomain={}", userStoreDomain);
            }
            return;
        }

        String userId;
        try {
            userId = user.getUserId();
        } catch (Exception e) {
            userId = null;
        }
        if (userId == null || userId.trim().isEmpty()) {
            userId = user.getUserName();
        }
        String tenantDomain = user.getTenantDomain();

        if (userId == null || tenantDomain == null) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("[MAU] Missing userId or tenantDomain. Skipping.");
            }
            return;
        }

        final String finalUserId = userId;
        final String finalTenant = tenantDomain;
        cacheWriter.submit(() -> {
            try {
                MAUCacheManager.getInstance().recordLogin(finalUserId, finalTenant);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("[MAU] Cache updated: userId={} tenant={}", finalUserId, finalTenant);
                }
            } catch (Exception e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("[MAU] Failed to record login: userId={} tenant={}",
                            finalUserId, finalTenant, e);
                }
            }
        });
    }

    public void shutdown() {
        cacheWriter.shutdown();
        try {
            if (!cacheWriter.awaitTermination(10, TimeUnit.SECONDS)) {
                cacheWriter.shutdownNow();
            }
        } catch (InterruptedException ie) {
            cacheWriter.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void applyConfig(Properties props) {
        UsageTrackingConfig.setMauFlushInterval(
                parseIntProp(props, UsageTrackingConfig.PROP_MAU_FLUSH_INTERVAL,
                        UsageTrackingConfig.getMauFlushInterval()));
        UsageTrackingConfig.setMauFlushIntervalUnit(
                parseTimeUnit(props.getProperty(UsageTrackingConfig.PROP_MAU_FLUSH_INTERVAL_UNIT)));
        UsageTrackingConfig.setMauCacheRetentionDays(
                parseIntProp(props, UsageTrackingConfig.PROP_MAU_CACHE_RETENTION,
                        UsageTrackingConfig.getMauCacheRetentionDays()));
        UsageTrackingConfig.setMauDbRetentionDays(
                parseIntProp(props, UsageTrackingConfig.PROP_MAU_DB_RETENTION,
                        UsageTrackingConfig.getMauDbRetentionDays()));
        if (LOG.isDebugEnabled()) {
            LOG.debug("[MAU] Config loaded: flushInterval={} {}, cacheRetention={}d, dbRetention={}d.",
                    UsageTrackingConfig.getMauFlushInterval(),
                    UsageTrackingConfig.getMauFlushIntervalUnit(),
                    UsageTrackingConfig.getMauCacheRetentionDays(),
                    UsageTrackingConfig.getMauDbRetentionDays());
        }
    }

    private void startSchedulerOnce() {
        if (schedulerStarted.compareAndSet(false, true)) {
            long interval = UsageTrackingConfig.getMauFlushInterval();
            TimeUnit unit = UsageTrackingConfig.getMauFlushIntervalUnit();
            scheduler.scheduleAtFixedRate(flushTask, interval, interval, unit);
            if (LOG.isDebugEnabled()) {
                LOG.debug("[MAU] Flush scheduler started: interval={} {}.", interval, unit);
            }
        }
    }

    private static AuthenticatedUser extractAuthenticatedUser(Map<String, Object> eventProps) {
        Object paramsObj = eventProps.get("params");
        if (paramsObj instanceof Map) {
            Object userObj = ((Map<?, ?>) paramsObj).get("user");
            if (userObj instanceof AuthenticatedUser) {
                return (AuthenticatedUser) userObj;
            }
        }
        return null;
    }

    private static int parseIntProp(Properties props, String key, int defaultValue) {
        String raw = props.getProperty(key);
        if (raw == null || raw.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("[MAU] Invalid integer '{}' for '{}'; using default {}.", raw, key, defaultValue);
            }
            return defaultValue;
        }
    }

    private static TimeUnit parseTimeUnit(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return TimeUnit.MINUTES;
        }
        switch (raw.trim().toUpperCase()) {
            case "HOURS":
                return TimeUnit.HOURS;
            case "DAYS":
                return TimeUnit.DAYS;
            default:
                if (!"MINUTES".equalsIgnoreCase(raw.trim()) && LOG.isDebugEnabled()) {
                    LOG.debug("[MAU] Unknown flushIntervalUnit '{}'; defaulting to MINUTES.", raw);
                }
                return TimeUnit.MINUTES;
        }
    }
}
