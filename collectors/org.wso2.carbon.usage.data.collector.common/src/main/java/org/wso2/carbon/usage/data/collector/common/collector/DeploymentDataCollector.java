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

package org.wso2.carbon.usage.data.collector.common.collector;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.usage.data.collector.common.collector.model.DeploymentData;
import org.wso2.carbon.usage.data.collector.common.internal.CommonUsageDataCollectorConstants;
import org.wso2.carbon.usage.data.collector.common.publisher.api.Publisher;
import org.wso2.carbon.usage.data.collector.common.publisher.api.PublisherException;
import org.wso2.carbon.usage.data.collector.common.publisher.api.model.ApiRequest;
import org.wso2.carbon.usage.data.collector.common.publisher.api.model.ApiResponse;
import org.wso2.carbon.usage.data.collector.common.publisher.api.model.DeploymentInformation;
import org.wso2.carbon.usage.data.collector.common.util.MetaInfoHolder;
import org.wso2.carbon.usage.data.collector.common.util.UsageDataUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Map;
import java.util.TreeMap;

/**
 * Main collector class that collects deployment information and publishes it.
 */
public class DeploymentDataCollector {

    private static final Log log = LogFactory.getLog(DeploymentDataCollector.class);

    // Default values
    private static final String DEFAULT_PRODUCT_VERSION = "N/A";
    private static final String DEFAULT_UPDATE_LEVEL = "N/A";

    private final Publisher publisher;

    public DeploymentDataCollector(Publisher publisher) {
        this.publisher = publisher;
    }

    /**
     * Collects deployment data and publishes it to the receiver.
     * Includes cached meta information in the payload (from MetaInfoHolder).
     * deploymentId and subscriptionKey are assigned by the receiver.
     */
    public void collectAndPublish() {
        try {
            // Collect deployment data
            DeploymentData data = collectDeploymentData();

            // Build deployment info JSON object
            JsonObject deploymentInfo = new JsonObject();
            deploymentInfo.addProperty("os", data.getOperatingSystem());
            deploymentInfo.addProperty("osVersion", data.getOperatingSystemVersion());
            deploymentInfo.addProperty("osArchitecture", data.getOperatingSystemArchitecture());
            deploymentInfo.addProperty("jdkVersion", data.getJdkVersion());
            deploymentInfo.addProperty("jdkVendor", data.getJdkVendor());
            deploymentInfo.addProperty("updateLevel", data.getUpdateLevel());
            deploymentInfo.addProperty("numberOfCores", data.getNumberOfCores());

            // Generate hash of the deployment info using canonical form (sorted keys)
            // TreeMap ensures consistent ordering for predictable hash values
            Map<String, Object> sortedDeploymentInfo = new TreeMap<>();
            sortedDeploymentInfo.put("jdkVendor", data.getJdkVendor());
            sortedDeploymentInfo.put("jdkVersion", data.getJdkVersion());
            sortedDeploymentInfo.put("numberOfCores", data.getNumberOfCores());
            sortedDeploymentInfo.put("os", data.getOperatingSystem());
            sortedDeploymentInfo.put("osArchitecture", data.getOperatingSystemArchitecture());
            sortedDeploymentInfo.put("osVersion", data.getOperatingSystemVersion());
            sortedDeploymentInfo.put("updateLevel", data.getUpdateLevel());

            Gson gson = new Gson();
            String canonicalJson = gson.toJson(sortedDeploymentInfo);
            String deploymentInfoHash = UsageDataUtil.generateSHA256Hash(canonicalJson);

            // Create API-compliant model with cached meta information from MetaInfoHolder
            DeploymentInformation deploymentInformation = new DeploymentInformation(
                    MetaInfoHolder.getNodeId(),
                    MetaInfoHolder.getProduct(),
                    deploymentInfo,
                    deploymentInfoHash
            );

            // Build API request
            ApiRequest request = new ApiRequest.Builder()
                    .withEndpoint(CommonUsageDataCollectorConstants.DEPLOYMENT_INFO_ENDPOINT)
                    .withData(deploymentInformation)
                    .build();

            // Publish to /deployment-information endpoint using Publisher with built-in retry
            ApiResponse response = publisher.publishToReceiver(request);
        } catch (PublisherException e) {
            if(log.isDebugEnabled()) {
                log.error("Failed to publish deployment data after all retries", e);
            }
        } catch (Exception e) {
            if(log.isDebugEnabled()) {
                log.error("Failed to collect and publish deployment data", e);
            }
        }
    }

