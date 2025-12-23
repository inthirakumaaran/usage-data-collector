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

package org.wso2.carbon.usage.data.collector.identity;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.usage.data.collector.common.publisher.api.model.ApiRequest;
import org.wso2.carbon.usage.data.collector.common.publisher.api.model.UsageCount;
import org.wso2.carbon.usage.data.collector.common.util.MetaInfoHolder;
import org.wso2.carbon.usage.data.collector.identity.counter.UserCounter;
import org.wso2.carbon.usage.data.collector.identity.internal.UsageDataCollectorDataHolder;
import org.wso2.carbon.usage.data.collector.identity.model.SystemUsage;
import org.wso2.carbon.usage.data.collector.identity.model.TenantUsage;
import org.wso2.carbon.usage.data.collector.identity.publisher.PublisherImp;
import org.wso2.carbon.user.api.Tenant;
import org.wso2.carbon.user.core.service.RealmService;
import org.wso2.carbon.user.core.tenant.TenantManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Main service for collecting usage statistics.
 * Orchestrates the calculator classes to gather system-wide statistics.
 */
public class UsageDataCollector {

    private static final Log LOG = LogFactory.getLog(UsageDataCollector.class);
    public static final String SUPER_TENANT = "carbon.super";
    public static final String TOTAL_USERS = "TOTAL_USERS";
    public static final String TOTAL_B2B_ORGS = "TOTAL_B2B_ORGS";
    public static final String TOTAL_ROOT_ORGS = "TOTAL_ROOT_ORGS";

    private final RealmService realmService;
    private final UserCounter userCountCalculator;
    private final PublisherImp publisher;

    public UsageDataCollector() {

        this.realmService = UsageDataCollectorDataHolder.getInstance().getRealmService();
        this.userCountCalculator = new UserCounter(realmService);
        this.publisher = new PublisherImp();
    }

    /**
     * Collect the metrics and publish them.
     */
    public void collectAndPublish() {

        SystemUsage report = this.collectSystemStatistics();
        publishUsageMetrics(report);
    }

    /**
     * Collect system-wide statistics
     */
    public SystemUsage collectSystemStatistics() {

        SystemUsage usage = new SystemUsage();

        try {
            TenantManager tenantManager = realmService.getTenantManager();
            Tenant[] tenants = tenantManager.getAllTenants();

            // Calculate root tenant count (all tenants including super tenant)
            int rootTenantCount = (tenants != null ? tenants.length : 0) + 1; // +1 for super tenant
            usage.setRootTenantCount(rootTenantCount);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Root Tenant Count: " + rootTenantCount);
            }
            // Prepare list of all tenants
            List<String> allTenantDomains = new ArrayList<>();
            allTenantDomains.add(SUPER_TENANT);

            if (tenants != null) {
                for (Tenant tenant : tenants) {
                    allTenantDomains.add(tenant.getDomain());
                }
            }

            // Calculate B2B organization count and total users
            int totalUsers = 0;

            for (String tenantDomain : allTenantDomains) {
                try {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Processing tenant: " + tenantDomain);
                    }
                    TenantUsage stats = processTenant(tenantDomain);

                    // Add to totals
                    totalUsers += stats.getUserCount();
                } catch (Exception e) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Error processing tenant: " + tenantDomain, e);
                        // Continue with next tenant
                    }
                }
            }
            usage.setTotalUsers(totalUsers);
        } catch (Exception e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Error calculating system statistics", e);
            }
        }

        return usage;
    }

    /**
     * Process a single tenant using the calculator classes
     */
    private TenantUsage processTenant(String tenantDomain) {

        TenantUsage stats = new TenantUsage(tenantDomain);
        // Use UserCountCalculator to count all users in the tenant
        int userCount = userCountCalculator.countUsersInOrganization(tenantDomain);
        stats.setUserCount(userCount);
        return stats;
    }

    private void publishUsageMetrics(SystemUsage report) {

        publishMetric(report.getTotalUsers(), TOTAL_USERS);
        publishMetric(report.getRootTenantCount(), TOTAL_ROOT_ORGS);
    }

    private void publishMetric(int count, String type) {

        if (!MetaInfoHolder.isInitialized()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Skipping metric publish: MetaInfoHolder is not initialized");
            }
            return;
        }
        String nodeId = MetaInfoHolder.getNodeId();
        String product = MetaInfoHolder.getProduct();
        UsageCount data = new UsageCount(nodeId, product, count, type);
        ApiRequest request =  new ApiRequest.Builder()
                .withEndpoint("usage-counts")
                .withData(data)
                .build();
        publisher.callReceiverApi(request);
    }
}
