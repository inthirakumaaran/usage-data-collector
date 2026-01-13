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

package org.wso2.carbon.usage.data.collector.common.internal;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.wso2.carbon.usage.data.collector.common.collector.DeploymentDataCollector;
import org.wso2.carbon.usage.data.collector.common.collector.DeploymentDataCollectorTask;
import org.wso2.carbon.usage.data.collector.common.collector.MetaInformationPublisher;
import org.wso2.carbon.usage.data.collector.common.publisher.api.Publisher;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * OSGi service component that manages usage data collection.
 * This component injects Publisher and uses it directly for all collectors.
 */
@Component(
    name = "org.wso2.carbon.usage.data.collector.common",
    immediate = true
)
public class UsageDataCollectorServiceComponent {

    private static final Log log = LogFactory.getLog(UsageDataCollectorServiceComponent.class);

    // Hardcoded configuration for scheduler
    private static final long INITIAL_DELAY_SECONDS = 600;
    private static final long INTERVAL_SECONDS = 3600;
    private static final long META_INFO_PUBLISH_DELAY_SECONDS = 300;

    private ScheduledExecutorService executorService;
    private ScheduledFuture<?> scheduledTask;
    private ScheduledFuture<?> metaInfoPublishTask;
    private Publisher publisher;

    /**
     * Bind the Publisher.
     */
    @Reference(
        name = "publisher",
        service = Publisher.class,
        cardinality = ReferenceCardinality.MANDATORY,
        policy = ReferencePolicy.DYNAMIC,
        unbind = "unsetPublisher"
    )
    protected void setPublisher(Publisher service) {
        this.publisher = service;
        if (log.isDebugEnabled()) {
            log.debug("Publisher service bound to UsageDataCollectorServiceComponent");
        }
    }

    /**
     * Unbind the Publisher.
     */
    protected void unsetPublisher(Publisher service) {
        this.publisher = null;
    }

    @Activate
    protected void activate(ComponentContext context) {
        try {
            if (publisher == null) {
                log.error("Publisher not available - cannot start usage data collector");
                return;
            }
            
            if (log.isDebugEnabled()) {
                log.debug("Activating Usage Data Collector Service Component");
            }

            // Initialize scheduler for both meta information publishing and deployment data collection
            executorService = Executors.newScheduledThreadPool(2, new ThreadFactory() {
                private int counter = 0;
                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r, "UsageDataCollector-Thread-" + (++counter));
                    thread.setDaemon(true);
                    return thread;
                }
            });

            // Schedule meta information publishing after 5 minutes (one-time task)
            // This prevents HTTP retries from delaying server availability and allows system to stabilize
            metaInfoPublishTask = executorService.schedule(() -> {
                try {
                    MetaInformationPublisher metaInfoPublisher = new MetaInformationPublisher(publisher);
                    metaInfoPublisher.publishAtStartup();
                    if (log.isDebugEnabled()) {
                        log.debug("Meta information published successfully after 5 minute delay");
                    }
                } catch (Exception e) {
                    if(log.isDebugEnabled()) {
                        log.error("Failed to publish meta information at startup (non-fatal)", e);
                    }
                    // Non-fatal - server continues to start, meta info will be in payloads anyway
                }
            }, META_INFO_PUBLISH_DELAY_SECONDS, TimeUnit.SECONDS);

            // Create deployment data collector with publisher
            // Note: Meta information is included in every payload using cached values from MetaInfoHolder
            DeploymentDataCollector collector = new DeploymentDataCollector(publisher);


            // Schedule the task
            scheduledTask = executorService.scheduleAtFixedRate(
                new DeploymentDataCollectorTask(collector),
                INITIAL_DELAY_SECONDS,
                INTERVAL_SECONDS,
                TimeUnit.SECONDS
            );
        } catch (Exception e) {
            if(log.isDebugEnabled()) {
                log.error("Failed to activate Usage Data Collector Service Component", e);
            }
        }
    }

    @Deactivate
    protected void deactivate(ComponentContext context) {
        if (log.isDebugEnabled()) {
            log.debug("UsageDataCollectorServiceComponent deactivating - stopping scheduled tasks");
        }
        
        // Stop the schedulers
        if (metaInfoPublishTask != null) {
            metaInfoPublishTask.cancel(false);
        }

        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }

        if (executorService != null) {
            executorService.shutdown();
            try {
                // Wait for tasks to complete with a reasonable timeout
                if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                    if (log.isDebugEnabled()) {
                        log.debug("Executor service did not terminate in time, forcing shutdown");
                    }
                    executorService.shutdownNow();
                    // Wait a bit more for forced shutdown
                    if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                        if (log.isDebugEnabled()) {
                            log.debug("Executor service did not terminate after forced shutdown");
                        }
                    }
                }
            } catch (InterruptedException e) {
                if (log.isDebugEnabled()) {
                    log.debug("Interrupted while waiting for executor service shutdown");
                }
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        if (log.isDebugEnabled()) {
            log.debug("UsageDataCollectorServiceComponent deactivated successfully");
        }
    }
}

