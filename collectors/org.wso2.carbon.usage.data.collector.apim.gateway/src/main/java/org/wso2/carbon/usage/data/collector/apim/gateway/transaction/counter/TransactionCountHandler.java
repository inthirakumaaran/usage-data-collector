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

package org.wso2.carbon.usage.data.collector.apim.gateway.transaction.counter;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.AbstractExtendedSynapseHandler;
import org.apache.synapse.MessageContext;
import org.wso2.carbon.usage.data.collector.common.publisher.api.Publisher;
import org.wso2.carbon.usage.data.collector.apim.gateway.transaction.aggregator.TransactionAggregator;

/**
 * Synapse handler that intercepts API calls and counts transactions.
 * This handler is registered in Synapse handlers configuration and
 * gets called for every API request/response flow.
 *
 * Only works in Gateway profile where Synapse is available.
 */
public class TransactionCountHandler extends AbstractExtendedSynapseHandler {

    private static final Log log = LogFactory.getLog(TransactionCountHandler.class);

    // Static singleton fields - all state is static for consistent singleton behavior
    private static volatile TransactionAggregator transactionAggregator;
    private static volatile Publisher publisher;
    private static volatile boolean enabled = false;
    private static final Object LOCK = new Object();

    /**
     * Register the Publisher and initialize the TransactionAggregator.
     * Called by the OSGi service component when Publisher becomes available.
     */
    public static void registerPublisher(Publisher newPublisher) {
        synchronized (LOCK) {
            publisher = newPublisher;

            if (transactionAggregator == null) {
                transactionAggregator = TransactionAggregator.getInstance();
            }

            // Initialize aggregator if not already initialized
            if (!transactionAggregator.isEnabled()) {
                transactionAggregator.init(newPublisher);
            }

            enabled = true;
        }
    }

    /**
     * Unregister the Publisher and disable transaction counting.
     * Called by the OSGi service component when Publisher is unbound.
     */
    public static void unregisterPublisher(Publisher oldPublisher) {
        synchronized (LOCK) {
            if (publisher == oldPublisher) {
                enabled = false;
                publisher = null;
            }
        }
    }

    /**
     * Public constructor for Synapse handler instantiation.
     * All state is static, so multiple instances share the same singleton state.
     */
    public TransactionCountHandler() {
        // No initialization needed - all state is static
    }

    @Override
    public boolean handleServerInit() {
        // Nothing to implement
        return true;
    }

    @Override
    public boolean handleRequestInFlow(MessageContext messageContext) {
        if (!enabled) {
            return true;
        }
        int tCount = TransactionCountingLogic.handleRequestInFlow(messageContext);
        if (tCount > 0) {
            if (transactionAggregator != null && transactionAggregator.isEnabled()) {
                transactionAggregator.addTransactions(tCount);
            }
        }
        return true;
    }

    @Override
    public boolean handleServerShutDown() {
        // Clean up resources using static fields
        if (transactionAggregator != null && transactionAggregator.isEnabled()) {
            transactionAggregator.shutdown();
        }
        return true;
    }

    @Override
    public boolean handleRequestOutFlow(MessageContext messageContext) {
        if (!enabled) {
            return true;
        }
        int tCount = TransactionCountingLogic.handleRequestOutFlow(messageContext);
        if (tCount > 0) {
            if (transactionAggregator != null && transactionAggregator.isEnabled()) {
                transactionAggregator.addTransactions(tCount);
            }
        }
        return true;
    }

    @Override
    public boolean handleResponseInFlow(MessageContext messageContext) {
        if (!enabled) {
            return true;
        }
        int tCount = TransactionCountingLogic.handleResponseInFlow(messageContext);
        if (tCount > 0) {
            if (transactionAggregator != null && transactionAggregator.isEnabled()) {
                transactionAggregator.addTransactions(tCount);
            }
        }
        return true;
    }

    @Override
    public boolean handleResponseOutFlow(MessageContext messageContext) {
        if (!enabled) {
            return true;
        }
        int tCount = TransactionCountingLogic.handleResponseOutFlow(messageContext);
        if (tCount > 0) {
            if (transactionAggregator != null && transactionAggregator.isEnabled()) {
                transactionAggregator.addTransactions(tCount);
            }
        }
        return true;
    }

    @Override
    public boolean handleArtifactDeployment(String s, String s1, String s2) {
        // Nothing to implement
        return true;
    }

    @Override
    public boolean handleArtifactUnDeployment(String s, String s1, String s2) {
        // Nothing to implement
        return true;
    }

    @Override
    public boolean handleError(MessageContext messageContext) {
        // Nothing to implement
        return true;
    }
}

