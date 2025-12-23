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

package org.wso2.carbon.usage.data.collector.identity.counter;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.usage.data.collector.identity.util.UsageCollectorConstants;
import org.wso2.carbon.user.core.UserStoreManager;
import org.wso2.carbon.user.core.common.AbstractUserStoreManager;
import org.wso2.carbon.user.core.jdbc.JDBCUserStoreManager;
import org.wso2.carbon.user.core.service.RealmService;
import org.wso2.carbon.user.core.UserCoreConstants;
import org.apache.commons.lang.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Counter to calculate total users in the system.
 */
public class UserCounter {

    private static final Log LOG = LogFactory.getLog(UserCounter.class);

    // Configuration
    private static final int LDAP_PAGE_SIZE = 100;
    private static final int MAX_LDAP_ITERATIONS = 1000;
    private static final long SLEEP_BETWEEN_REQUESTS_MS = 100; // 100ms
    private static final long SLEEP_AFTER_MAX_REQUESTS_MS = 5_000; // 5 seconds
    private static final int MAX_REQUESTS_PER_MINUTE = 2;

    private final RealmService realmService;

    public UserCounter(RealmService realmService) {

        this.realmService = realmService;
    }

    /**
     * Count users in tenant across all user stores
     */
    public int countUsersInOrganization(String tenantDomain) {

        try {
            int tenantId = realmService.getTenantManager().getTenantId(tenantDomain);

            PrivilegedCarbonContext.startTenantFlow();
            PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantId(tenantId);
            PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(tenantDomain);

            UserStoreManager userStoreManager =
                    (UserStoreManager) realmService.getTenantUserRealm(tenantId).getUserStoreManager();

            return getTotalUsersFromAllDomains(userStoreManager);
        } catch (Exception e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Error calculating user count for: " + tenantDomain, e);
            }
            return 0;
        } finally {
            PrivilegedCarbonContext.endTenantFlow();
        }
    }

    /**
     * Get total users from all user store domains
     */
    private int getTotalUsersFromAllDomains(UserStoreManager userStoreManager) throws Exception {

        int totalUsers = 0;
        String[] domains = getDomainNames(userStoreManager);

        for (String domain : domains) {
            try {
                // Todo: Currently LDAP Users are skipped and need to enable this properly.
                int count = isJDBCUserStore(userStoreManager, domain) ? countJDBCUsers(userStoreManager, domain) : 0;
                totalUsers += count;
            } catch (Exception e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Error counting users in domain: " + domain, e);
                }
            }
        }

        return totalUsers;
    }

    /**
     * Count users in JDBC domain (fast, direct count query).
     */
    private int countJDBCUsers(UserStoreManager userStoreManager, String domain) throws Exception {

        AbstractUserStoreManager abstractUSM =
                (AbstractUserStoreManager) userStoreManager.getSecondaryUserStoreManager(domain);

        if (abstractUSM instanceof JDBCUserStoreManager) {
            return (int) abstractUSM.countUsersWithClaims(UsageCollectorConstants.USERNAME_CLAIM, "*");
        }
        return 0;
    }

    /**
     * Get all domain names (PRIMARY + secondary).
     */
    private String[] getDomainNames(UserStoreManager userStoreManager) {

        List<String> domains = new ArrayList<>();

        // Add primary domain
        String primaryDomain = userStoreManager.getRealmConfiguration()
                .getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_DOMAIN_NAME);
        domains.add(StringUtils.isEmpty(primaryDomain) ?
                UserCoreConstants.PRIMARY_DEFAULT_DOMAIN_NAME : primaryDomain);

        // Add secondary domains
        UserStoreManager secondary = userStoreManager.getSecondaryUserStoreManager();
        while (secondary != null) {
            String domain = secondary.getRealmConfiguration()
                    .getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_DOMAIN_NAME);
            if (domain != null) {
                domains.add(domain.toUpperCase());
            }
            secondary = secondary.getSecondaryUserStoreManager();
        }
        // Sorting the secondary user stores to maintain an order of domains so that pagination is consistent.
        Collections.sort(domains.subList(1, domains.size()));
        return domains.toArray(new String[0]);
    }

    /**
     * Check if given domain is JDBC user store.
     */
    private boolean isJDBCUserStore(UserStoreManager userStoreManager, String domain) {

        try {
            AbstractUserStoreManager abstractUSM =
                    (AbstractUserStoreManager) userStoreManager.getSecondaryUserStoreManager(domain);
            return abstractUSM instanceof JDBCUserStoreManager;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Apply rate limiting to protect database
     * - Sleeps 100ms between every request
     * - Sleeps 6 seconds after every 10 requests
     */
    private void applyRateLimiting(int iteration) throws InterruptedException {

        // Always sleep a bit between requests
        Thread.sleep(SLEEP_BETWEEN_REQUESTS_MS);

        // After take a longer break after certain request count.
        if (iteration % MAX_REQUESTS_PER_MINUTE == 0) {
            LOG.debug(String.format("Rate limit checkpoint reached (%d requests). Sleeping for %dms",
                    iteration, SLEEP_AFTER_MAX_REQUESTS_MS));
            Thread.sleep(SLEEP_AFTER_MAX_REQUESTS_MS);
            LOG.debug("Resumed after rate limit sleep");
        }

        // After 5000 users enforce another sleep.
        if (iteration % 50 == 0) {
            Thread.sleep(SLEEP_AFTER_MAX_REQUESTS_MS);
            LOG.debug(String.format("Progress: %d iterations completed", iteration));
        }
    }
}
