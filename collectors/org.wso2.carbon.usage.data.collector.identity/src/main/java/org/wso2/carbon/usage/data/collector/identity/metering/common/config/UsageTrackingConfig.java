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

package org.wso2.carbon.usage.data.collector.identity.metering.common.config;

import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe holder for usage-metering runtime configuration.
 * <p>
 * Defaults are applied at class-load time. Each event handler's {@code init()} method
 * calls the appropriate setters once the IS framework has supplied the
 * {@code [event_handler.properties]} block from {@code deployment.toml}.
 * <p>
 * Delivery of counts is handled by the usage-data collector's scheduled publish cycle
 * (through the common {@code Receiver}); this class no longer carries any publisher or
 * HTTP-endpoint configuration.
 */
public final class UsageTrackingConfig {

    // ── Property key names ──────────────────────────────────────────────────────

    // MAU
    public static final String PROP_MAU_FLUSH_INTERVAL = "flushInterval";
    public static final String PROP_MAU_FLUSH_INTERVAL_UNIT = "flushIntervalUnit";
    public static final String PROP_MAU_CACHE_RETENTION = "cacheRetentionDays";
    public static final String PROP_MAU_DB_RETENTION = "dbRetentionDays";

    // Agent
    public static final String PROP_AGENT_USERSTORE_DOMAIN = "userStoreDomain";
    /** Enable/disable agent login (AUTHENTICATION_SUCCESS) counting. Default: {@code true}. */
    public static final String PROP_AGENT_TRACK_LOGIN = "trackLogin";
    /** Enable/disable OBO token-exchange flow counting (ACTOR_TOKEN_PRESENT=true). Default: {@code true}. */
    public static final String PROP_AGENT_TRACK_OBO_TOKEN_EXCHANGE = "trackOBOTokenExchange";
    /** Enable/disable direct agent token-exchange flow counting. Default: {@code true}. */
    public static final String PROP_AGENT_TRACK_DIRECT_TOKEN_EXCHANGE = "trackDirectTokenExchange";

    // ── Defaults ────────────────────────────────────────────────────────────────

    // MAU
    private static final AtomicInteger mauFlushInterval = new AtomicInteger(15);
    private static final AtomicReference<TimeUnit> mauFlushIntervalUnit = new AtomicReference<>(TimeUnit.MINUTES);
    private static final AtomicInteger mauCacheRetentionDays = new AtomicInteger(40);
    private static final AtomicInteger mauDbRetentionDays = new AtomicInteger(60);

    // Agent
    private static final AtomicReference<String> agentUserStoreDomain = new AtomicReference<>("AGENT");
    private static final AtomicBoolean agentTrackLogin = new AtomicBoolean(true);
    private static final AtomicBoolean agentTrackOBOTokenExchange = new AtomicBoolean(true);
    private static final AtomicBoolean agentTrackDirectTokenExchange = new AtomicBoolean(true);

    private UsageTrackingConfig() {}

    // ── Getters ─────────────────────────────────────────────────────────────────

    public static int getMauFlushInterval() {
        return mauFlushInterval.get();
    }

    public static TimeUnit getMauFlushIntervalUnit() {
        return mauFlushIntervalUnit.get();
    }

    public static int getMauCacheRetentionDays() {
        return mauCacheRetentionDays.get();
    }

    public static int getMauDbRetentionDays() {
        return mauDbRetentionDays.get();
    }

    public static String getAgentUserStoreDomain() {
        return agentUserStoreDomain.get();
    }

    public static boolean isAgentTrackLoginEnabled() {
        return agentTrackLogin.get();
    }

    public static boolean isAgentTrackOBOTokenExchangeEnabled() {
        return agentTrackOBOTokenExchange.get();
    }

    public static boolean isAgentTrackDirectTokenExchangeEnabled() {
        return agentTrackDirectTokenExchange.get();
    }

    // ── Setters ─────────────────────────────────────────────────────────────────

    public static void setMauFlushInterval(int v) {
        mauFlushInterval.set(v);
    }

    public static void setMauFlushIntervalUnit(TimeUnit v) {
        mauFlushIntervalUnit.set(v != null ? v : TimeUnit.MINUTES);
    }

    public static void setMauCacheRetentionDays(int v) {
        mauCacheRetentionDays.set(v);
    }

    public static void setMauDbRetentionDays(int v) {
        mauDbRetentionDays.set(v);
    }

    public static void setAgentUserStoreDomain(String v) {
        if (v != null && !v.trim().isEmpty()) {
            agentUserStoreDomain.set(v);
        }
    }

    public static void setAgentTrackLogin(boolean v) {
        agentTrackLogin.set(v);
    }

    public static void setAgentTrackOBOTokenExchange(boolean v) {
        agentTrackOBOTokenExchange.set(v);
    }

    public static void setAgentTrackDirectTokenExchange(boolean v) {
        agentTrackDirectTokenExchange.set(v);
    }

    /**
     * Strips the handler-name prefix that IS 7.x adds to every key in
     * {@code identity-event.properties}.
     * <p>
     * IS 7.x stores properties as {@code handlerName.propertyKey=value} and
     * {@code ModuleConfiguration.getModuleProperties()} returns them with that prefix
     * still attached. Call this before reading any property so lookups work with the
     * short key names used throughout this class.
     *
     * @param handlerName the value returned by the handler's {@code getName()} method.
     * @param raw         the Properties object returned by {@code getModuleProperties()}.
     * @return a new Properties whose keys have the {@code "handlerName."} prefix removed.
     */
    public static Properties stripHandlerPrefix(String handlerName, Properties raw) {
        if (raw == null) {
            return new Properties();
        }
        Properties out = new Properties();
        String prefix = handlerName + ".";
        for (String key : raw.stringPropertyNames()) {
            if (key.startsWith(prefix)) {
                out.setProperty(key.substring(prefix.length()), raw.getProperty(key));
            } else {
                out.setProperty(key, raw.getProperty(key));
            }
        }
        return out;
    }
}
