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

package org.wso2.carbon.usage.data.collector.identity.publisher;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.identity.core.util.IdentityDatabaseUtil;
import org.wso2.carbon.usage.data.collector.common.publisher.api.Publisher;
import org.wso2.carbon.usage.data.collector.common.publisher.api.model.ApiRequest;
import org.wso2.carbon.usage.data.collector.common.publisher.api.model.ApiResponse;
import org.wso2.carbon.usage.data.collector.common.publisher.api.model.DeploymentInformation;
import org.wso2.carbon.usage.data.collector.common.publisher.api.model.MetaInformation;
import org.wso2.carbon.usage.data.collector.common.publisher.api.model.UsageCount;
import org.wso2.carbon.usage.data.collector.common.receiver.Receiver;
import org.wso2.carbon.usage.data.collector.identity.internal.UsageDataCollectorDataHolder;

import java.io.IOException;

import javax.sql.DataSource;

/**
 * Publisher implementation.
 */
public class PublisherImp implements Publisher {

    private static final Log LOG = LogFactory.getLog(PublisherImp.class);

    @Override
    public DataSource getDataSource() {

        return IdentityDatabaseUtil.getDataSource();
    }

    @Override
    public ApiResponse callReceiverApi(ApiRequest request) {

        if (request == null || request.getData() == null) {
            return ApiResponse.failure(400, "Invalid request");
        }

        Receiver receiver = UsageDataCollectorDataHolder.getInstance().getReceiver();
        if (receiver == null) {
            return ApiResponse.failure(500, "Receiver is not available");
        }

        try {
            Object data = request.getData();

            if (data instanceof UsageCount) {
                receiver.processUsageData((UsageCount) data);
            } else if (data instanceof DeploymentInformation) {
                receiver.processDeploymentInformationData((DeploymentInformation) data);
            } else if (data instanceof MetaInformation) {
                receiver.processMetaInformationData((MetaInformation) data);
            } else {
                return ApiResponse.failure(400, "Unsupported data type");
            }
            return ApiResponse.success(200, "Success");
        } catch (Exception e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Receiver API call failed", e);
            }
            return ApiResponse.failure(500, "Receiver API call failed");
        }
    }

    @Override
    public ApiResponse callExternalApi(ApiRequest request) {

        if (request == null || request.getEndpoint() == null || request.getEndpoint().trim().isEmpty()) {
            return ApiResponse.failure(400, "Invalid request");
        }

        try {
            return new HTTPClient().sendHttpRequest(request.getEndpoint(), request);
        } catch (IOException e) {
            String errorMsg = "External API call failed";
            if (LOG.isDebugEnabled()) {
                LOG.debug(errorMsg, e);
            }
            // Return failure response instead of throwing exception
            return ApiResponse.failure(500, errorMsg);
        }
    }
}
