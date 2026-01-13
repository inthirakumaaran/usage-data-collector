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

package org.wso2.carbon.usage.data.collector.apim.gateway.internal;

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
import org.wso2.carbon.usage.data.collector.apim.gateway.transaction.counter.TransactionCountHandler;

/**
 * OSGi service component for APIM Gateway usage data collection.
 * This component manages transaction counting functionality.
 *
 * Only activates in Gateway profile where Synapse dependencies are available.
 *
 * Architecture:
 * - Registers TransactionCountHandler with Publisher
 * - Handler intercepts Synapse message flows
 * - Aggregates and publishes transaction counts hourly
 */
@Component(
    name = "org.wso2.carbon.usage.data.collector.apim.gateway",
    immediate = true
)
public class GatewayServiceComponent {

    private static final Log log = LogFactory.getLog(GatewayServiceComponent.class);

    private Publisher publisher;

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

        // Register publisher with TransactionCountHandler for transaction counting
        TransactionCountHandler.registerPublisher(service);

        if (log.isDebugEnabled()) {
            log.debug("Publisher bound to Gateway service component");
        }
    }

    /**
     * Unbind the Publisher service.
     */
    protected void unsetPublisher(Publisher service) {
        // Unregister publisher from TransactionCountHandler
        TransactionCountHandler.unregisterPublisher(service);

        this.publisher = null;

        if (log.isDebugEnabled()) {
            log.debug("Publisher unbound from Gateway service component");
        }
    }

    @Activate
    protected void activate(ComponentContext context) {
        try {
            if (publisher == null) {
                if(log.isDebugEnabled()) {
                    log.warn("Publisher not available - Gateway transaction counting may not work properly");
                }
            }
        } catch (Exception e) {
            if(log.isDebugEnabled()) {
                log.error("Error during activation of APIM Gateway Usage Data Collector", e);
            }
        }
    }

    @Deactivate
    protected void deactivate(ComponentContext context) {
        if (log.isDebugEnabled()) {
            log.debug("APIM Gateway Usage Data Collector deactivated successfully");
        }
    }
}

