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
 * All usage metric count types tracked for the Identity Server.
 * <p>
 * The {@link #getValue()} of each type is published as the {@code type} field of the
 * {@code UsageCount} payload handed to the usage-data receiver.
 */
public enum CountType {

    /** Unique human users who authenticated at least once in a calendar month. */
    MAU("MAU"),

    /**
     * New machine-to-machine access tokens issued via the {@code client_credentials}
     * OAuth2 grant type (reuse of existing tokens does not count).
     */
    M2M_TOKEN("M2M_TOKEN"),

    /** New agent identities provisioned into the agent userstore. */
    AGENT_PROVISION("AGENT_PROVISION"),

    /** Updates to agent profiles, credentials, or role assignments. */
    AGENT_UPDATE("AGENT_UPDATE"),

    /** Agent identities removed from the agent userstore. */
    AGENT_DELETE("AGENT_DELETE"),

    /**
     * Account lock or disable state change applied to an agent identity
     * (claims {@code accountLocked} or {@code accountDisabled} updated).
     */
    AGENT_STATUS_CHANGE("AGENT_STATUS_CHANGE"),

    /** Successful authentication events completed by agent identities. */
    AGENT_LOGIN("AGENT_LOGIN"),

    /**
     * Access tokens obtained on behalf of agent identities via the
     * {@code authorization_code} grant where {@code ACTOR_TOKEN_PRESENT=true}
     * (token-exchange / impersonation flows).
     */
    AGENT_TOKEN("AGENT_TOKEN"),

    /**
     * New access tokens issued via the {@code refresh_token} grant on behalf of
     * agent identities ({@code ACTOR_TOKEN_PRESENT=true}).
     */
    AGENT_REFRESH_TOKEN("AGENT_REFRESH_TOKEN"),

    /**
     * Access tokens issued directly to agent identities via the
     * {@code authorization_code} grant where the authenticated user belongs to
     * the agent userstore (direct agent login — no actor-token exchange).
     */
    AGENT_DIRECT_TOKEN("AGENT_DIRECT_TOKEN"),

    /**
     * New access tokens issued via the {@code refresh_token} grant where the
     * original token was obtained through a direct agent login.
     */
    AGENT_DIRECT_REFRESH_TOKEN("AGENT_DIRECT_REFRESH_TOKEN");

    private final String value;

    CountType(String value) {
        this.value = value;
    }

    /**
     * Value published as the {@code type} field of the {@code UsageCount} payload.
     * <p>
     * Note: the receiver caps the published type at 20 characters, so
     * {@code AGENT_DIRECT_REFRESH_TOKEN} is delivered as {@code AGENT_DIRECT_REFRESH};
     * this remains unique across all types.
     */
    public String getValue() {
        return value;
    }
}
