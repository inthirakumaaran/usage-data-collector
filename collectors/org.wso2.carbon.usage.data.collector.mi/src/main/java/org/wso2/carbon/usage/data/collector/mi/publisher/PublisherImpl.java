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
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.usage.data.collector.mi.publisher;

import com.google.gson.Gson;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.wso2.carbon.usage.data.collector.common.publisher.api.Publisher;
import org.wso2.carbon.usage.data.collector.common.publisher.api.PublisherException;
import org.wso2.carbon.usage.data.collector.common.publisher.api.model.ApiRequest;
import org.wso2.carbon.usage.data.collector.common.publisher.api.model.ApiResponse;
import org.wso2.carbon.usage.data.collector.common.publisher.api.model.DeploymentInformation;
import org.wso2.carbon.usage.data.collector.common.publisher.api.model.MetaInformation;
import org.wso2.carbon.usage.data.collector.common.publisher.api.model.UsageCount;
import org.wso2.carbon.usage.data.collector.common.receiver.Receiver;
import org.wso2.carbon.usage.data.collector.mi.datasource.DataSourceProvider;
import org.wso2.carbon.usage.data.collector.mi.internal.UsageDataCollectorDataHolder;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Map;

import javax.sql.DataSource;

/**
 * Implementation of Publisher interface for MI.
 */
@Component(
    name = "org.wso2.carbon.usage.data.collector.mi.publisher",
    service = Publisher.class,
    immediate = true
)
public class PublisherImpl implements Publisher {
    
    private volatile boolean isShuttingDown = false;
    
    @Activate
    protected void activate() {
        if (log.isDebugEnabled()) {
            log.debug("Publisher activated");
        }
    }

    @Deactivate
    protected void deactivate() {
        
        // Set shutdown flag to prevent new operations
        isShuttingDown = true;
        
        try {
            httpClient.close();
            if (log.isDebugEnabled()) {
                log.debug("Publisher deactivated and HttpClient closed");
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("Error closing shared HttpClient", e);
            }
        }
    }

    @Reference(
            name = "receiver",
            service = Receiver.class,
            cardinality = ReferenceCardinality.MANDATORY,
            policy = ReferencePolicy.DYNAMIC,
            unbind = "unsetReceiver"
    )
    protected void setReceiver(Receiver receiver) {
        UsageDataCollectorDataHolder.getInstance().setReceiver(receiver);
        if (log.isDebugEnabled()) {
            log.debug("Transaction Receiver bounded");
        }
    }

    protected void unsetReceiver(Receiver receiver) {
        UsageDataCollectorDataHolder.getInstance().setReceiver(null);
    }

    private static final Log log = LogFactory.getLog(PublisherImpl.class);
    private static final org.apache.http.impl.client.CloseableHttpClient httpClient =
            org.apache.http.impl.client.HttpClients.createDefault();
    private static final Gson gson = new Gson();

    @Override
    public DataSource getDataSource() throws PublisherException {
        try {
            DataSourceProvider provider = DataSourceProvider.getInstance();
            if (!provider.isInitialized()) {
                provider.initialize(PublisherConstants.DATASOURCE_NAME);
            }
            return provider.getDataSource();
        } catch (SQLException e) {
            String errorMsg = "Failed to get datasource: " + PublisherConstants.DATASOURCE_NAME;
            log.error(errorMsg, e);
            throw new PublisherException(errorMsg, e);
        }
    }

    @Override
    public ApiResponse callReceiverApi(ApiRequest request) throws PublisherException {
        return executeReceiverCall(request);
    }

    @Override
    public ApiResponse callExternalApi(ApiRequest request) throws PublisherException {
        String endpoint = request.getEndpoint();
        return executeHttpRequest(request, endpoint, "external API");
    }
    
