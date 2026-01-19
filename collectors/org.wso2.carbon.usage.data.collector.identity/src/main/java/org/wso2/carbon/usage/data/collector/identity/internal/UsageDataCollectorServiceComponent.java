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

package org.wso2.carbon.usage.data.collector.identity.internal;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.Version;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.wso2.carbon.core.clustering.api.CoordinatedActivity;
import org.wso2.carbon.identity.organization.management.service.OrganizationManager;
import org.wso2.carbon.usage.data.collector.identity.UsageDataCollector;
import org.wso2.carbon.usage.data.collector.identity.UsageDataCollectorScheduler;
import org.wso2.carbon.usage.data.collector.identity.UsageDataCollectorWithoutB2B;
import org.wso2.carbon.usage.data.collector.identity.publisher.PublisherImp;
import org.wso2.carbon.usage.data.collector.identity.util.ClusteringUtil;
import org.wso2.carbon.user.core.service.RealmService;
import org.wso2.carbon.utils.ConfigurationContextService;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Manages the lifecycle and scheduling of usage data collection.
 */
@Component(
        name = "org.wso2.carbon.usage.data.collector.identity",
        immediate = true
)
public class UsageDataCollectorServiceComponent {

    private static final Log LOG = LogFactory.getLog(UsageDataCollectorServiceComponent.class);

    private static final long SHUTDOWN_TIMEOUT_SECONDS = 10;
    private final AtomicBoolean hasRunUsageCollection = new AtomicBoolean(false);
    private static final String EXPECTED_COMPONENT_NAME = "org.wso2.carbon.identity.core";
    private static final String VERSION_SUPPORTED_IN_7_PLUS = "7.0.0";

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> scheduledTask;
    private BundleContext bundleContext;
    private ServiceRegistration<?> publisherServiceRegistration;
    private UsageDataCollectorScheduler schedulerNew;

