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

package org.wso2.carbon.usage.data.collector.apim.internal;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.wso2.carbon.usage.data.collector.common.publisher.api.Publisher;
import org.wso2.carbon.usage.data.collector.apim.collector.apicount.ApiCountCollector;
import org.wso2.carbon.usage.data.collector.apim.collector.apicount.ApiCountCollectorTask;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * OSGi service component that manages APIM core usage data collection.
 * This component coordinates:
 * - API count collection (periodic database queries)
 * - Meta information publishing at startup
 *
 * Works in all APIM profiles (control-plane, gateway, traffic-manager).
 * Transaction counting is handled by separate gateway-specific bundle.
 *
 * Architecture follows the common module's solid implementation pattern.
 */
@Component(
    name = "org.wso2.carbon.usage.data.collector.apim",
    immediate = true
)
public class ApimUsageDataCollectorServiceComponent {

    private static final Log log = LogFactory.getLog(ApimUsageDataCollectorServiceComponent.class);

    // Configuration for API count collector scheduler
    private static final long API_COUNT_INITIAL_DELAY_SECONDS = 600;
    private static final long API_COUNT_INTERVAL_SECONDS = 3600; // 1 hour default

    private Publisher publisher;
    private ScheduledExecutorService apiCountExecutorService;
    private ScheduledFuture<?> apiCountScheduledTask;

    /**
     * Bind the Publisher service.
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
    }

    /**
     * Unbind the Publisher service.
     */
    protected void unsetPublisher(Publisher service) {

        this.publisher = null;
    }


    @Activate
    protected void activate(ComponentContext context) {
        try {
            if (publisher == null) {
                if(log.isDebugEnabled()) {
                    log.warn("Publisher not available - APIM usage data collection may not work properly");
                }
                return;
            }

            // Create API count collector with publisher
            ApiCountCollector apiCountCollector = new ApiCountCollector(publisher);

            // Initialize scheduler for API count collection
            apiCountExecutorService = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r, "ApiCountCollector-Thread");
                    thread.setDaemon(true);
                    return thread;
                }
            });

            // Schedule the API count collection task
            apiCountScheduledTask = apiCountExecutorService.scheduleAtFixedRate(
                    new ApiCountCollectorTask(apiCountCollector),
                    API_COUNT_INITIAL_DELAY_SECONDS,
                    API_COUNT_INTERVAL_SECONDS,
                    TimeUnit.SECONDS
            );
        } catch (Exception e) {
            if(log.isDebugEnabled()) {
                log.error("Failed to activate APIM Usage Data Collector Service Component", e);
            }
        }
    }

    @Deactivate
    protected void deactivate() {
        // Stop the API count collector scheduler
        if (apiCountScheduledTask != null) {
            apiCountScheduledTask.cancel(false);
        }

        if (apiCountExecutorService != null) {
            apiCountExecutorService.shutdown();
            try {
                if (!apiCountExecutorService.awaitTermination(60, TimeUnit.SECONDS)) {
                    apiCountExecutorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                apiCountExecutorService.shutdownNow();
                Thread.currentThread().interrupt();
                if(log.isDebugEnabled()) {
                    log.error("Interrupted while shutting down API count collector executor", e);
                }
            }
        }
    }
}