    /**
     * This method identifies the request type based on the endpoint and data,
     * then delegates to the appropriate processor method.
     *
     * @param request The ApiRequest containing data and endpoint information.
     * @return ApiResponse representing the result of the processing.
     * @throws PublisherException if the request fails validation or processing.
     */
    private ApiResponse executeReceiverCall(ApiRequest request) throws PublisherException {
        try {
            String endpoint = request.getEndpoint();
            Object data = request.getData();
            
            if (data == null) {
                return ApiResponse.failure(400, "Request body is required");
            }
            
            // Identify request type based on endpoint
            if (endpoint != null && endpoint.contains(PublisherConstants.USAGE_COUNT_ENDPOINT)) {
                return processUsageCount(data);
            } else if (endpoint != null && endpoint.contains(PublisherConstants.DEPLOYMENT_INFO_ENDPOINT)) {
                return processDeploymentInformation(data);
            } else if (endpoint != null && endpoint.contains(PublisherConstants.META_INFO_ENDPOINT)) {
                return processMetaInformation(data);
            } else {
                return ApiResponse.failure(400, "Unknown receiver endpoint: " + endpoint);
            }
        } catch (PublisherException e) {
            throw e;
        } catch (Exception e) {
            String errorMsg = "Failed to process receiver request";
            if (log.isDebugEnabled()) {
                log.error(errorMsg, e);
            }
            throw new PublisherException(errorMsg, e);
        }
    }
    