    @Activate
    protected void activate(ComponentContext context) {

        try {

            this.bundleContext = context.getBundleContext();
            // Register 700 and above version related services.
            consumeServicesFor700PlusVersion(context);

            boolean isClusteringEnabled = ClusteringUtil.isClusteringEnabled();

            if (isClusteringEnabled) {
                LOG.debug("Clustering detected. Co-ordinator listener is enabled for usage data collectors.");
                registerDataCollectionAsCoordinatorActivity();
            } else {
                LOG.debug("Standalone setup detected. Usage data collectors starts immediately.");
                runUsageCollectionTask();
            }

            // Register the publisher.
            publisherServiceRegistration = bundleContext.registerService(
                    org.wso2.carbon.usage.data.collector.common.publisher.api.Publisher.class.getName(),
                    new PublisherImp(),
                    null);

            LOG.debug("UsageDataCollectorServiceComponent activated successfully");

        } catch (Exception e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Error activating UsageDataCollectorServiceComponent", e);
            }
            cleanup();
        }
    }

    @Deactivate
    protected void deactivate(ComponentContext context) {

        cleanup();
        UsageDataCollectorDataHolder.getInstance().setOrganizationManager(null);
        LOG.debug("Organization mgt service is unset in usage data collector service.");

        LOG.debug("UsageDataCollectorServiceComponent deactivated successfully");
    }

    private void cleanup() {

        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }

        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Stop scheduler
        if (schedulerNew != null) {
            try {
                schedulerNew.stopScheduledTask();
            } catch (RuntimeException e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Error while stopping UsageDataCollectorScheduler", e);
                }
            }
        }

        if (publisherServiceRegistration != null) {
            try {
                publisherServiceRegistration.unregister();
            } catch (IllegalStateException e) {
                // Service already unregistered
            }
        }
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
            name = "configuration.context.service",
            service = ConfigurationContextService.class,
            cardinality = ReferenceCardinality.MANDATORY,
            policy = ReferencePolicy.DYNAMIC,
            unbind = "unsetConfigurationContextService"
    )
    protected void setConfigurationContextService(ConfigurationContextService configContextService) {

        UsageDataCollectorDataHolder.getInstance().setConfigurationContextService(configContextService);
    }

    protected void unsetConfigurationContextService(ConfigurationContextService configContextService) {

        UsageDataCollectorDataHolder.getInstance().setConfigurationContextService(null);
    }

    @Reference(
            name = "receiver",
            service = org.wso2.carbon.usage.data.collector.common.receiver.Receiver.class,
            cardinality = ReferenceCardinality.MANDATORY,
            policy = ReferencePolicy.DYNAMIC,
            unbind = "unsetReceiver"
    )
    protected void setReceiver(org.wso2.carbon.usage.data.collector.common.receiver.Receiver receiver) {

        UsageDataCollectorDataHolder.getInstance().setReceiver(receiver);
    }

    protected void unsetReceiver(org.wso2.carbon.usage.data.collector.common.receiver.Receiver receiver) {

        UsageDataCollectorDataHolder.getInstance().setReceiver(null);
    }

    /**
     * Consume services manually if they available in the runtime.
     *
     * @param bundleContext Bundle Context.
     * @param serviceClass  Expected service class name.
     * @param setter        Service Data Holder setter method.
     * @param serviceName   Expected service name.
     */
    private <T> void consumeService(BundleContext bundleContext, Class<T> serviceClass,
                                    Consumer<T> setter, String serviceName) {

        ServiceReference<T> serviceReference = bundleContext.getServiceReference(serviceClass);
        if (serviceReference != null) {
            T service = bundleContext.getService(serviceReference);
            if (service != null) {
                setter.accept(service);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Successfully registered " + serviceName + " Service.");
                }
            } else {
                LOG.debug(serviceName + " Service reference is available, but service instance is null.");
            }
        } else {
            LOG.debug(serviceName + " Service is not available.");
        }
    }

    /**
     * Register coordinator activity for usage data collection.
     */
    private void registerDataCollectionAsCoordinatorActivity() {

        try {
            CoordinatedActivity coordinatorListener = new CoordinatedActivity() {
                @Override
                public void execute() {
                    if (hasRunUsageCollection.compareAndSet(false, true)) {
                        LOG.debug("This node is the coordinator and will run the collectors.");
                        runUsageCollectionTask();
                    } else {
                        LOG.debug("Usage collection already executed, skipping duplicate execution.");
                    }
                }
            };

            // Register the coordinator activity.
            bundleContext.registerService(
                    CoordinatedActivity.class.getName(),
                    coordinatorListener,
                    null
            );

            // Run usage collection if this node is already the coordinator when the listener registers.
            if (ClusteringUtil.isCoordinator()) {
                if (hasRunUsageCollection.compareAndSet(false, true)) {
                    LOG.debug("Node is already coordinator. Running initial usage data collection.");
                    runUsageCollectionTask();
                }
            }
        } catch (Exception e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Error registering coordinator activity", e);
            }
        }
    }

    private void runUsageCollectionTask() {

        if (UsageDataCollectorDataHolder.getInstance().getB2bSupportedISVersion()) {
            UsageDataCollector collectorService = new UsageDataCollector();
            schedulerNew = new UsageDataCollectorScheduler(collectorService);
        } else {
            UsageDataCollectorWithoutB2B collectorWithoutB2B = new UsageDataCollectorWithoutB2B();
            schedulerNew = new UsageDataCollectorScheduler(collectorWithoutB2B);
        }
        schedulerNew.startScheduledTask();
    }

    /**
     * This method check for the expected component version in the 7.0.0 to proceed with binding new services.
     *
     * @param context Component context.
     */
    private void consumeServicesFor700PlusVersion(ComponentContext context) {

        BundleContext bundleContext = context.getBundleContext();
        Bundle[] bundles = bundleContext.getBundles();

        for (Bundle bundle : bundles) {
            if (EXPECTED_COMPONENT_NAME.equals(bundle.getSymbolicName())) {
                Version version = bundle.getVersion();
                if (version.compareTo(Version.parseVersion(VERSION_SUPPORTED_IN_7_PLUS)) >= 0) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug(EXPECTED_COMPONENT_NAME + " is " + version + " - Proceeding with service binding.");
                    }
                    UsageDataCollectorDataHolder.getInstance().isB2BSupportedISVersion(true);
                    consumeService(bundleContext,
                            OrganizationManager.class,
                            UsageDataCollectorDataHolder.getInstance()::setOrganizationManager,
                            "Organization Manager");
                } else {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug(EXPECTED_COMPONENT_NAME + " is " + version + " - Skipping service binding.");
                    }
                }
                break;
            }
        }
    }
}
