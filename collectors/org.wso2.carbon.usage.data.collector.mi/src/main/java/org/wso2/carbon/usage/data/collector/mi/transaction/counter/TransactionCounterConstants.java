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

public class TransactionCounterConstants {
    public static final String IS_INBOUND = "isInbound";

    public static final String IS_THERE_ASSOCIATED_INCOMING_REQUEST = "is_there_incoming_request";
    public static final String TRANSPORT_WS = "ws";
    public static final String TRANSPORT_WSS = "wss";

    public static final String SERVER_ID = "serverId";

    // OSGi Component Constants
    public static final String COMPONENT_NAME = "org.wso2.carbon.usage.data.collector.mi.transaction.counter.handler.component";
    public static final String HANDLER_NAME_PROPERTY = "handler.name=TransactionCountHandler";
    public static final String HANDLER_ENABLED_PROPERTY = "handler.enabled=true";
    public static final String TRANSACTION_PUBLISHER_REFERENCE = "transaction.publisher";
}
