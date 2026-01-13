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

package org.wso2.carbon.usage.data.collector.common.publisher.api;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.usage.data.collector.common.publisher.api.model.ApiRequest;
import org.wso2.carbon.usage.data.collector.common.publisher.api.model.ApiResponse;

import javax.sql.DataSource;

/**
 * Interface for product-specific publishers.
 * Provides product-specific database access and API call capabilities with built-in retry logic.
 *
 * Different WSO2 products (APIM, MI, IS) have different:
 * - DataSource configurations
 * - Internal API endpoints and authentication
 * - External API endpoints and authentication
 *
 * Implementations of this interface can be registered as OSGi services
 * and will be automatically discovered by the framework.
 *
 * <h3>Usage Pattern:</h3>
 * <ul>
 *   <li><b>Collectors should use:</b> {@code publishToReceiver()} for receiver API calls with retry logic</li>
 *   <li><b>Collectors should use:</b> {@code callExternalApi()} for external API calls (e.g., Choreo, OAuth) without automatic retry</li>
 *   <li><b>Implementations should override:</b> {@code callReceiverApi()} and {@code callExternalApi()} - Low-level HTTP methods</li>
 * </ul>
 */
public interface Publisher {

    int MAX_RETRIES = 3;
    int RETRY_DELAY_MS = 1000;
    Log log = LogFactory.getLog(Publisher.class);

    /**
     * Gets the DataSource for this product.
     * This is used for database operations specific to the product.
     *
     * Examples:
     * - WSO2 APIM: DataSource from "jdbc/WSO2AM_DB"
     * - WSO2 IS: DataSource from "jdbc/WSO2CARBON_DB"
     * - WSO2 MI: DataSource from "jdbc/WSO2_CONSUMPTION_TRACKING_DB"
     *
     * @return The DataSource for database operations
     * @throws PublisherException If the DataSource cannot be retrieved
     */
    DataSource getDataSource() throws PublisherException;

    /**
     * Publishes data to the receiver API with automatic retry logic.
     * This is the HIGH-LEVEL method that collectors should use.
     *
     * <p>Retry behavior:</p>
     * <ul>
     *   <li>Retries up to {@value #MAX_RETRIES} times</li>
     *   <li>Exponential backoff: 1s, 2s, 3s between retries</li>
     *   <li>Returns on first successful response (2xx status code)</li>
     *   <li>Throws exception if all retries fail</li>
     * </ul>
     *
     * @param request The API request containing data and parameters
     * @return ApiResponse with successful status code and body
     * @throws PublisherException If all retry attempts fail
     */
    default ApiResponse publishToReceiver(ApiRequest request) throws PublisherException {
        return executeWithRetry(() -> callReceiverApi(request), "publishToReceiver");
    }


    /**
     * Internal retry logic shared by both publish methods.
     * Implements exponential backoff retry strategy with smart retry decisions.
     *
     * @param operation The operation to execute with retry
     * @param operationName Name of the operation for logging
     * @return ApiResponse from successful operation
     * @throws PublisherException If all retries fail
     */
    default ApiResponse executeWithRetry(PublisherOperation operation, String operationName)
            throws PublisherException {

        PublisherException lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                ApiResponse response = operation.execute();

                // Check if response is successful (2xx status code)
                int statusCode = response.getStatusCode();
                if (statusCode >= 200 && statusCode < 300) {
                    if (attempt > 1 && log.isDebugEnabled()) {
                        log.debug(operationName + " succeeded on attempt " + attempt);
                    }
                    return response;
                }

                // Non-2xx response - check if we should retry
                String errorMsg = "Received non-successful status code: " + statusCode +
                        ", error: " + (response.getErrorMessage() != null ? response.getErrorMessage() : 
                        response.getResponseBody());
                lastException = new PublisherException(errorMsg);

                // Check if this status code is retryable
                if (!shouldRetry(statusCode)) {
                    // Non-retryable error - fail immediately
                    throw lastException;
                }

            } catch (PublisherException e) {
                lastException = e;
                if (log.isDebugEnabled()) {
                    log.debug(operationName + " failed (attempt " + attempt + "/" + MAX_RETRIES + "): " + e.getMessage());
                }
            }

