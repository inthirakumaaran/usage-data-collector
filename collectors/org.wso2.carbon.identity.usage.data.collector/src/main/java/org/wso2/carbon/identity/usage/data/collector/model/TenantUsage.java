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
 * Model class for tenant-level statistics.
 */
public class TenantUsage {

    private String tenantDomain;
    private int b2bOrgCount;
    private int userCount;

    public TenantUsage() {
    }

    public TenantUsage(String tenantDomain) {

        this.tenantDomain = tenantDomain;
    }

    public TenantUsage(String tenantDomain, int b2bOrgCount, int userCount) {

        this.tenantDomain = tenantDomain;
        this.b2bOrgCount = b2bOrgCount;
        this.userCount = userCount;
    }

    public String getTenantDomain() {

        return tenantDomain;
    }

    public void setTenantDomain(String tenantDomain) {

        this.tenantDomain = tenantDomain;
    }

    public int getB2bOrgCount() {

        return b2bOrgCount;
    }

    public void setB2bOrgCount(int b2bOrgCount) {

        this.b2bOrgCount = b2bOrgCount;
    }

    public int getUserCount() {

        return userCount;
    }

    public void setUserCount(int userCount) {

        this.userCount = userCount;
    }

    @Override
    public String toString() {

        return "TenantUsage{" +
                "tenantDomain='" + tenantDomain + '\'' +
                ", b2bOrgCount=" + b2bOrgCount +
                ", userCount=" + userCount +
                '}';
    }
}