/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com) All Rights Reserved.
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

package org.wso2.carbon.usage.data.collector.identity.util;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.identity.core.util.IdentityConfigParser;
import org.wso2.carbon.identity.core.util.IdentityDatabaseUtil;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

/**
 * Resolves the datasource used for the MAU per-user tracking table (IDN_MAU_COUNT).
 * <p>
 * On first use it reads {@code UsageTracking.DataSourceName} from identity.xml. If a JNDI name is
 * configured, that datasource is looked up and used for every subsequent access; otherwise the IS
 * identity DB is used as the fallback.
 * <p>
 * The resolved datasource is cached; changing the configuration requires a server restart.
 */
public final class UsageDataSourceProvider {

    private static final Log LOG = LogFactory.getLog(UsageDataSourceProvider.class);

    // null = no custom datasource configured / lookup failed -> fall back to identity DB.
    private static volatile DataSource customDataSource;
    private static volatile boolean initialized;
    private static final Object INIT_LOCK = new Object();

    private UsageDataSourceProvider() {}

    /**
     * Returns the datasource for the usage-tracking table: the custom datasource configured via
     * {@code UsageTracking.DataSourceName} if available, otherwise the IS identity DB.
     *
     * @return The resolved datasource.
     */
    public static DataSource getDataSource() {

        ensureInitialized();
        if (customDataSource != null) {
            return customDataSource;
        }
        return IdentityDatabaseUtil.getDataSource();
    }

    private static void ensureInitialized() {

        if (initialized) {
            return;
        }
        synchronized (INIT_LOCK) {
            if (initialized) {
                return;
            }
            try {
                loadCustomDataSource();
            } finally {
                initialized = true;
            }
        }
    }

    private static void loadCustomDataSource() {

        // Read via IdentityConfigParser (same accessor the usage-metering writer uses) so the
        // reader and writer resolve UsageTracking.DataSourceName identically.
        Object value = IdentityConfigParser.getInstance().getConfiguration()
                .get(UsageCollectorConstants.USAGE_TRACKING_DATASOURCE_NAME);
        String dsName = (value instanceof String) ? ((String) value).trim() : "";
        if (dsName.isEmpty()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("UsageTracking.DataSourceName is not set in identity.xml; using the IS identity DB "
                        + "for IDN_MAU_COUNT.");
            }
            return;
        }

        try {
            customDataSource = (DataSource) new InitialContext().lookup(dsName);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Using custom datasource (JNDI '" + dsName + "') for IDN_MAU_COUNT.");
            }
        } catch (NamingException e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("JNDI lookup failed for UsageTracking.DataSourceName '" + dsName
                        + "'. The datasource must be bound to JNDI under exactly this name. "
                        + "Falling back to the IS identity DB.", e);
            }
            customDataSource = null;
        }
    }
}
