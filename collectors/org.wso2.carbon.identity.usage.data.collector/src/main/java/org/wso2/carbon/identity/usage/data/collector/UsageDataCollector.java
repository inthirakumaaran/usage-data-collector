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

package org.wso2.carbon.identity.usage.data.collector;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.identity.organization.management.service.OrganizationManager;
import org.wso2.carbon.identity.usage.data.collector.counter.OrganizationCounter;
import org.wso2.carbon.user.api.Tenant;
import org.wso2.carbon.user.core.service.RealmService;
import org.wso2.carbon.user.core.tenant.TenantManager;
import org.wso2.carbon.identity.usage.data.collector.counter.UserCounter;
import org.wso2.carbon.identity.usage.data.collector.internal.UsageDataCollectorDataHolder;
import org.wso2.carbon.identity.usage.data.collector.model.SystemUsage;
import org.wso2.carbon.identity.usage.data.collector.model.TenantUsage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Main service for collecting usage statistics
 * Orchestrates the calculator classes to gather system-wide statistics
 */
public class UsageDataCollector {

    private static final Log log = LogFactory.getLog(UsageDataCollector.class);
    private static final int THREAD_POOL_SIZE = 10;
    private static final int PROCESSING_TIMEOUT_MINUTES = 15;
    public static final String SUPER_TENANT = "carbon.super";

    private final RealmService realmService;
    private final OrganizationManager organizationManager;
    private final ExecutorService executorService;
    private final UserCounter userCountCalculator;
    private final OrganizationCounter orgCountCalculator;

    public UsageDataCollector() {

        this.realmService = UsageDataCollectorDataHolder.getInstance().getRealmService();
        this.organizationManager = UsageDataCollectorDataHolder.getInstance().getOrganizationManager();
        this.executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        this.userCountCalculator = new UserCounter(realmService, organizationManager);
        this.orgCountCalculator = new OrganizationCounter(organizationManager);
    }

    public void collectAndPublish() {

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
            if (log.isDebugEnabled()) {
                log.debug("Root Tenant Count: " + rootTenantCount);
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
            AtomicInteger totalB2BOrgs = new AtomicInteger(0);
            AtomicInteger totalUsers = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(allTenantDomains.size());

            for (String tenantDomain : allTenantDomains) {
                executorService.submit(() -> {
                    try {
                        TenantUsage stats = processTenant(tenantDomain);
                        // Add to totals
                        totalB2BOrgs.addAndGet(stats.getB2bOrgCount());
                        totalUsers.addAndGet(stats.getUserCount());

                        if (log.isDebugEnabled()) {
                            log.debug(String.format("✓ Processed: %s | B2B Orgs: %d | Users: %d",
                                    tenantDomain, stats.getB2bOrgCount(), stats.getUserCount()));
                        }
                    } catch (Exception e) {
                        log.error("Error in processing tenant: " + tenantDomain, e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // Wait for all processing to complete
            latch.await(PROCESSING_TIMEOUT_MINUTES, TimeUnit.MINUTES);

            usage.setTotalB2BOrganizations(totalB2BOrgs.get());
            usage.setTotalUsers(totalUsers.get());

            printReport(usage);
        } catch (Exception e) {
            log.error("Error calculating system statistics", e);
        }

        return usage;
    }

    /**
     * Process a single tenant using the calculator classes
     */
    private TenantUsage processTenant(String tenantDomain) {

        TenantUsage stats = new TenantUsage(tenantDomain);

        // Use OrganizationCountCalculator to count B2B organizations
        int b2bOrgCount = orgCountCalculator.countB2BOrganizations(tenantDomain);
        stats.setB2bOrgCount(b2bOrgCount);

        try{
            // Use UserCountCalculator to count all users in the tenant
            int userCount = userCountCalculator.countAllUsersInTenant(tenantDomain);
            stats.setUserCount(userCount);
        } catch (Exception e) {
            log.error("Error calculating user count for: " + tenantDomain, e);
        }
       return stats;
    }

    /**
     * Print the report
     */
    private void printReport(SystemUsage report) {
        log.info("\n========== SYSTEM STATISTICS REPORT ==========");
        log.info("Root Tenant Count: " + report.getRootTenantCount());
        log.info("Total B2B Organizations: " + report.getTotalB2BOrganizations());
        log.info("Total Users: " + report.getTotalUsers());

        if (report.getRootTenantCount() > 0) {
            log.info("Average B2B Orgs per Tenant: " +
                    String.format("%.2f", (double) report.getTotalB2BOrganizations() / report.getRootTenantCount()));
            log.info("Average Users per Tenant: " +
                    String.format("%.2f", (double) report.getTotalUsers() / report.getRootTenantCount()));
        }

        log.info("==============================================\n");
    }

    /**
     * Shutdown executor service
     */
    public void shutdown() {

        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.debug("Usage Data Collector Service is shutdown.");
    }
}