    /**
     * Collects deployment data without publishing.
     * This method can be used by other components (e.g., MetaInformationManager)
     * that need the deployment data.
     *
     * @return DeploymentData containing collected information
     */
    public DeploymentData collectDeploymentData() {
        DeploymentData data = new DeploymentData();

        // Collect OS information
        collectOperatingSystemInfo(data);

        // Collect JDK information
        collectJdkInfo(data);

        // Set product information
        collectProductInfo(data);

        // Collect hardware information
        collectHardwareInfo(data);

        return data;
    }



    /**
     * Collects operating system information.
     */
    private void collectOperatingSystemInfo(DeploymentData data) {
        try {
            data.setOperatingSystem(System.getProperty("os.name"));
            data.setOperatingSystemVersion(System.getProperty("os.version"));
            data.setOperatingSystemArchitecture(System.getProperty("os.arch"));
        } catch (Exception e) {
            if(log.isDebugEnabled()) {
                log.error("Error collecting OS information", e);
            }
        }
    }

    /**
     * Collects JDK information.
     */
    private void collectJdkInfo(DeploymentData data) {
        try {
            data.setJdkVersion(System.getProperty("java.version"));
            data.setJdkVendor(System.getProperty("java.vendor"));
        } catch (Exception e) {
            if(log.isDebugEnabled()) {
                log.error("Error collecting JDK information", e);
            }
        }
    }

    /**
     * Sets product information by reading from carbon.home/updates directory.
     */
    private void collectProductInfo(DeploymentData data) {
        String carbonHome = System.getProperty("carbon.home");

        if (carbonHome != null && !carbonHome.trim().isEmpty()) {
            File updatesDir = new File(carbonHome, "updates");

            // Read product version from product.txt
            File productFile = new File(updatesDir, "product.txt");

            if (productFile.exists() && productFile.canRead()) {
                String productVersion = readFirstLineFromFile(productFile);
                if (!productVersion.isEmpty()) {
                    data.setProductVersion(productVersion);
                } else {
                    data.setProductVersion(DEFAULT_PRODUCT_VERSION);
                }
            } else {
                data.setProductVersion(DEFAULT_PRODUCT_VERSION);
            }

            // Read update level from config.json
            File configFile = new File(updatesDir, "config.json");

            if (configFile.exists() && configFile.canRead()) {
                JsonObject configJsonObj = readJsonObject(configFile.getAbsolutePath());
                if (configJsonObj != null && configJsonObj.has("update-level")) {
                    data.setUpdateLevel(configJsonObj.get("update-level").getAsString());
                } else {
                    data.setUpdateLevel(DEFAULT_UPDATE_LEVEL);
                }
            } else {
                data.setUpdateLevel(DEFAULT_UPDATE_LEVEL);
            }
        } else {
            // Fallback if carbon.home is not set
            data.setProductVersion(DEFAULT_PRODUCT_VERSION);
            data.setUpdateLevel(DEFAULT_UPDATE_LEVEL);
        }
    }

    /**
     * Reads the first line from a file.
     *
     * @param file the file to read from
     * @return the first line trimmed, or empty string if unable to read
     */
    private String readFirstLineFromFile(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line = reader.readLine();
            return line != null ? line.trim() : "";
        } catch (Exception e) {
            if(log.isDebugEnabled()) {
                log.error("Could not read file: " + file.getAbsolutePath(), e);
            }
            return "";
        }
    }

    /**
     * Reads and parses a JSON file into a JsonObject.
     *
     * @param filePath the path to the JSON file
     * @return JsonObject or null if parsing fails
     */
    private JsonObject readJsonObject(String filePath) {
        try (FileReader reader = new FileReader(filePath)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            if(log.isDebugEnabled()) {
                log.error("Error reading JSON file: " + filePath, e);
            }
            return null;
        }
    }

    /**
     * Collects hardware information.
     */
    private void collectHardwareInfo(DeploymentData data) {
        try {
            int cores = Runtime.getRuntime().availableProcessors();
            data.setNumberOfCores(String.valueOf(cores));
        } catch (Exception e) {
            if(log.isDebugEnabled()) {
                log.error("Error collecting hardware information", e);
            }
        }
    }
}

