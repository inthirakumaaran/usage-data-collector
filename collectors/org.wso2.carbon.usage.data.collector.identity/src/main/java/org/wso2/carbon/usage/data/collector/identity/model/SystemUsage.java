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

package org.wso2.carbon.usage.data.collector.identity.model;

/**
 * Simple model for system-wide statistics.
 */
public class SystemUsage {

    private int rootTenantCount;
    private int totalUsers;

    public int getRootTenantCount() {

        return rootTenantCount;
    }

    public void setRootTenantCount(int rootTenantCount) {

        this.rootTenantCount = rootTenantCount;
    }

    public int getTotalUsers() {

        return totalUsers;
    }

    public void setTotalUsers(int totalUsers) {

        this.totalUsers = totalUsers;
    }

    @Override
    public String toString() {

        return "SystemUsage{" +
                "rootTenantCount=" + rootTenantCount +
                ", totalUsers=" + totalUsers +
                '}';
    }
}
