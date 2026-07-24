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
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.usage.data.collector.identity.metering.common.cache.GenericCounterCache;
import org.wso2.carbon.usage.data.collector.identity.metering.common.config.UsageTrackingConfig;
import org.wso2.carbon.user.core.UserStoreException;
import org.wso2.carbon.user.core.UserStoreManager;
import org.wso2.carbon.user.core.common.AbstractUserOperationEventListener;
import org.wso2.carbon.user.core.util.UserCoreUtil;

import java.util.Map;

/**
 * Counts administrative CRUD and status-change operations on agent identities managed in
 * the configured agent userstore, accumulating into in-memory caches drained by the
 * usage-count collector.
 * <ul>
 *   <li><b>AGENT_PROVISION</b> – a new agent identity is created.</li>
 *   <li><b>AGENT_DELETE</b> – an agent identity is removed.</li>
 *   <li><b>AGENT_UPDATE</b> – an agent's credentials are changed (self or admin).</li>
 *   <li><b>AGENT_STATUS_CHANGE</b> – an agent's account is locked or disabled.</li>
 * </ul>
 * Only operations on users whose userstore domain matches
 * {@link UsageTrackingConfig#getAgentUserStoreDomain()} (case-insensitive) are counted.
 * Registered as an OSGi {@code UserOperationEventListener} with execution order {@code 9001}.
 */
public class AgentManagementListener extends AbstractUserOperationEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(AgentManagementListener.class);

    /** Claim URI set when an account is locked. */
    private static final String CLAIM_ACCOUNT_LOCKED = "http://wso2.org/claims/identity/accountLocked";
    /** Claim URI set when an account is administratively disabled. */
    private static final String CLAIM_ACCOUNT_DISABLED = "http://wso2.org/claims/identity/accountDisabled";

    private final GenericCounterCache provisionCache;
    private final GenericCounterCache updateCache;
    private final GenericCounterCache deleteCache;
    private final GenericCounterCache statusChangeCache;

    public AgentManagementListener(GenericCounterCache provisionCache,
                                   GenericCounterCache updateCache,
                                   GenericCounterCache deleteCache,
                                   GenericCounterCache statusChangeCache) {
        this.provisionCache = provisionCache;
        this.updateCache = updateCache;
        this.deleteCache = deleteCache;
        this.statusChangeCache = statusChangeCache;
    }

    /** Runs after all core IS handlers (which use 10–1000) so only completed operations are counted. */
    @Override
    public int getExecutionOrderId() {
        return 9001;
    }

    @Override
    public boolean doPostAddUser(String userName, Object credential, String[] roleList,
                                 Map<String, String> claims, String profile,
                                 UserStoreManager userStoreManager) throws UserStoreException {

        if (!isAgentUserStore(userStoreManager)) {
            return true;
        }
        provisionCache.increment(resolveTenantDomain(userStoreManager));
        if (LOG.isDebugEnabled()) {
            LOG.debug("[AgentMgmt] AGENT_PROVISION: user={}", userName);
        }
        return true;
    }

    @Override
    public boolean doPostDeleteUser(String userName,
                                    UserStoreManager userStoreManager) throws UserStoreException {

        if (!isAgentUserStore(userStoreManager)) {
            return true;
        }
        deleteCache.increment(resolveTenantDomain(userStoreManager));
        if (LOG.isDebugEnabled()) {
            LOG.debug("[AgentMgmt] AGENT_DELETE: user={}", userName);
        }
        return true;
    }

    /** Agent changes its own credentials. */
    @Override
    public boolean doPostUpdateCredential(String userName, Object credential,
                                          UserStoreManager userStoreManager) throws UserStoreException {

        if (!isAgentUserStore(userStoreManager)) {
            return true;
        }
        updateCache.increment(resolveTenantDomain(userStoreManager));
        if (LOG.isDebugEnabled()) {
            LOG.debug("[AgentMgmt] AGENT_UPDATE (self credential update): user={}", userName);
        }
        return true;
    }

    /** Admin resets an agent's credentials. */
    @Override
    public boolean doPostUpdateCredentialByAdmin(String userName, Object credential,
                                                 UserStoreManager userStoreManager) throws UserStoreException {

        if (!isAgentUserStore(userStoreManager)) {
            return true;
        }
        updateCache.increment(resolveTenantDomain(userStoreManager));
        if (LOG.isDebugEnabled()) {
            LOG.debug("[AgentMgmt] AGENT_UPDATE (admin credential reset): user={}", userName);
        }
        return true;
    }

    /**
     * Fires after any claim update. Only counted when the {@code accountLocked} or
     * {@code accountDisabled} claim is included.
     */
    @Override
    public boolean doPostSetUserClaimValues(String userName, Map<String, String> claims,
                                            String profileName,
                                            UserStoreManager userStoreManager) throws UserStoreException {

        if (!isAgentUserStore(userStoreManager)) {
            return true;
        }
        if (!hasStatusChangeClaim(claims)) {
            return true;
        }

        String tenantDomain = resolveTenantDomain(userStoreManager);
        statusChangeCache.increment(tenantDomain);
        if (LOG.isDebugEnabled()) {
            LOG.debug("[AgentMgmt] AGENT_STATUS_CHANGE: user={} tenant={}", userName, tenantDomain);
        }
        return true;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isAgentUserStore(UserStoreManager userStoreManager) {
        String domain = UserCoreUtil.getDomainName(userStoreManager.getRealmConfiguration());
        return UsageTrackingConfig.getAgentUserStoreDomain().equalsIgnoreCase(domain);
    }

    private String resolveTenantDomain(UserStoreManager userStoreManager) {
        try {
            return IdentityTenantUtil.getTenantDomain(userStoreManager.getTenantId());
        } catch (Exception e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("[AgentMgmt] Could not resolve tenant domain: {}", e.getMessage());
            }
            return "unknown";
        }
    }

    private boolean hasStatusChangeClaim(Map<String, String> claims) {
        return claims != null
                && (claims.containsKey(CLAIM_ACCOUNT_LOCKED)
                || claims.containsKey(CLAIM_ACCOUNT_DISABLED));
    }
}
