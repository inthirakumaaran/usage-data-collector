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

package org.wso2.carbon.usage.data.collector.common.collector.model;

import com.google.gson.Gson;
import org.wso2.carbon.usage.data.collector.common.publisher.api.model.UsageData;

import java.time.Instant;

/**
 * Model class representing deployment data to be collected and published.
 * Internal use only - for collection purposes. Use DeploymentInformation for publishing.
 */
public class DeploymentData extends UsageData {

    private String operatingSystem;
    private String operatingSystemVersion;
    private String operatingSystemArchitecture;
    private String jdkVersion;
    private String jdkVendor;
    private String productVersion;
    private String updateLevel;
    private String numberOfCores;

    public DeploymentData() {
        super();
        this.createdTime = Instant.now().toString();
    }

    @Override
    public String toJson() {
        return new Gson().toJson(this);
    }

    // Getters and Setters

    public String getOperatingSystem() {
        return operatingSystem;
    }

    public void setOperatingSystem(String operatingSystem) {
        this.operatingSystem = operatingSystem;
    }

    public String getOperatingSystemVersion() {
        return operatingSystemVersion;
    }

    public void setOperatingSystemVersion(String operatingSystemVersion) {
        this.operatingSystemVersion = operatingSystemVersion;
    }

    public String getOperatingSystemArchitecture() {
        return operatingSystemArchitecture;
    }

    public void setOperatingSystemArchitecture(String operatingSystemArchitecture) {
        this.operatingSystemArchitecture = operatingSystemArchitecture;
    }

    public String getJdkVersion() {
        return jdkVersion;
    }

    public void setJdkVersion(String jdkVersion) {
        this.jdkVersion = jdkVersion;
    }

    public String getJdkVendor() {
        return jdkVendor;
    }

    public void setJdkVendor(String jdkVendor) {
        this.jdkVendor = jdkVendor;
    }

    public String getProductVersion() {
        return productVersion;
    }

    public void setProductVersion(String productVersion) {
        this.productVersion = productVersion;
    }

    public String getUpdateLevel() {
        return updateLevel;
    }

    public void setUpdateLevel(String updateLevel) {
        this.updateLevel = updateLevel;
    }

    public String getNumberOfCores() {
        return numberOfCores;
    }

    public void setNumberOfCores(String numberOfCores) {
        this.numberOfCores = numberOfCores;
    }

    @Override
    public String toString() {
        return "DeploymentData{" +
                "createdTime='" + createdTime + '\'' +
                ", operatingSystem='" + operatingSystem + '\'' +
                ", operatingSystemVersion='" + operatingSystemVersion + '\'' +
                ", operatingSystemArchitecture='" + operatingSystemArchitecture + '\'' +
                ", jdkVersion='" + jdkVersion + '\'' +
                ", jdkVendor='" + jdkVendor + '\'' +
                ", productVersion='" + productVersion + '\'' +
                ", updateLevel='" + updateLevel + '\'' +
                ", numberOfCores=" + numberOfCores +
                '}';
    }
}

