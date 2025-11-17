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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.identity.organization.management.service.OrganizationManager;

import java.util.List;

/**
 * Calculator for counting B2B organizations in tenants
 */
public class OrganizationCounter {

    private static final Log log = LogFactory.getLog(OrganizationCounter.class);

    private final OrganizationManager organizationManager;

    public OrganizationCounter(OrganizationManager organizationManager) {

        this.organizationManager = organizationManager;
    }

    /**
     * Count B2B organizations in a tenant (child organizations under root)
     * @param tenantDomain The tenant domain
     * @return Number of B2B organizations
     */
    public int countB2BOrganizations(String tenantDomain) {

        if (tenantDomain == null || tenantDomain.trim().isEmpty()) {
            log.debug("Invalid tenant domain provided");
            return 0;
        }

        try {
            String rootOrgId = organizationManager.resolveOrganizationId(tenantDomain);

            if (rootOrgId == null) {
                log.debug("No root organization found for tenant: " + tenantDomain);
                return 0;
            }

            List<String> childOrgIds = organizationManager.getChildOrganizationsIds(rootOrgId, true);

            return (childOrgIds != null) ? childOrgIds.size() : 0;

        } catch (NullPointerException e) {
            log.debug("No root organization found for tenant: " + tenantDomain);
            return 0;
        } catch (Exception e) {
            log.error("Error calculating organization count for: " + tenantDomain, e);
            return 0;
        }
    }
}