    /**
     * Processes UsageCount data through UsageDataProcessor.
     */
    private ApiResponse processUsageCount(Object data) throws PublisherException {
        // Reject new operations during shutdown
        if (isShuttingDown) {
            if (log.isDebugEnabled()) {
                log.debug("Rejecting usage count processing - component is shutting down");
            }
            return ApiResponse.failure(503, "Service is shutting down");
        }
        
        try {
            UsageCount usageCount = convertToUsageCount(data);
            // Validate required fields
            String validationError = validateUsageCount(usageCount);
            if (validationError != null) {
                return ApiResponse.failure(400, validationError);
            }
            
            // Get Receiver service from DataHolder
            Receiver receiver = UsageDataCollectorDataHolder.getInstance().getReceiver();
            if (receiver == null) {
                if (log.isDebugEnabled()) {
                    log.debug("Receiver service not available - cannot process usage count");
                }
                return ApiResponse.failure(503, "Receiver service not available");
            }
            
            // Process using Receiver interface
            receiver.processUsageData(usageCount);
            return ApiResponse.success(201, "{\"message\":\"Record received successfully.\"}");
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.error("Failed to process usage count", e);
            }
            return ApiResponse.failure(500, "Internal server error: " + e.getMessage());
        }
    }
    
    /**
     * Processes DeploymentInformation data through UsageDataProcessor.
     */
    private ApiResponse processDeploymentInformation(Object data) throws PublisherException {
        // Reject new operations during shutdown
        if (isShuttingDown) {
            return ApiResponse.failure(503, "Service is shutting down");
        }
        
        try {
            DeploymentInformation deploymentInfo = convertToDeploymentInformation(data);
            // Validate required fields
            String validationError = validateDeploymentInformation(deploymentInfo);
            if (validationError != null) {
                return ApiResponse.failure(400, validationError);
            }
            
            // Get Receiver service from DataHolder
            Receiver receiver = UsageDataCollectorDataHolder.getInstance().getReceiver();
            if (receiver == null) {
                if (log.isDebugEnabled()) {
                    log.debug("Receiver service not available - cannot process deployment information");
                }
                return ApiResponse.failure(503, "Receiver service not available");
            }
            
            // Process using Receiver interface
            receiver.processDeploymentInformationData(deploymentInfo);
            return ApiResponse.success(201, "{\"message\":\"Record received successfully.\"}");
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.error("Failed to process deployment information", e);
            }
            return ApiResponse.failure(500, "Internal server error: " + e.getMessage());
        }
    }
    
    /**
     * Processes MetaInformation data through UsageDataProcessor.
     */
    private ApiResponse processMetaInformation(Object data) throws PublisherException {
        // Reject new operations during shutdown
        if (isShuttingDown) {
            return ApiResponse.failure(503, "Service is shutting down");
        }
        
        try {
            MetaInformation metaInfo = convertToMetaInformation(data);
            // Validate required fields
            String validationError = validateMetaInformation(metaInfo);
            if (validationError != null) {
                return ApiResponse.failure(400, validationError);
            }
            
            // Get Receiver service from DataHolder
            Receiver receiver = UsageDataCollectorDataHolder.getInstance().getReceiver();
            if (receiver == null) {
                if (log.isDebugEnabled()) {
                    log.debug("Receiver service not available - cannot process meta information");
                }
                return ApiResponse.failure(503, "Receiver service not available");
            }
            
            // Process using Receiver interface
            receiver.processMetaInformationData(metaInfo);
            return ApiResponse.success(201, "{\"message\":\"Record received successfully.\"}");
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.error("Failed to process meta information", e);
            }
            return ApiResponse.failure(500, "Internal server error: " + e.getMessage());
        }
    }
    
    /**
     * Converts generic data object to UsageCount.
     */
    private UsageCount convertToUsageCount(Object data) {
        if (data instanceof UsageCount) {
            return (UsageCount) data;
        }
        String json = gson.toJson(data);
        return gson.fromJson(json, UsageCount.class);
    }
    
    /**
     * Converts generic data object to DeploymentInformation.
     */
    private DeploymentInformation convertToDeploymentInformation(Object data) {
        if (data instanceof DeploymentInformation) {
            return (DeploymentInformation) data;
        }
        String json = gson.toJson(data);
        return gson.fromJson(json, DeploymentInformation.class);
    }
    
    /**
     * Converts generic data object to MetaInformation.
     */
    private MetaInformation convertToMetaInformation(Object data) {
        if (data instanceof MetaInformation) {
            return (MetaInformation) data;
        }
        String json = gson.toJson(data);
        return gson.fromJson(json, MetaInformation.class);
    }
    
    /**
     * Validates UsageCount fields (same validation as HTTP API).
     */
    private String validateUsageCount(UsageCount usageCount) {
        if (usageCount.getNodeId() == null || usageCount.getNodeId().trim().isEmpty()) {
            return "nodeId is required";
        }
        if (usageCount.getProduct() == null || usageCount.getProduct().trim().isEmpty()) {
            return "product is required";
        }
        if (usageCount.getType() == null || usageCount.getType().trim().isEmpty()) {
            return "type is required";
        }
        return null;
    }
    
    /**
     * Validates DeploymentInformation fields (same validation as HTTP API).
     */
    private String validateDeploymentInformation(DeploymentInformation deploymentInfo) {
        if (deploymentInfo.getNodeId() == null || deploymentInfo.getNodeId().trim().isEmpty()) {
            return "nodeId is required";
        }
        if (deploymentInfo.getProduct() == null || deploymentInfo.getProduct().trim().isEmpty()) {
            return "product is required";
        }
        if (deploymentInfo.getDeploymentInfo() == null) {
            return "deploymentInfo is required";
        }
        if (deploymentInfo.getDeploymentInfoHash() == null || 
            deploymentInfo.getDeploymentInfoHash().trim().isEmpty()) {
            return "deploymentInfoHash is required";
        }
        return null;
    }
    
    /**
     * Validates MetaInformation fields (same validation as HTTP API).
     */
    private String validateMetaInformation(MetaInformation metaInfo) {
        if (metaInfo.getNodeId() == null || metaInfo.getNodeId().trim().isEmpty()) {
            return "nodeId is required";
        }
        if (metaInfo.getProduct() == null || metaInfo.getProduct().trim().isEmpty()) {
            return "product is required";
        }
        return null;
    }
    
    /**
     * Executes an HTTP POST request to the given endpoint with the provided ApiRequest data.
     *
     * @param request       The ApiRequest containing data and timeout.
     * @param endpoint      The endpoint URL to send the request to.
     * @param endpointLabel A label for logging and error messages.
     * @return ApiResponse representing the result of the HTTP call.
     * @throws PublisherException if the request fails.
     */
    private ApiResponse executeHttpRequest(ApiRequest request, String endpoint, String endpointLabel)
            throws PublisherException {
        int timeoutMs = PublisherConstants.DEFAULT_TIMEOUT_MS;
        int reqTimeout = request.getTimeoutMs();
        if (reqTimeout > 0) {
            timeoutMs = reqTimeout;
        }
        
        // Determine content type from headers, default to application/json
        Map<String, String> requestHeaders = request.getHeaders();
        String contentType = requestHeaders != null ? requestHeaders.get("Content-Type") : null;
        if (contentType == null) {
            contentType = "application/json";
        }
        
        // Prepare request body based on content type
        String requestBody;
        if (request.getData() instanceof String) {
            // If data is already a string (e.g., form-encoded), use it directly
            requestBody = (String) request.getData();
        } else if (contentType.contains("application/x-www-form-urlencoded")) {
            // Convert map to form-encoded string for form data
            if (request.getData() instanceof Map) {
                StringBuilder sb = new StringBuilder();
                @SuppressWarnings("unchecked")
                Map<String, Object> dataMap = (Map<String, Object>) request.getData();
                try {
                    for (Map.Entry<String, Object> entry : dataMap.entrySet()) {
                        if (sb.length() > 0) {
                            sb.append("&");
                        }
                        // Java 8 compatible - uses String instead of Charset
                        sb.append(java.net.URLEncoder.encode(entry.getKey(), "UTF-8"))
                          .append("=")
                          .append(java.net.URLEncoder.encode(String.valueOf(entry.getValue()), "UTF-8"));
                    }
                } catch (java.io.UnsupportedEncodingException e) {
                    throw new PublisherException("UTF-8 encoding not supported", e);
                }
                requestBody = sb.toString();
            } else {
                requestBody = String.valueOf(request.getData());
            }
        } else {
            // Default: convert to JSON
            requestBody = request.getData() != null ? gson.toJson(request.getData()) : "{}";
        }
        
        org.apache.http.client.config.RequestConfig requestConfig = org.apache.http.client.config.RequestConfig.custom()
                .setConnectTimeout(timeoutMs)
                .setConnectionRequestTimeout(timeoutMs)
                .setSocketTimeout(timeoutMs)
                .build();
        try {
            HttpPost httpPost = new HttpPost(endpoint);
            httpPost.setConfig(requestConfig);
            httpPost.setHeader("Content-Type", contentType);
            httpPost.setHeader("Accept", "application/json");
            httpPost.setHeader("User-Agent", "WSO2-Usage-Data-Collector/1.0");
            
            // Add any additional headers from the request
            if (requestHeaders != null) {
                for (Map.Entry<String, String> header : requestHeaders.entrySet()) {
                    if (!"Content-Type".equalsIgnoreCase(header.getKey())) {
                        httpPost.setHeader(header.getKey(), header.getValue());
                    }
                }
            }
            
            httpPost.setEntity(new StringEntity(requestBody, StandardCharsets.UTF_8));
            try (org.apache.http.client.methods.CloseableHttpResponse response = httpClient.execute(httpPost)) {
                int statusCode = response.getStatusLine().getStatusCode();
                String responseBody = EntityUtils.toString(response.getEntity());
                if (statusCode >= 200 && statusCode < 300) {
                    return ApiResponse.success(statusCode, responseBody);
                } else {
                    return ApiResponse.failure(statusCode, "HTTP error: " + statusCode + " - " + responseBody);
                }
            }
        } catch (Exception e) {
            String errorMsg = "PublisherImpl: Failed to call " + endpointLabel + " at " + endpoint;
            if (log.isDebugEnabled()) {
                log.error(errorMsg, e);
            }
            throw new PublisherException(errorMsg, e);
        }
    }
}
