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

package org.wso2.carbon.identity.usage.data.collector.model;

/**
 * Simple model for system-wide statistics.
 */
public class SystemUsage {

    private int rootTenantCount;
    private int totalB2BOrganizations;
    private int totalUsers;

    public int getRootTenantCount() {

        return rootTenantCount;
    }

    public void setRootTenantCount(int rootTenantCount) {

        this.rootTenantCount = rootTenantCount;
    }

    public int getTotalB2BOrganizations() {

        return totalB2BOrganizations;
    }

    public void setTotalB2BOrganizations(int totalB2BOrganizations) {

        this.totalB2BOrganizations = totalB2BOrganizations;
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
                ", totalB2BOrganizations=" + totalB2BOrganizations +
                ", totalUsers=" + totalUsers +
                '}';
    }
}