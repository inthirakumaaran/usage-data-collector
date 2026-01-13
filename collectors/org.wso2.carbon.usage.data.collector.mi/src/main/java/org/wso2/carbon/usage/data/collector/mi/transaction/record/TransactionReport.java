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

package org.wso2.carbon.usage.data.collector.mi.transaction.record;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public class TransactionReport {

    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC);

    private final String id;
    private final long totalCount;
    private final String createdTime;

    public TransactionReport(long totalCount) {
        this.id = UUID.randomUUID().toString();
        this.totalCount = totalCount;
        this.createdTime = ISO_FORMATTER.format(Instant.now());
    }

    public String getId() {
        return id;
    }

    public long getTotalCount() {
        return totalCount;
    }

    public String getCreatedTime() {
        return createdTime;
    }

    @Override
    public String toString() {
        return "TransactionReport{" +
                "id='" + id + '\'' +
                ", totalCount=" + totalCount +
                ", createdTime='" + createdTime + '\'' +
                '}';
    }
}