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

package org.wso2.carbon.identity.usage.data.collector;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class UsageDataCollectorTask implements Runnable {

    private static final Log log = LogFactory.getLog(UsageDataCollectorTask.class);

    private UsageDataCollector collector;

    public UsageDataCollectorTask(UsageDataCollector collector) {
        this.collector = collector;
    }

    @Override
    public void run() {
        try {
            if(log.isDebugEnabled()) {
                log.debug("Executing deployment data collection task");
            }
            collector.collectAndPublish();
        } catch (Exception e) {
            log.error("Error executing deployment data collection task", e);
            // Don't propagate exception - let scheduler continue
        }
    }
}

