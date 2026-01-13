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

package org.wso2.carbon.usage.data.collector.mi.transaction.counter;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.AbstractExtendedSynapseHandler;
import org.apache.synapse.MessageContext;
import org.wso2.carbon.usage.data.collector.mi.transaction.aggregator.TransactionAggregator;
import org.wso2.carbon.usage.data.collector.mi.transaction.publisher.TransactionPublisher;

/**
 * Synapse handler for counting and aggregating transaction data.
 * This handler is managed by TransactionCountHandlerComponent through OSGi.
 */
public class TransactionCountHandler extends AbstractExtendedSynapseHandler {
    private static final Log log = LogFactory.getLog(TransactionCountHandler.class);
    private TransactionAggregator transactionAggregator;
    private TransactionPublisher publisher;
    private volatile boolean enabled = false;
    private static TransactionCountHandler instance;

    public TransactionCountHandler() {
        if (log.isDebugEnabled()) {
            log.debug("TransactionCountHandler instantiated");
        }
        instance = this;
    }

    /**
     * Registers a TransactionPublisher with this handler.
     * This is called statically by TransactionPublisherImpl during activation.
     */
    public static void registerTransactionPublisher(TransactionPublisher publisher) {
        if (instance != null) {
            instance.setPublisher(publisher);
        }
    }

    /**
     * Unregisters a TransactionPublisher from this handler.
     * This is called statically by TransactionPublisherImpl during deactivation.
     */
    public static void unregisterTransactionPublisher(TransactionPublisher publisher) {
        if (instance != null) {
            instance.unsetPublisher(publisher);
        }
    }

    /**
     * Sets the TransactionPublisher for this handler.
     * This is called by TransactionCountHandlerComponent.
     */
    public void setPublisher(TransactionPublisher publisher) {
        this.publisher = publisher;
        if (publisher != null) {
            this.transactionAggregator = TransactionAggregator.getInstance();
            if (this.transactionAggregator != null) {
                synchronized (this.transactionAggregator) {
                    if (!this.transactionAggregator.isEnabled()) {
                        this.transactionAggregator.init(publisher);
                    }
                }
                this.enabled = true;
                if (log.isDebugEnabled()) {
                    log.debug("TransactionCountHandler initialized with publisher");
                }
            }
        }
    }

    /**
     * Unsets the TransactionPublisher.
     * This is called by TransactionCountHandlerComponent.
     */
    public void unsetPublisher(TransactionPublisher publisher) {
        if (this.publisher == publisher) {
            this.publisher = null;
            this.enabled = false;
            if (log.isDebugEnabled()) {
                log.debug("TransactionCountHandler unregistered from publisher");
            }
        }
    }

    @Override
    public boolean handleServerInit() {
        return true;
    }

    @Override
    public boolean handleRequestInFlow(MessageContext messageContext) {
        if (!enabled) {
            return true;
        }
        int tCount = TransactionCountingLogic.handleRequestInFlow(messageContext);
        if(tCount > 0) {
            if (transactionAggregator != null && transactionAggregator.isEnabled()) {
                transactionAggregator.addTransactions(tCount);
            }
        }
        return true;
    }

    @Override
    public boolean handleServerShutDown() {
        if (log.isDebugEnabled()) {
            log.debug("Shutting down Transaction Counter...");
        }
        // Clean up resources
        if (transactionAggregator != null && transactionAggregator.isEnabled()) {
            transactionAggregator.shutdown();
        }
        if (log.isDebugEnabled()) {
            log.debug("Transaction Counter shutdown completed");
        }
        return true;
    }

    @Override
    public boolean handleRequestOutFlow(MessageContext messageContext) {
        if (!enabled) {
            return true;
        }
        int tCount = TransactionCountingLogic.handleRequestOutFlow(messageContext);
        if(tCount > 0) {
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
        if(tCount > 0) {
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
        if(tCount > 0) {
            if (transactionAggregator != null && transactionAggregator.isEnabled()) {
                transactionAggregator.addTransactions(tCount);
            }
        }
        return true;
    }

    @Override
    public boolean handleArtifactDeployment(String artifactName, String artifactType, String artifactPath) {
        return true;
    }

    @Override
    public boolean handleArtifactUnDeployment(String artifactName, String artifactType, String artifactPath) {
        return true;
    }

    @Override
    public boolean handleError(MessageContext messageContext) {
        return true;
    }
}
