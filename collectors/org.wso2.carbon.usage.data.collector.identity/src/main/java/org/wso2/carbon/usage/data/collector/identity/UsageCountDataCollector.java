/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com) All Rights Reserved.
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
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.usage.data.collector.common.publisher.api.model.ApiRequest;
import org.wso2.carbon.usage.data.collector.common.publisher.api.model.ApiResponse;
import org.wso2.carbon.usage.data.collector.common.publisher.api.model.UsageCount;
import org.wso2.carbon.usage.data.collector.common.util.MetaInfoHolder;
import org.wso2.carbon.usage.data.collector.identity.metering.common.CountType;
import org.wso2.carbon.usage.data.collector.identity.metering.common.UsageTrackingException;
import org.wso2.carbon.usage.data.collector.identity.metering.common.cache.GenericCounterCache;
import org.wso2.carbon.usage.data.collector.identity.metering.mau.MAUCacheManager;
import org.wso2.carbon.usage.data.collector.identity.metering.mau.MAUDataAccessObject;
import org.wso2.carbon.usage.data.collector.identity.publisher.PublisherImp;
import org.wso2.carbon.usage.data.collector.identity.util.ClusteringUtil;
import org.wso2.carbon.usage.data.collector.identity.util.UsageCollectorConstants;

import java.util.List;
import java.util.Map;

/**
 * Collector for the in-memory usage counters populated by the metering event handlers.
 * <p>
 * On each scheduled cycle it drains the M2M and agent counter caches and, on the cluster
 * coordinator only, computes the monthly distinct-user (MAU) count from {@code IDN_MAU_COUNT}.
 * Each value is published as a {@code UsageCount} carrying this node's id through the common
 * usage-data receiver (endpoint {@code usage-counts}); the receiver aggregates per-node
 * counts across the cluster. No usage counts are persisted to a local table.
 * <p>
 * M2M and agent caches are drained on <em>every</em> node so per-node counts are never lost;
 * MAU is published only by the coordinator because distinct-user counts cannot be summed
 * across nodes without over-counting.
 */
public class UsageCountDataCollector implements UsageDataCollectorInterface {

    private static final Log LOG = LogFactory.getLog(UsageCountDataCollector.class);

    private static final String USAGE_COUNT_ENDPOINT = "usage-counts";

    private final MAUDataAccessObject mauDao;
    private final Map<CountType, GenericCounterCache> counterCaches;
    private final PublisherImp publisher;

    // Last month (MMYYYY) whose final MAU count was published. Guards the once-per-month publish.
    private String lastPublishedMauMonth = "";

    public UsageCountDataCollector(MAUDataAccessObject mauDao,
                                   Map<CountType, GenericCounterCache> counterCaches,
                                   PublisherImp publisher) {

        this.mauDao = mauDao;
        this.counterCaches = counterCaches;
        this.publisher = publisher;
    }

    @Override
    public void collectAndPublish() {

        if (!MetaInfoHolder.isInitialized()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Skipping usage count publish: MetaInfoHolder is not initialized");
            }
            return;
        }

        publishCounterCaches();
        publishMonthlyActiveUsers();
    }

    /**
     * Drains every M2M and agent counter cache on this node and publishes the per-type total
     * (summed across tenants) as a {@link UsageCount}.
     */
    private void publishCounterCaches() {

        for (Map.Entry<CountType, GenericCounterCache> entry : counterCaches.entrySet()) {
            CountType type = entry.getKey();
            Map<String, Long> snapshot = entry.getValue().snapshotAndReset();
            long total = 0L;
            for (Long value : snapshot.values()) {
                total += value;
            }
            if (total > 0) {
                publishMetric(type, total);
            }
        }
    }

    /**
     * Publishes the monthly-active-user count from {@code IDN_MAU_COUNT}. Runs only on the cluster
     * coordinator (or a standalone node) so the distinct count backed by the shared table is
     * published exactly once.
     * <p>
     * MAU is a monthly metric: by default the <em>previous</em> (now complete) month's final
     * distinct count is published once, on the first cycle of the new month. When the
     * {@code MauIntervalPublish} flag is set (non-production) the current month's running count is
     * published on every cycle instead.
     */
    private void publishMonthlyActiveUsers() {

        if (ClusteringUtil.isClusteringEnabled() && !ClusteringUtil.isCoordinator()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Not the cluster coordinator; skipping MAU publish on this node.");
            }
            return;
        }

        if (isMauIntervalPublishEnabled()) {
            // Non-production: publish the current month's running distinct count every cycle.
            publishMauForMonth(MAUCacheManager.currentMonthYear());
            return;
        }

        // Production: publish the previous (completed) month's final count exactly once.
        String previousMonth = MAUCacheManager.previousMonthYear();
        if (previousMonth.equals(lastPublishedMauMonth)) {
            return;
        }
        publishMauForMonth(previousMonth);
        // Mark handled regardless of the count so the distinct query is not repeated every cycle.
        lastPublishedMauMonth = previousMonth;
    }

    private void publishMauForMonth(String monthYear) {

        try {
            List<String> tenants = mauDao.getDistinctTenants(monthYear);
            long total = 0L;
            for (String tenant : tenants) {
                total += mauDao.countDistinctUsers(tenant, monthYear);
            }
            if (total > 0) {
                publishMetric(CountType.MAU, total);
            }
        } catch (UsageTrackingException e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Failed to compute MAU count for month " + monthYear, e);
            }
        }
    }

    private static boolean isMauIntervalPublishEnabled() {

        String property = IdentityUtil.getProperty(UsageCollectorConstants.USAGE_COUNT_COLLECTOR_MAU_INTERVAL_PUBLISH);
        return Boolean.parseBoolean(property != null ? property.trim() : null);
    }

    private void publishMetric(CountType type, long count) {

        String nodeId = MetaInfoHolder.getNodeId();
        String product = MetaInfoHolder.getProduct();
        UsageCount data = new UsageCount(nodeId, product, count, type.getValue());
        ApiRequest request = new ApiRequest.Builder()
                .withEndpoint(USAGE_COUNT_ENDPOINT)
                .withData(data)
                .build();
        ApiResponse response = publisher.callReceiverApi(request);

        if (response == null || !response.isSuccess()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Failed to publish usage count: type=" + type.getValue() + " count=" + count);
            }
            return;
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("Published usage count: type=" + type.getValue() + " count=" + count);
        }
    }
}
