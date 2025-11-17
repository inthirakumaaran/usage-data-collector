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

package org.wso2.carbon.identity.usage.data.collector.counter;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.coyote.BadRequestException;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.organization.management.service.OrganizationManager;
import org.wso2.carbon.identity.usage.data.collector.util.UsageCollectorConstants;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.user.core.UserCoreConstants;
import org.wso2.carbon.user.core.UserStoreClientException;
import org.wso2.carbon.user.core.UserStoreManager;
import org.wso2.carbon.user.core.common.AbstractUserStoreManager;
import org.wso2.carbon.user.core.jdbc.JDBCUserStoreManager;
import org.wso2.carbon.user.core.model.Condition;
import org.wso2.carbon.user.core.model.ExpressionAttribute;
import org.wso2.carbon.user.core.model.ExpressionCondition;
import org.wso2.carbon.user.core.model.ExpressionOperation;
import org.wso2.carbon.user.core.service.RealmService;

import java.util.*;

/**
 * Calculator for counting users across tenants and organizations
 */
public class UserCounter {

    private static final Log log = LogFactory.getLog(UserCounter.class);

    private final RealmService realmService;
    private final OrganizationManager organizationManager;
    private static final String USERNAME_CLAIM = "http://wso2.org/claims/username";
    private static final int LDAP_PAGE_SIZE = 100; // LDAP pagination page size
    private static final int MAX_LDAP_ITERATIONS = 1000; // Safety limit


    public UserCounter(RealmService realmService, OrganizationManager organizationManager) {

        this.realmService = realmService;
        this.organizationManager = organizationManager;
    }
    /**
     * Count all users in a tenant across all organizations
     */
    public int countAllUsersInTenant(String tenantDomain) throws Exception {

        log.debug("Counting users in tenant: " + tenantDomain);

        // Get root organization ID
        String rootOrgId = organizationManager.resolveOrganizationId(tenantDomain);

        if (rootOrgId == null) {
            log.debug("No root organization found for tenant: " + tenantDomain);
            return 0;
        }

        // Get all organization IDs in this tenant (root + children)
        List<String> allOrgIds = new ArrayList<>();
        allOrgIds.add(rootOrgId);

        List<String> childOrgIds = organizationManager.getChildOrganizationsIds(rootOrgId, true);
        if (childOrgIds != null && !childOrgIds.isEmpty()) {
            allOrgIds.addAll(childOrgIds);
        }

        log.debug("Found " + allOrgIds.size() + " organizations in tenant: " + tenantDomain);

        // Count users across all organizations
        int totalUsers = 0;
        for (String orgId : allOrgIds) {
            try {
                int usersInOrg = countUsersInOrganization(orgId);
                totalUsers += usersInOrg;
                log.debug(String.format("Organization %s has %d users", orgId, usersInOrg));
            } catch (Exception e) {
                log.error("Error counting users in organization: " + orgId, e);
            }
        }

        return totalUsers;
    }

    /**
     * Count users in a specific organization
     * Works with all user store types
     */
    private int countUsersInOrganization(String organizationId) throws Exception {

        String tenantDomain = organizationManager.resolveTenantDomain(organizationId);
        int tenantId = realmService.getTenantManager().getTenantId(tenantDomain);

        try {
            PrivilegedCarbonContext.startTenantFlow();
            PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantId(tenantId);
            PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(tenantDomain);

            UserStoreManager userStoreManager =
                    (UserStoreManager) realmService.getTenantUserRealm(tenantId)
                            .getUserStoreManager();

            return (int) getTotalUsersFromAllUserStores(userStoreManager);

        } finally {
            PrivilegedCarbonContext.endTenantFlow();
        }
    }


    private long getTotalUsersFromAllUserStores(UserStoreManager userStoreManager) throws Exception {

        long totalUsers = 0;
        String[] userStoreDomainNames = getDomainNames(userStoreManager);
        boolean canCountTotalUserCount = canCountTotalUserCount(userStoreManager, userStoreDomainNames);
        for (String userStoreDomainName : userStoreDomainNames) {
            if (isJDBCUserStore(userStoreManager, userStoreDomainName)) {
                totalUsers += getTotalUsers(userStoreManager, userStoreDomainName);
            } else {
                int maxLimit = Integer.MAX_VALUE;
                if (isConsiderMaxLimitForTotalResultEnabled()) {
                    maxLimit = getMaxLimit(userStoreDomainName, userStoreManager);
                }
                totalUsers += getUsersCountForListUsers(1, maxLimit, userStoreDomainName, userStoreManager);
            }
        }

        return totalUsers;
    }

