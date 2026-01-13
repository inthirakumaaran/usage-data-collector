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

package org.wso2.carbon.usage.data.collector.mi.internal;

import org.wso2.carbon.usage.data.collector.common.receiver.Receiver;

/**
 * Data holder for MI usage data collector.
 */
public class UsageDataCollectorDataHolder {

    private static final UsageDataCollectorDataHolder instance = new UsageDataCollectorDataHolder();

    private Receiver receiver;

    private UsageDataCollectorDataHolder() {
    }

    public static UsageDataCollectorDataHolder getInstance() {
        return instance;
    }

    public Receiver getReceiver() {
        return receiver;
    }

    public void setReceiver(Receiver receiver) {
        this.receiver = receiver;
    }
}
