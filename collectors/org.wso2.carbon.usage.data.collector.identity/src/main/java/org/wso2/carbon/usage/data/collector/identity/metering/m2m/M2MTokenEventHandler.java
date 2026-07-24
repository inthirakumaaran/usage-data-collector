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

package org.wso2.carbon.usage.data.collector.identity.metering.m2m;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.identity.core.bean.context.MessageContext;
import org.wso2.carbon.identity.event.IdentityEventConstants;
import org.wso2.carbon.identity.event.IdentityEventException;
import org.wso2.carbon.identity.event.event.Event;
import org.wso2.carbon.identity.event.handler.AbstractEventHandler;
import org.wso2.carbon.usage.data.collector.identity.metering.common.OAuthConstants;
import org.wso2.carbon.usage.data.collector.identity.metering.common.cache.GenericCounterCache;

import java.util.Map;

/**
 * IS event handler that counts new M2M token issuances into an in-memory cache.
 * <p>
 * Every NEW OAuth2 access token issued via the {@code client_credentials} grant type is
 * counted once per tenant. Reuse of an existing valid token ({@code EXISTING_TOKEN_USED=true})
 * and grants other than {@code client_credentials} are ignored. The accumulated counts are
 * drained and published by the usage-count collector on its scheduled cycle.
 */
public class M2MTokenEventHandler extends AbstractEventHandler {

    private static final Logger LOG = LoggerFactory.getLogger(M2MTokenEventHandler.class);

    private final GenericCounterCache cache;

    public M2MTokenEventHandler(GenericCounterCache cache) {
        this.cache = cache;
    }

    @Override
    public String getName() {
        return "m2mTokenUsageHandler";
    }

    @Override
    public int getPriority(MessageContext messageContext) {
        return 200;
    }

    @Override
    public void handleEvent(Event event) throws IdentityEventException {
        if (event == null || !OAuthConstants.EVENT_POST_ISSUE_ACCESS_TOKEN.equals(event.getEventName())) {
            return;
        }

        Map<String, Object> props = event.getEventProperties();
        if (props == null || props.isEmpty()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("[M2M] Event properties are null or empty. Skipping.");
            }
            return;
        }

        // Skip if this is a reuse of an existing token — only new issuances count.
        Object existingTokenUsed = props.get(OAuthConstants.PROP_EXISTING_TOKEN_USED);
        if (Boolean.parseBoolean(String.valueOf(existingTokenUsed))) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("[M2M] Existing token reused. Skipping.");
            }
            return;
        }

        // Only client_credentials grants are M2M tokens.
        Object grantType = props.get(IdentityEventConstants.EventProperty.GRANT_TYPE);
        if (!OAuthConstants.GRANT_CLIENT_CREDENTIALS.equals(grantType)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("[M2M] Grant type '{}' is not client_credentials. Skipping.", grantType);
            }
            return;
        }

        String tenantDomain = (String) props.get(IdentityEventConstants.EventProperty.TENANT_DOMAIN);
        if (tenantDomain == null || tenantDomain.trim().isEmpty()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("[M2M] Missing tenant domain in event. Skipping.");
            }
            return;
        }

        cache.increment(tenantDomain);
        if (LOG.isDebugEnabled()) {
            LOG.debug("[M2M] Incremented M2M token count for tenant={}.", tenantDomain);
        }
    }
}