            // If not the last attempt, wait before retrying with exponential backoff
            if (attempt < MAX_RETRIES) {
                try {
                    long delay = (long) attempt * RETRY_DELAY_MS;
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new PublisherException("Retry interrupted for " + operationName, ie);
                }
            }
        }

        // All retries failed
        String errorMsg = operationName + " failed after " + MAX_RETRIES + " attempts";
        throw new PublisherException(errorMsg, lastException);
    }

    /**
     * Determines if an HTTP status code should trigger a retry.
     *
     * <p>Retryable status codes:</p>
     * <ul>
     *   <li><b>404 (Not Found)</b> - Service might not be ready yet</li>
     *   <li><b>408 (Request Timeout)</b> - Temporary timeout, likely to succeed on retry</li>
     *   <li><b>429 (Too Many Requests)</b> - Rate limiting, will succeed after backoff</li>
     *   <li><b>500 (Internal Server Error)</b> - Server error, might be transient</li>
     *   <li><b>502 (Bad Gateway)</b> - Gateway error, might be transient</li>
     *   <li><b>503 (Service Unavailable)</b> - Service temporarily unavailable</li>
     *   <li><b>504 (Gateway Timeout)</b> - Gateway timeout, might succeed on retry</li>
     * </ul>
     *
     * <p>Non-retryable status codes (fail immediately):</p>
     * <ul>
     *   <li><b>400 (Bad Request)</b> - Invalid request, retry won't help</li>
     *   <li><b>401 (Unauthorized)</b> - Authentication issue, retry won't help</li>
     *   <li><b>403 (Forbidden)</b> - Authorization issue, retry won't help</li>
     *   <li><b>405 (Method Not Allowed)</b> - Wrong HTTP method, retry won't help</li>
     * </ul>
     *
     * @param statusCode The HTTP status code
     * @return true if the request should be retried, false otherwise
     */
    default boolean shouldRetry(int statusCode) {
        // Retryable 4xx errors
        if (statusCode == 404 ||  // Not Found - service not ready
            statusCode == 408 ||  // Request Timeout
            statusCode == 429) {  // Too Many Requests - rate limiting
            return true;
        }

        // Retryable 5xx errors (server errors that might be transient)
        if (statusCode >= 500 && statusCode < 600) {
            return true;  // All 5xx errors are retryable (500, 502, 503, 504, etc.)
        }

        // All other status codes (400, 401, 403, 405, etc.) are non-retryable
        return false;
    }

    /**
     * Sends HTTP request to receiver API WITHOUT retry logic.
     * This is a LOW-LEVEL method that implementations must override.
     * Collectors should NOT call this directly - use {@link #publishToReceiver(ApiRequest)} instead.
     *
     * <p><b>Implementation Guidelines:</b></p>
     * <ul>
     *   <li>Just send the HTTP request and return the response</li>
     *   <li>Return both success and failure responses</li>
     *   <li>Do NOT implement retry logic here</li>
     *   <li>Do NOT validate status codes here</li>
     *   <li>Throw PublisherException only for network errors or configuration issues</li>
     * </ul>
     *
     * @param request The API request containing data and parameters
     * @return ApiResponse containing status code and body (both success and failure responses)
     * @throws PublisherException If the request cannot be sent (e.g., network error, configuration issue)
     */
    ApiResponse callReceiverApi(ApiRequest request) throws PublisherException;

    /**
     * Sends HTTP request to external API WITHOUT retry logic.
     * This is a LOW-LEVEL method that implementations must override.
     * Use this for external API calls like Choreo API, OAuth token endpoints, etc.
     *
     * <p><b>Implementation Guidelines:</b></p>
     * <ul>
     *   <li>Just send the HTTP request and return the response</li>
     *   <li>Return both success and failure responses</li>
     *   <li>Do NOT implement retry logic here - caller handles retries if needed</li>
     *   <li>Do NOT validate status codes here</li>
     *   <li>Throw PublisherException only for network errors or configuration issues</li>
     * </ul>
     *
     * <p><b>Common Use Cases:</b></p>
     * <ul>
     *   <li>Calling Choreo API to publish usage data</li>
     *   <li>Getting OAuth access tokens</li>
     *   <li>Any other external HTTP API calls</li>
     * </ul>
     *
     * @param request The API request containing data and parameters (including endpoint)
     * @return ApiResponse containing status code and body (both success and failure responses)
     * @throws PublisherException If the request cannot be sent (e.g., network error, configuration issue)
     */
    ApiResponse callExternalApi(ApiRequest request) throws PublisherException;

    /**
     * Functional interface for operations that can be retried.
     */
    @FunctionalInterface
    interface PublisherOperation {
        ApiResponse execute() throws PublisherException;
    }
}

