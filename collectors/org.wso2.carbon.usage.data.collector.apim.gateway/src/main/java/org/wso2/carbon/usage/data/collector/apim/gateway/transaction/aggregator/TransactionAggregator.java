/*
 *  Copyright (c) 2025, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 LLC. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.usage.data.collector.apim.gateway.transaction.aggregator;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.usage.data.collector.common.publisher.api.Publisher;
import org.wso2.carbon.usage.data.collector.common.publisher.api.PublisherException;
import org.wso2.carbon.usage.data.collector.common.publisher.api.model.ApiRequest;
import org.wso2.carbon.usage.data.collector.common.publisher.api.model.ApiResponse;
import org.wso2.carbon.usage.data.collector.common.publisher.api.model.UsageCount;
import org.wso2.carbon.usage.data.collector.common.util.MetaInfoHolder;
import org.wso2.carbon.usage.data.collector.apim.gateway.internal.GatewayConstants;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Aggregates transaction counts and publishes them hourly.
 * Gateway-specific component that tracks API transaction volume.
 */
public class TransactionAggregator {

    private static final Log log = LogFactory.getLog(TransactionAggregator.class);
    private static volatile TransactionAggregator instance = null;

    private final AtomicLong hourlyTransactionCount = new AtomicLong(0);
    private Publisher publisher;
    private ScheduledExecutorService scheduledExecutorService;
    private long currentHourStartTime;
    private volatile boolean enabled = false;

    private TransactionAggregator() {}

    public static TransactionAggregator getInstance() {
        if (instance == null) {
            synchronized (TransactionAggregator.class) {
                if (instance == null) {
                    instance = new TransactionAggregator();
                }
            }
        }
        return instance;
    }

    public void init(Publisher publisher) {
        if (publisher == null) {
            if(log.isDebugEnabled()) {
                log.warn("Publisher is null. Hourly aggregation will be disabled.");
            }
            return;
        }

        // If executor already exists and is active, skip re-init
        if (scheduledExecutorService != null && !scheduledExecutorService.isShutdown() && enabled) {
            return;
        }

        // If executor exists (whether shut down or not), clean it up before re-init
        if (scheduledExecutorService != null) {
            scheduledExecutorService.shutdownNow();
            try {
                if (!scheduledExecutorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    if(log.isDebugEnabled()) {
                        log.warn("Existing TransactionAggregator executor did not terminate in time");
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if(log.isDebugEnabled()) {
                    log.error("Interrupted while shutting down executor", e);
                }
            }

            scheduledExecutorService = null;
        }

        // Fresh initialization
        this.publisher = publisher;
        this.currentHourStartTime = System.currentTimeMillis();
        this.scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();

        long interval = 60 * 60 * 1000L;
        try {
            scheduledExecutorService.scheduleAtFixedRate(
                    this::publishAndReset,
                    interval,
                    interval,
                    TimeUnit.MILLISECONDS
            );
            this.enabled = true;
        } catch (Exception e) {
            if(log.isDebugEnabled()) {
                log.error("TransactionAggregator: Failed to schedule periodic task", e);
            }
            this.enabled = false;
        }
    }

    public void addTransactions(int count) {
        if (!enabled || count <= 0) {
            return;
        }
        hourlyTransactionCount.addAndGet(count);
    }

    private void publishAndReset() {
        try {
            long count = hourlyTransactionCount.getAndSet(0);
            long hourEndTime = System.currentTimeMillis();

            // Always send transaction count, even when count is zero
            publishTransaction(count, currentHourStartTime, hourEndTime);

            currentHourStartTime = hourEndTime;

        } catch (Exception e) {
            if(log.isDebugEnabled()) {
                log.error("TransactionAggregator: Error while publishing hourly transaction count", e);
            }
        }
    }

    /**
     * Publish transaction count using Publisher.publishToReceiver() which has built-in retry logic.
     */
    private void publishTransaction(long count, long periodStartTime, long periodEndTime) {
        if (publisher == null) {
            if(log.isDebugEnabled()) {
                log.warn("Cannot publish transaction - Publisher not available");
            }
            return;
        }

        try {
            String nodeId = MetaInfoHolder.getNodeId();
            String product = MetaInfoHolder.getProduct();

            UsageCount usageCount = new UsageCount(nodeId, product, count,
                    GatewayConstants.TRANSACTION_TYPE);

            ApiRequest request = new ApiRequest.Builder()
                    .withEndpoint(GatewayConstants.USAGE_COUNT_ENDPOINT)
                    .withData(usageCount)
                    .build();

            // Publisher.publishToReceiver() handles retry logic automatically
            ApiResponse response = publisher.publishToReceiver(request);
        } catch (PublisherException e) {
            if(log.isDebugEnabled()) {
                log.error("Failed to publish transaction count after all retries: " + e.getMessage(), e);
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void shutdown() {
        if (scheduledExecutorService != null) {
            // Publish final report before shutdown
            publishAndReset();

            scheduledExecutorService.shutdown();
            try {
                if (!scheduledExecutorService.awaitTermination(
                        GatewayConstants.SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    scheduledExecutorService.shutdownNow();
                    if(log.isDebugEnabled()) {
                        log.warn("TransactionAggregator forced shutdown");
                    }
                }
            } catch (InterruptedException e) {
                scheduledExecutorService.shutdownNow();
                Thread.currentThread().interrupt();
                if(log.isDebugEnabled()) {
                    log.error("Interrupted while shutting down TransactionAggregator", e);
                }
            }
        }
        enabled = false;
    }
}

