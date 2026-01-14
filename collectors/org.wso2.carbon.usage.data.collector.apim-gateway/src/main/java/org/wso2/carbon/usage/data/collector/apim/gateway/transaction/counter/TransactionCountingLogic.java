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

import org.apache.synapse.MessageContext;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.wso2.carbon.usage.data.collector.apim.gateway.internal.GatewayConstants;

/**
 * Logic for counting transactions based on Synapse message flow.
 * Handles different message flow directions and transport types.
 */
public class TransactionCountingLogic {

    public static int handleRequestInFlow(MessageContext messageContext) {
        if (messageContext != null) {
            org.apache.axis2.context.MessageContext axis2MessageContext = null;
            if (messageContext instanceof Axis2MessageContext) {
                axis2MessageContext = ((Axis2MessageContext) messageContext).getAxis2MessageContext();
            }
            if (axis2MessageContext != null) {
                // Checking if the message is inbound
                Object isInbound = messageContext.getProperty(GatewayConstants.IS_INBOUND);
                if (isInbound instanceof Boolean && (Boolean) isInbound) {
                    return 1;
                }

                // Setting this property to identify request-response pairs
                messageContext.setProperty(GatewayConstants.IS_THERE_ASSOCIATED_INCOMING_REQUEST, true);

                // Counting message received via an open WebSocket
                String transport = axis2MessageContext.getIncomingTransportName();
                if (transport != null &&
                        (transport.equals(GatewayConstants.TRANSPORT_WS) ||
                        transport.equals(GatewayConstants.TRANSPORT_WSS))) {
                    return 1;
                }
            }
        }
        return 0;
    }

    public static int handleRequestOutFlow(MessageContext messageContext) {
        if (messageContext == null) {
            return 0;
        }
        Object isThereAnAssociatedIncomingRequest = messageContext.getProperty(
                GatewayConstants.IS_THERE_ASSOCIATED_INCOMING_REQUEST);

        // Counting outgoing messages that are not related to any request-response pair
        if (isThereAnAssociatedIncomingRequest == null) {
            return 1;
        }
        return 0;
    }

    public static int handleResponseInFlow(MessageContext messageContext) {
        if (messageContext == null) {
            return 0;
        }
        return 0;
    }

    public static int handleResponseOutFlow(MessageContext messageContext) {
        if (messageContext == null) {
            return 0;
        }
        Object isThereAnAssociatedIncomingRequest = messageContext.getProperty(
                GatewayConstants.IS_THERE_ASSOCIATED_INCOMING_REQUEST);

        // Counting request-response pairs
        if (isThereAnAssociatedIncomingRequest instanceof Boolean) {
            return 1;
        }
        return 0;
    }
}

