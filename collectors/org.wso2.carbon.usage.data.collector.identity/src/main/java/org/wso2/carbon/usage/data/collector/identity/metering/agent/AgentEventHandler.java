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

package org.wso2.carbon.usage.data.collector.identity.metering.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.identity.application.authentication.framework.model.AuthenticatedUser;
import org.wso2.carbon.identity.base.IdentityRuntimeException;
import org.wso2.carbon.identity.core.bean.context.MessageContext;
import org.wso2.carbon.identity.core.handler.InitConfig;
import org.wso2.carbon.identity.event.IdentityEventConstants;
import org.wso2.carbon.identity.event.IdentityEventException;
import org.wso2.carbon.identity.event.bean.ModuleConfiguration;
import org.wso2.carbon.identity.event.event.Event;
import org.wso2.carbon.identity.event.handler.AbstractEventHandler;
import org.wso2.carbon.usage.data.collector.identity.metering.common.OAuthConstants;
import org.wso2.carbon.usage.data.collector.identity.metering.common.cache.GenericCounterCache;
import org.wso2.carbon.usage.data.collector.identity.metering.common.config.UsageTrackingConfig;

import java.util.Map;
import java.util.Properties;

/**
 * Single event handler that tracks all agent-related login and token activity into
 * in-memory caches. The accumulated counts are drained and published by the usage-count
 * collector on its scheduled cycle.
 *
 * <h3>Event routing</h3>
 * <ul>
 *   <li>AUTHENTICATION_SUCCESS, AGENT userstore -&gt; AGENT_LOGIN.</li>
 *   <li>POST_ISSUE_ACCESS_TOKEN_V2, authorization_code, actor token -&gt; AGENT_TOKEN.</li>
 *   <li>POST_ISSUE_ACCESS_TOKEN_V2, refresh_token, actor token -&gt; AGENT_REFRESH_TOKEN.</li>
 *   <li>POST_ISSUE_ACCESS_TOKEN_V2, authorization_code, no actor token, AGENT -&gt; AGENT_DIRECT_TOKEN.</li>
 *   <li>POST_ISSUE_ACCESS_TOKEN_V2, refresh_token, no actor token, AGENT -&gt; AGENT_DIRECT_REFRESH_TOKEN.</li>
 * </ul>
 */
public class AgentEventHandler extends AbstractEventHandler {

    private static final Logger LOG = LoggerFactory.getLogger(AgentEventHandler.class);

    private static final String EVENT_AUTHENTICATION_SUCCESS = "AUTHENTICATION_SUCCESS";

    // Login.
    private final GenericCounterCache loginCache;
    // Token exchange (ACTOR_TOKEN_PRESENT = true).
    private final GenericCounterCache tokenExchangeCache;
    private final GenericCounterCache tokenExchangeRefreshCache;
    // Direct agent login (AGENT userstore, no actor token).
    private final GenericCounterCache directLoginCache;
    private final GenericCounterCache directLoginRefreshCache;

    public AgentEventHandler(GenericCounterCache loginCache,
                             GenericCounterCache tokenExchangeCache,
                             GenericCounterCache tokenExchangeRefreshCache,
                             GenericCounterCache directLoginCache,
                             GenericCounterCache directLoginRefreshCache) {
        this.loginCache = loginCache;
        this.tokenExchangeCache = tokenExchangeCache;
        this.tokenExchangeRefreshCache = tokenExchangeRefreshCache;
        this.directLoginCache = directLoginCache;
        this.directLoginRefreshCache = directLoginRefreshCache;
    }

