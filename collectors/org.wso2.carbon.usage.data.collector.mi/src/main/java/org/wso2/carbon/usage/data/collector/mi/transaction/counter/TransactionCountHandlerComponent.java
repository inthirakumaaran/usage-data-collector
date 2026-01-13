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
import org.apache.synapse.SynapseHandler;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.wso2.carbon.usage.data.collector.mi.transaction.publisher.TransactionPublisher;

import static org.wso2.carbon.usage.data.collector.mi.transaction.counter.TransactionCounterConstants.COMPONENT_NAME;
import static org.wso2.carbon.usage.data.collector.mi.transaction.counter.TransactionCounterConstants.HANDLER_NAME_PROPERTY;
import static org.wso2.carbon.usage.data.collector.mi.transaction.counter.TransactionCounterConstants.HANDLER_ENABLED_PROPERTY;
import static org.wso2.carbon.usage.data.collector.mi.transaction.counter.TransactionCounterConstants.TRANSACTION_PUBLISHER_REFERENCE;

/**
 * OSGi component that registers TransactionCountHandler as a SynapseHandler service.
 * This component will be automatically picked up by DynamicSynapseHandlerRegistrar
 * which listens for SynapseHandler services with dynamic cardinality.
 */
@Component(
        name = COMPONENT_NAME,
        service = SynapseHandler.class,
        immediate = true,
        property = {
                HANDLER_NAME_PROPERTY,
                HANDLER_ENABLED_PROPERTY
        }
)
public class TransactionCountHandlerComponent extends AbstractExtendedSynapseHandler {

    private static final Log log = LogFactory.getLog(TransactionCountHandlerComponent.class);
    
    private TransactionCountHandler handler;
    private TransactionPublisher transactionPublisher;

    @Activate
    protected void activate() {
        try {
            if (log.isDebugEnabled()) {
                log.debug("Activating TransactionCountHandler OSGi component");
            }
            
            // Create the handler instance
            handler = new TransactionCountHandler();
            
            // Register the publisher if available
            if (transactionPublisher != null) {
                handler.setPublisher(transactionPublisher);
                if (log.isDebugEnabled()) {
                    log.debug("TransactionCountHandler registered with TransactionPublisher");
                }
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("TransactionPublisher not available during activation. " +
                        "Handler will be initialized when publisher becomes available.");
                }
            }
            
            if (log.isDebugEnabled()) {
                log.debug("TransactionCountHandler component activated successfully");
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("Error activating TransactionCountHandler component", e);
            }
        }
    }

    @Deactivate
    protected void deactivate() {
        try {
            if (log.isDebugEnabled()) {
                log.debug("Deactivating TransactionCountHandler OSGi component");
            }
            
            // Unregister the publisher
            if (transactionPublisher != null && handler != null) {
                handler.unsetPublisher(transactionPublisher);
                if (log.isDebugEnabled()) {
                    log.debug("TransactionCountHandler unregistered from TransactionPublisher");
                }
            }
            
            handler = null;
            if (log.isDebugEnabled()) {
                log.debug("TransactionCountHandler component deactivated successfully");
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("Error deactivating TransactionCountHandler component", e);
            }
        }
    }

    /**
     * Binds the TransactionPublisher service when it becomes available.
     * 
     * @param publisher the TransactionPublisher service
     */
    @Reference(
            name = TRANSACTION_PUBLISHER_REFERENCE,
            service = TransactionPublisher.class,
            cardinality = ReferenceCardinality.OPTIONAL,
            policy = ReferencePolicy.DYNAMIC,
            unbind = "unsetTransactionPublisher"
    )
    protected void setTransactionPublisher(TransactionPublisher publisher) {
        try {
            if (log.isDebugEnabled()) {
                log.debug("TransactionPublisher service bound to TransactionCountHandler component");
            }
            this.transactionPublisher = publisher;
            
            // If handler is already created, register it with the publisher
            if (handler != null) {
                handler.setPublisher(publisher);
                if (log.isDebugEnabled()) {
                    log.debug("Existing TransactionCountHandler registered with newly bound TransactionPublisher");
                }
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("Error binding TransactionPublisher to TransactionCountHandler", e);
            }
        }
    }

    /**
     * Unbinds the TransactionPublisher service when it becomes unavailable.
     * 
     * @param publisher the TransactionPublisher service
     */
    protected void unsetTransactionPublisher(TransactionPublisher publisher) {
        try {
            if (log.isDebugEnabled()) {
                log.debug("TransactionPublisher service unbound from TransactionCountHandler component");
            }
            
            if (this.transactionPublisher == publisher) {
                if (handler != null) {
                    handler.unsetPublisher(publisher);
                }
                this.transactionPublisher = null;
                if (log.isDebugEnabled()) {
                    log.debug("TransactionCountHandler unregistered from TransactionPublisher");
                }
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("Error unbinding TransactionPublisher from TransactionCountHandler", e);
            }
        }
    }

    // Delegate all SynapseHandler methods to the actual handler instance

    @Override
    public boolean handleServerInit() {
        return handler != null && handler.handleServerInit();
    }

    @Override
    public boolean handleServerShutDown() {
        return handler != null && handler.handleServerShutDown();
    }

    @Override
    public boolean handleRequestInFlow(MessageContext synCtx) {
        return handler != null && handler.handleRequestInFlow(synCtx);
    }

    @Override
    public boolean handleRequestOutFlow(MessageContext synCtx) {
        return handler != null && handler.handleRequestOutFlow(synCtx);
    }

    @Override
    public boolean handleResponseInFlow(MessageContext synCtx) {
        return handler != null && handler.handleResponseInFlow(synCtx);
    }

    @Override
    public boolean handleResponseOutFlow(MessageContext synCtx) {
        return handler != null && handler.handleResponseOutFlow(synCtx);
    }

    @Override
    public boolean handleArtifactDeployment(String artifactName, String artifactType, String artifactPath) {
        return handler != null && handler.handleArtifactDeployment(artifactName, artifactType, artifactPath);
    }

    @Override
    public boolean handleArtifactUnDeployment(String artifactName, String artifactType, String artifactPath) {
        return handler != null && handler.handleArtifactUnDeployment(artifactName, artifactType, artifactPath);
    }

    @Override
    public boolean handleError(MessageContext synCtx) {
        return handler != null && handler.handleError(synCtx);
    }
}
