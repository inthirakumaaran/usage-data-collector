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

package org.wso2.carbon.usage.data.collector.identity.util;

/**
 * Constants related to usage collection.
 */
public class UsageCollectorConstants {

    private UsageCollectorConstants() {}

    public static final String USERNAME_CLAIM = "http://wso2.org/claims/username";

    // JNDI name of the datasource holding IDN_MAU_COUNT (per-user MAU tracking rows).
    // Omit/empty to fall back to the IS identity DB.
    public static final String USAGE_TRACKING_DATASOURCE_NAME = "UsageTracking.DataSourceName";

    // Usage count collector configuration properties in identity.xml.
    public static final String USAGE_COUNT_COLLECTOR_HOUR = "UsageTracking.UsageCountCollector.Hour";
    public static final String USAGE_COUNT_COLLECTOR_MINUTE = "UsageTracking.UsageCountCollector.Minute";
    // When set to a positive value, the collector runs at this fixed interval instead of once daily.
    // Intended for testing (e.g. 300 for every 5 minutes).
    public static final String USAGE_COUNT_COLLECTOR_INTERVAL_SECONDS =
            "UsageTracking.UsageCountCollector.IntervalSeconds";
    // When true, MAU is published on every collection cycle (the current month's running distinct
    // count) instead of once per month. Non-production only — the receiver would otherwise aggregate
    // the repeated running counts into an inflated total. Defaults to false (monthly publish).
    public static final String USAGE_COUNT_COLLECTOR_MAU_INTERVAL_PUBLISH =
            "UsageTracking.UsageCountCollector.MauIntervalPublish";

}
