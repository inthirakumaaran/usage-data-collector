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

package org.wso2.carbon.usage.data.collector.identity.metering.common;

/**
 * OAuth2 event and grant-type constants shared across usage-metering handlers.
 */
public final class OAuthConstants {

    /** IS event name fired after every access token issuance attempt. */
    public static final String EVENT_POST_ISSUE_ACCESS_TOKEN = "POST_ISSUE_ACCESS_TOKEN_V2";

    public static final String GRANT_AUTHORIZATION_CODE = "authorization_code";
    public static final String GRANT_REFRESH_TOKEN = "refresh_token";
    public static final String GRANT_CLIENT_CREDENTIALS = "client_credentials";

    /**
     * Boolean event property set by IS when actor-token exchange (impersonation /
     * agentic token-exchange) is in progress. Absent or {@code false} for all
     * other grant flows including direct agent logins.
     */
    public static final String PROP_ACTOR_TOKEN_PRESENT = "ACTOR_TOKEN_PRESENT";

    /**
     * Boolean token-event property set {@code true} when a cached valid token was reused
     * instead of a new one being issued. Only new issuances are counted.
     */
    public static final String PROP_EXISTING_TOKEN_USED = "EXISTING_TOKEN_USED";

    private OAuthConstants() {}
}
