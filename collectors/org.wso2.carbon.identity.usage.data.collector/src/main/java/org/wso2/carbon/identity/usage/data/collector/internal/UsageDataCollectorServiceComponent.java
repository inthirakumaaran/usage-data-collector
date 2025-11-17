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

package org.wso2.carbon.identity.usage.data.collector.internal;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.wso2.carbon.identity.organization.management.service.OrganizationManager;
import org.wso2.carbon.identity.usage.data.collector.UsageDataCollector;
import org.wso2.carbon.identity.usage.data.collector.model.SystemUsage;
import org.wso2.carbon.user.core.service.RealmService;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


/**
 * OSGi service component for the Deployment Data Collector.
 * Manages the lifecycle and scheduling of deployment data collection.
 */
@Component(
    name = "org.wso2.carbon.deployment.data.collector",
    immediate = true
)
public class UsageDataCollectorServiceComponent {

    private static final Log log = LogFactory.getLog(UsageDataCollectorServiceComponent.class);

    private UsageDataCollector collectorService;
    private ScheduledExecutorService scheduler;

    @Activate
    protected void activate(ComponentContext context) {
        try {
            log.info("========== UsageDataCollectorServiceComponent Activating ==========");
            collectorService = new UsageDataCollector();
            schedulePeriodicReport2();

            log.info("UsageDataCollectorServiceComponent activated successfully");

        } catch (Exception e) {
            log.error("Error activating UsageDataCollectorServiceComponent", e);
        }
    }

    @Deactivate
    protected void deactivate(ComponentContext context) {
        try {
            if (collectorService != null) {
                collectorService.shutdown();
            }
            log.info("UsageDataCollectorServiceComponent deactivated successfully");

        } catch (Exception e) {
            log.error("Error deactivating UsageDataCollectorServiceComponent", e);
        }
    }

    private void logReport(SystemUsage report) {
        if (report == null) {
            log.warn("No statistics report available");
            return;
        }

        log.info("\n" +
                "╔════════════════════════════════════════════════════════════╗\n" +
                "║           USAGE STATISTICS REPORT                          ║\n" +
                "╠════════════════════════════════════════════════════════════╣\n" +
                String.format("║ Root Tenant Count:        %-28d ║\n", report.getRootTenantCount()) +
                String.format("║ Total B2B Organizations:  %-28d ║\n", report.getTotalB2BOrganizations()) +
                String.format("║ Total Users:              %-28d ║\n", report.getTotalUsers()) +
                "╚════════════════════════════════════════════════════════════╝"
        );
    }

    /**
     * Schedule periodic report generation
     */
    private void schedulePeriodicReport2() {

        scheduler = Executors.newSingleThreadScheduledExecutor();

        scheduler.scheduleWithFixedDelay(() -> {
            try {
                log.info("Running scheduled system statistics calculation...");
                long startTime = System.currentTimeMillis();
                SystemUsage report = collectorService.collectSystemStatistics();

                long duration = System.currentTimeMillis() - startTime;
                log.info("Scheduled Report completed in " + duration + "ms");
                // Log formatted report
                logReport(report);
            } catch (Exception e) {
                log.error("Error in scheduled statistics calculation", e);
            }
        }, 0, 2, TimeUnit.MINUTES);  // Initial delay: 12 hours, Period: 12 hours
    }


    @Reference(name = "user.realm.service.default",
            service = RealmService.class,
            cardinality = ReferenceCardinality.MANDATORY,
            policy = ReferencePolicy.DYNAMIC,
            unbind = "unsetRealmService")
    protected void setRealmService(RealmService realmService) {

        UsageDataCollectorDataHolder.getInstance().setRealmService(realmService);
    }

    protected void unsetRealmService(RealmService realmService) {

        UsageDataCollectorDataHolder.getInstance().setRealmService(null);
    }

    @Reference(
            name = "organization.manager",
            service = OrganizationManager.class,
            cardinality = ReferenceCardinality.MANDATORY,
            policy = ReferencePolicy.DYNAMIC,
            unbind = "unsetOrganizationManager"
    )
    protected void setOrganizationManager(OrganizationManager organizationManager) {
        UsageDataCollectorDataHolder.getInstance().setOrganizationManager(organizationManager);
    }

    protected void unsetOrganizationManager(OrganizationManager organizationManager) {
        UsageDataCollectorDataHolder.getInstance().setOrganizationManager(null);
    }
}

