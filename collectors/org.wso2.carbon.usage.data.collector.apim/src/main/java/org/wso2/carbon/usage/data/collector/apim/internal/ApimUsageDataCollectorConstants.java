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

package org.wso2.carbon.usage.data.collector.apim.internal;

/**
 * Configuration constants for APIM core usage data collector.
 * Provides default values and system property keys.
 *
 * Follows the common module's pattern of centralized configuration.
 * Transaction-related constants are in the gateway-specific bundle.
 */
public class ApimUsageDataCollectorConstants {

    // Database queries
    public static final String API_COUNT_QUERY =
            "SELECT COUNT(*) AS api_count FROM AM_API WHERE API_TYPE != 'MCP' OR API_TYPE IS NULL";
    public static final String MCP_API_COUNT_QUERY =
            "SELECT COUNT(*) AS mcp_api_count FROM AM_API WHERE API_TYPE = 'MCP'";

    // Usage count types
    public static final String API_COUNT_TYPE = "API_COUNT";
    public static final String MCP_API_COUNT_TYPE = "MCP_API_COUNT";

    // Endpoints
    public static final String USAGE_COUNT_ENDPOINT = "usage-data-receiver/usage-counts";

    // Shutdown timeout
    public static final long SHUTDOWN_TIMEOUT_SECONDS = 60;


    private ApimUsageDataCollectorConstants() {
        // Private constructor to prevent instantiation
    }
}