    @Override
    public String getName() {
        return "agentUsageHandler";
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
            }
        }
    }

    @Override
    public void handleEvent(Event event) throws IdentityEventException {
        if (event == null) {
            return;
        }
        String eventName = event.getEventName();
        if (EVENT_AUTHENTICATION_SUCCESS.equals(eventName)) {
            handleAuthSuccess(event);
        } else if (OAuthConstants.EVENT_POST_ISSUE_ACCESS_TOKEN.equals(eventName)) {
            handleTokenIssue(event);
        }
    }

    // ── Event handlers ────────────────────────────────────────────────────────

    private void handleAuthSuccess(Event event) {
        if (!UsageTrackingConfig.isAgentTrackLoginEnabled()) {
            return;
        }

        Map<String, Object> eventProps = event.getEventProperties();
        if (eventProps == null) {
            return;
        }

        AuthenticatedUser user = extractAuthenticatedUser(eventProps);
        if (user == null) {
            return;
        }

        String userStoreDomain = user.getUserStoreDomain();
        if (!UsageTrackingConfig.getAgentUserStoreDomain().equalsIgnoreCase(userStoreDomain)) {
            return;
        }

        String tenantDomain = user.getTenantDomain();
        if (tenantDomain == null || tenantDomain.trim().isEmpty()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("[Agent] AUTHENTICATION_SUCCESS: missing tenant domain. Skipping.");
            }
            return;
        }

        loginCache.increment(tenantDomain);
        if (LOG.isDebugEnabled()) {
            LOG.debug("[Agent] AGENT_LOGIN incremented for tenant={}.", tenantDomain);
        }
    }

    private void handleTokenIssue(Event event) {
        Map<String, Object> props = event.getEventProperties();
        if (props == null || props.isEmpty()) {
            return;
        }

        Object grantType = props.get(IdentityEventConstants.EventProperty.GRANT_TYPE);
        // Only authorization_code and refresh_token grants are agent-relevant here;
        // client_credentials is handled by M2MTokenEventHandler.
        if (!OAuthConstants.GRANT_AUTHORIZATION_CODE.equals(grantType)
                && !OAuthConstants.GRANT_REFRESH_TOKEN.equals(grantType)) {
            return;
        }

        String tenantDomain = (String) props.get(IdentityEventConstants.EventProperty.TENANT_DOMAIN);
        if (tenantDomain == null || tenantDomain.trim().isEmpty()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("[Agent] POST_ISSUE_ACCESS_TOKEN_V2: missing tenant domain. Skipping.");
            }
            return;
        }

        boolean hasActorToken = Boolean.parseBoolean(
                String.valueOf(props.get(OAuthConstants.PROP_ACTOR_TOKEN_PRESENT)));

        if (hasActorToken) {
            handleTokenExchange(grantType, tenantDomain);
        } else {
            handleDirectLogin(props, grantType, tenantDomain);
        }
    }

    private void handleTokenExchange(Object grantType, String tenantDomain) {
        if (!UsageTrackingConfig.isAgentTrackOBOTokenExchangeEnabled()) {
            return;
        }

        if (OAuthConstants.GRANT_AUTHORIZATION_CODE.equals(grantType)) {
            tokenExchangeCache.increment(tenantDomain);
            if (LOG.isDebugEnabled()) {
                LOG.debug("[Agent] AGENT_TOKEN (token-exchange, auth_code) incremented for tenant={}.", tenantDomain);
            }
        } else if (OAuthConstants.GRANT_REFRESH_TOKEN.equals(grantType)) {
            tokenExchangeRefreshCache.increment(tenantDomain);
            if (LOG.isDebugEnabled()) {
                LOG.debug("[Agent] AGENT_REFRESH_TOKEN (token-exchange, refresh) incremented for tenant={}.",
                        tenantDomain);
            }
        }
    }

    private void handleDirectLogin(Map<String, Object> props, Object grantType, String tenantDomain) {
        if (!UsageTrackingConfig.isAgentTrackDirectTokenExchangeEnabled()) {
            return;
        }

        String userStoreDomain = extractUserStoreDomain(props);
        if (userStoreDomain == null
                || !UsageTrackingConfig.getAgentUserStoreDomain().equalsIgnoreCase(userStoreDomain)) {
            return;
        }

        if (OAuthConstants.GRANT_AUTHORIZATION_CODE.equals(grantType)) {
            directLoginCache.increment(tenantDomain);
            if (LOG.isDebugEnabled()) {
                LOG.debug("[Agent] AGENT_DIRECT_TOKEN (direct, auth_code) incremented for tenant={}.", tenantDomain);
            }
        } else if (OAuthConstants.GRANT_REFRESH_TOKEN.equals(grantType)) {
            directLoginRefreshCache.increment(tenantDomain);
            if (LOG.isDebugEnabled()) {
                LOG.debug("[Agent] AGENT_DIRECT_REFRESH_TOKEN (direct, refresh) incremented for tenant={}.",
                        tenantDomain);
            }
        }
    }

    // ── Config ────────────────────────────────────────────────────────────────

    private static void applyConfig(Properties props) {
        UsageTrackingConfig.setAgentUserStoreDomain(
                props.getProperty(UsageTrackingConfig.PROP_AGENT_USERSTORE_DOMAIN));

        String trackLogin = props.getProperty(UsageTrackingConfig.PROP_AGENT_TRACK_LOGIN);
        if (trackLogin != null && !trackLogin.trim().isEmpty()) {
            UsageTrackingConfig.setAgentTrackLogin(Boolean.parseBoolean(trackLogin.trim()));
        }
        String trackOBOTokenExchange = props.getProperty(UsageTrackingConfig.PROP_AGENT_TRACK_OBO_TOKEN_EXCHANGE);
        if (trackOBOTokenExchange != null && !trackOBOTokenExchange.trim().isEmpty()) {
            UsageTrackingConfig.setAgentTrackOBOTokenExchange(Boolean.parseBoolean(trackOBOTokenExchange.trim()));
        }
        String trackDirectTokenExchange = props.getProperty(UsageTrackingConfig.PROP_AGENT_TRACK_DIRECT_TOKEN_EXCHANGE);
        if (trackDirectTokenExchange != null && !trackDirectTokenExchange.trim().isEmpty()) {
            UsageTrackingConfig.setAgentTrackDirectTokenExchange(Boolean.parseBoolean(trackDirectTokenExchange.trim()));
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("[Agent] Config loaded: userStoreDomain={}, trackLogin={}, "
                            + "trackOBOTokenExchange={}, trackDirectTokenExchange={}.",
                    UsageTrackingConfig.getAgentUserStoreDomain(),
                    UsageTrackingConfig.isAgentTrackLoginEnabled(),
                    UsageTrackingConfig.isAgentTrackOBOTokenExchangeEnabled(),
                    UsageTrackingConfig.isAgentTrackDirectTokenExchangeEnabled());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    /**
     * Checks the {@code "userstore-domain"} string property first, then falls back to the
     * {@code USER} property cast to {@link AuthenticatedUser}.
     */
    private static String extractUserStoreDomain(Map<String, Object> props) {
        Object domain = props.get("userstore-domain");
        if (domain instanceof String && !((String) domain).trim().isEmpty()) {
            return (String) domain;
        }
        Object userObj = props.get(IdentityEventConstants.EventProperty.USER);
        if (userObj instanceof AuthenticatedUser) {
            return ((AuthenticatedUser) userObj).getUserStoreDomain();
        }
        return null;
    }
}