    private int getMaxLimit(String domainName, UserStoreManager userStoreManager) {

        int givenMax = UserCoreConstants.MAX_USER_ROLE_LIST;
        if (StringUtils.isEmpty(domainName)) {
            domainName = getPrimaryUserStoreDomain(userStoreManager);
            if (log.isDebugEnabled()) {
                log.debug("Primary user store DomainName picked as " + domainName);
            }
        }
        if (userStoreManager.getSecondaryUserStoreManager(domainName).getRealmConfiguration()
                .getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_MAX_USER_LIST) != null) {
            givenMax = Integer.parseInt(userStoreManager.getSecondaryUserStoreManager(domainName).getRealmConfiguration()
                    .getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_MAX_USER_LIST));
        }

        return givenMax;
    }

    private boolean canCountTotalUserCount(UserStoreManager userStoreManager, String[] userStoreDomainNames) {

        for (String userStoreDomainName : userStoreDomainNames) {
            if (!isJDBCUserStore(userStoreManager, userStoreDomainName)) {
                return false;
            }
        }
        return true;
    }

    private boolean isJDBCUserStore(UserStoreManager userStoreManager, String userStoreDomainName) {

        AbstractUserStoreManager secondaryUserStoreManager = (AbstractUserStoreManager) userStoreManager
                .getSecondaryUserStoreManager(userStoreDomainName);
        return secondaryUserStoreManager instanceof JDBCUserStoreManager;
    }

    private long getTotalUsers(UserStoreManager userStoreManager, String domainName) throws Exception {

        long totalUsers = 0;
        AbstractUserStoreManager secondaryUserStoreManager = null;
        if (StringUtils.isNotBlank(domainName)) {
            secondaryUserStoreManager = (AbstractUserStoreManager) userStoreManager
                    .getSecondaryUserStoreManager(domainName);
        }
        try {
            if (secondaryUserStoreManager instanceof JDBCUserStoreManager) {
                totalUsers = secondaryUserStoreManager.countUsersWithClaims(USERNAME_CLAIM, "*");
            }
        } catch (org.wso2.carbon.user.core.UserStoreException e) {
            log.error( "Error while getting total user count in domain: " + domainName);
        }
        return totalUsers;
    }

    private String[] getDomainNames(UserStoreManager userStoreManager) {

        String domainName;
        ArrayList<String> domainsOfUserStores = new ArrayList<>();
        UserStoreManager secondaryUserStore = userStoreManager.getSecondaryUserStoreManager();
        while (secondaryUserStore != null) {
            domainName = secondaryUserStore.getRealmConfiguration().
                    getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_DOMAIN_NAME).toUpperCase();
            secondaryUserStore = secondaryUserStore.getSecondaryUserStoreManager();
            domainsOfUserStores.add(domainName);
        }
        // Sorting the secondary user stores to maintain an order fo domains so that pagination is consistent.
        Collections.sort(domainsOfUserStores);

        String primaryUSDomain = getPrimaryUserStoreDomain(userStoreManager);

        // Append the primary domain name to the front of the domain list since the first iteration of multiple
        // domain filtering should happen for the primary user store.
        domainsOfUserStores.add(0, primaryUSDomain);
        return domainsOfUserStores.toArray(new String[0]);
    }

    private String getPrimaryUserStoreDomain(UserStoreManager userStoreManager) {

        String primaryUSDomain = userStoreManager.getRealmConfiguration().getUserStoreProperty(UserCoreConstants.
                RealmConfig.PROPERTY_DOMAIN_NAME);
        if (StringUtils.isEmpty(primaryUSDomain)) {
            primaryUSDomain = UserCoreConstants.PRIMARY_DEFAULT_DOMAIN_NAME;
        }
        return primaryUSDomain;
    }

    private int getUsersCountForListUsers(int offset, int maxLimit, String domainName,
                                          UserStoreManager userStoreManager) throws Exception {

        ExpressionCondition condition = new ExpressionCondition(ExpressionOperation.SW.toString(),
                ExpressionAttribute.USERNAME.toString(), "");
        if (StringUtils.isNotEmpty(domainName)) {
//            return getFilteredUserCountFromSingleDomain(condition, offset, maxLimit, domainName,
//                    (AbstractUserStoreManager) userStoreManager);
            return (int) getFilteredUserCountWithPagination(condition, domainName,
                    (AbstractUserStoreManager) userStoreManager);
        }
        else {
            log.error("Domain name cannot be empty");
            return 0;
        }
    }

    private int getFilteredUserCountFromSingleDomain(Condition condition, int offset, int limit, String domainName,
                                                     AbstractUserStoreManager userStoreManager )
            throws Exception {

        if (log.isDebugEnabled()) {
            log.debug(String.format("Getting the filtered users count in domain : %s with limit: %d and offset: %d.",
                    domainName, limit, offset));
        }
        try {
            return userStoreManager.getUsersCount(condition, domainName, UserCoreConstants.DEFAULT_PROFILE, limit, offset,
                    isRemoveDuplicateUsersInUsersResponseEnabled());
        } catch (UserStoreException e) {
            // Sometimes client exceptions are wrapped in the super class.
            // Therefore checking for possible client exception.
            Throwable rootCause = ExceptionUtils.getRootCause(e);
            String errorMessage = String.format(
                    "Error while retrieving filtered users count for the domain: %s with limit: %d and offset: %d. %s",
                    domainName, limit, offset, rootCause != null ? rootCause.getMessage() : e.getMessage());
            if (log.isDebugEnabled()) {
                log.debug(errorMessage, e);
            }
            if (e instanceof UserStoreClientException || rootCause instanceof UserStoreClientException) {
                throw new BadRequestException(errorMessage);
            } else {
                throw new Exception(errorMessage, e);
            }
        }
    }

    /**
     * Enhanced: Get filtered user count with pagination (for LDAP/AD stores)
     * Loops through all pages to get accurate total count
     */
    private long getFilteredUserCountWithPagination(Condition condition, String domainName,
                                                    AbstractUserStoreManager userStoreManager)
            throws Exception {

        long totalCount = 0;
        int offset = 0;
        int limit = LDAP_PAGE_SIZE;
        int iteration = 0;

        if (log.isDebugEnabled()) {
            log.debug("Starting paginated count for LDAP domain: " + domainName);
        }

        while (iteration < MAX_LDAP_ITERATIONS) {
            try {
                // Get count for this page
                int pageCount = getFilteredUserCountFromSingleDomain(condition,
                        offset, limit, domainName, userStoreManager);

                if (log.isDebugEnabled()) {
                    log.debug(String.format("Domain: %s, Offset: %d, Limit: %d, Page Count: %d",
                            domainName, offset, limit, pageCount));
                }

                if (pageCount == 0) {
                    // No more users, exit loop
                    break;
                }

                totalCount += pageCount;

                // If we got fewer users than the limit, we've reached the end
                if (pageCount < limit) {
                    break;
                }

                // Move to next page
                offset += limit;
                iteration++;

            } catch (Exception e) {
                log.error(String.format("Error getting users at offset %d for domain %s",
                        offset, domainName), e);
                // Don't throw - return what we've counted so far
                break;
            }
        }

        if (iteration >= MAX_LDAP_ITERATIONS) {
            log.warn(String.format("Reached maximum iteration limit (%d) for domain %s. " +
                    "Count may be incomplete.", MAX_LDAP_ITERATIONS, domainName));
        }

        log.info(String.format("Paginated total count for domain '%s': %d (iterations: %d)",
                domainName, totalCount, iteration));

        return totalCount;
    }

    private boolean isRemoveDuplicateUsersInUsersResponseEnabled() {

        String removeDuplicateUsersInUsersResponse =
                IdentityUtil.getProperty(UsageCollectorConstants.SCIM_2_REMOVE_DUPLICATE_USERS_IN_USERS_RESPONSE);
        if (StringUtils.isNotBlank(removeDuplicateUsersInUsersResponse)) {
            return Boolean.parseBoolean(removeDuplicateUsersInUsersResponse);
        }
        return false;
    }

    public static boolean isConsiderMaxLimitForTotalResultEnabled() {

        return Boolean.parseBoolean(IdentityUtil
                .getProperty(UsageCollectorConstants.SCIM_ENABLE_CONSIDER_MAX_LIMIT_FOR_TOTAL_RESULT));
    }

}
