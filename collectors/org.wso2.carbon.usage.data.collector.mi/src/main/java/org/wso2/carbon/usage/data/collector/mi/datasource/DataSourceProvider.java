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

package org.wso2.carbon.usage.data.collector.mi.datasource;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import java.sql.SQLException;

import javax.sql.DataSource;

/**
 * DataSource provider that retrieves DataSource via OSGi service lookup.
 */
public class DataSourceProvider {

    private static final Log log = LogFactory.getLog(DataSourceProvider.class);
    private DataSource dataSource;
    private static volatile DataSourceProvider instance;
    private String dataSourceName;
    private boolean initialized = false;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 60*1000; // 1 minute delay

    private DataSourceProvider() {}

    public static DataSourceProvider getInstance() {
        if (instance == null) {
            synchronized (DataSourceProvider.class) {
                if (instance == null) {
                    instance = new DataSourceProvider();
                }
            }
        }
        return instance;
    }

    public void initialize(String dataSourceName) throws SQLException {
        this.dataSourceName = dataSourceName;
        this.initialized = true;

        if (dataSource == null) {
            dataSource = lookupDataSource();
            if (dataSource == null) {
                if(log.isDebugEnabled()) {
                    log.debug("DataSource '" + dataSourceName + "' not available yet. Will retry on next access.");
                }
            } else {
                if(log.isDebugEnabled()) {
                    log.debug("DataSource '" + dataSourceName + "' initialized successfully");
                }
            }
        }
    }

    public DataSource getDataSource() throws SQLException {
        if (!initialized) {
            throw new SQLException("DataSource is not initialized. Call initialize() first.");
        }

        // Lazy loading: try to get DataSource if not already loaded
        if (dataSource == null) {
            int attempt = 0;
            DataSource ds = null;
            while (attempt < MAX_RETRIES) {
                ds = lookupDataSource();
                if (ds != null) {
                    synchronized (this) {
                        if (dataSource == null) {
                            dataSource = ds;
                            if(log.isDebugEnabled()) {
                                log.debug("DataSource '" + dataSourceName + "' successfully loaded on attempt " + (attempt + 1));
                            }
                        }
                    }
                    // If dataSource was set by this thread, break; otherwise, continue retrying
                    if (dataSource != null) {
                        break;
                    }
                }
                attempt++;
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            if (dataSource == null) {
                throw new SQLException("DataSource '" + dataSourceName + "' not found after " + MAX_RETRIES + " attempts. " +
                        "Please ensure the DataSource is properly configured in deployment.toml");
            }
        }
        return dataSource;
    }

    private DataSource lookupDataSource() {

        try {
            org.osgi.framework.Bundle bundle = FrameworkUtil.getBundle(this.getClass());
            if (bundle == null) {
                if (log.isDebugEnabled()) {
                    log.debug("Bundle is null - OSGi environment not ready or class not loaded as OSGi bundle");
                }
                return null;
            }
            BundleContext bundleContext = bundle.getBundleContext();
            if (bundleContext == null) {
                if (log.isDebugEnabled()) {
                    log.debug("BundleContext is null - OSGi environment not ready");
                }
                return null;
            }

            ServiceReference<?> serviceRef = bundleContext.getServiceReference(
                    "org.wso2.micro.integrator.ndatasource.core.DataSourceService");

            if (serviceRef == null) {
                if (log.isDebugEnabled()) {
                    log.debug("DataSourceService reference not found - service may not be registered yet");
                }
                return null;
            }

            Object dataSourceService = bundleContext.getService(serviceRef);

            if (dataSourceService == null) {
                if (log.isDebugEnabled()) {
                    log.debug("DataSourceService is null");
                }
                return null;
            }

            Object carbonDataSource = dataSourceService.getClass()
                    .getMethod("getDataSource", String.class)
                    .invoke(dataSourceService, dataSourceName);

            if (carbonDataSource == null) {
                if (log.isDebugEnabled()) {
                    log.debug("CarbonDataSource '" + dataSourceName + "' not found in registry");
                }
                return null;
            }

            // Unwrap CarbonDataSource to get actual DataSource
            Object dsObject = carbonDataSource.getClass()
                    .getMethod("getDSObject")
                    .invoke(carbonDataSource);

            if (dsObject instanceof DataSource) {
                if (log.isDebugEnabled()) {
                    log.debug("DataSource '" + dataSourceName + "' successfully retrieved via OSGi");
                }
                return (DataSource) dsObject;
            } else if (dsObject != null) {
                if (log.isDebugEnabled()) {
                    log.debug("DSObject is not a DataSource. Type: " + dsObject.getClass().getName());
                }
            } else {
                if (log.isDebugEnabled()) {
                log.error("DSObject is null for DataSource: " + dataSourceName);
                }
            }

        } catch (NoSuchMethodException e) {
            if (log.isDebugEnabled()) {
                log.debug("Method not found - API may have changed: " + e.getMessage(), e);
            }
        } catch (java.lang.reflect.InvocationTargetException invocationException) {
            Throwable cause = invocationException.getCause();
            String causeMsg = (cause != null) ? cause.getMessage() : "null";
            if (log.isDebugEnabled()) {
                log.debug("InvocationTargetException cause: " + causeMsg, cause);
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("Failed to lookup DataSource via OSGi: " + e.getMessage());
            }
        }

        return null;
    }

    /**
     * Check if DataSource provider has been initialized
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Check if DataSource is currently available
     */
    public boolean isAvailable() {
        return dataSource != null;
    }

    /**
     * Force a refresh of the DataSource (useful for testing or recovery)
     */
    public synchronized void refresh() {
        if (log.isDebugEnabled()) {
            log.debug("Forcing DataSource refresh for '" + dataSourceName + "'");
        }
        dataSource = null;
    }

    public synchronized void close() {
        dataSource = null;
        initialized = false;
    }
}
