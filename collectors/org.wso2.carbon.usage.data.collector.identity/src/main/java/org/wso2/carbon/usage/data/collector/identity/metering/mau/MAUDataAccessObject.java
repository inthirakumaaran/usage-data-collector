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

package org.wso2.carbon.usage.data.collector.identity.metering.mau;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.usage.data.collector.identity.metering.common.UsageTrackingException;
import org.wso2.carbon.usage.data.collector.identity.util.UsageDataSourceProvider;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

/**
 * Data Access Object for the {@code IDN_MAU_COUNT} table.
 * <p>
 * Stores one login row per {@code (USER_ID, TENANT_DOMAIN, MONTH_YEAR)}. The usage-count
 * collector reads this table to count distinct users per tenant and publishes the result
 * through the usage-data receiver.
 *
 * <h2>Table: IDN_MAU_COUNT</h2>
 * <pre>
 * CREATE TABLE IDN_MAU_COUNT (
 *     USER_ID       VARCHAR(255) NOT NULL,
 *     TENANT_DOMAIN VARCHAR(256) NOT NULL,
 *     MONTH_YEAR    VARCHAR(6)   NOT NULL,  -- MMYYYY, e.g. "072026"
 *     LAST_LOGIN    BIGINT       NOT NULL,  -- epoch millis of most-recent login this month
 *     PRIMARY KEY (USER_ID, TENANT_DOMAIN, MONTH_YEAR)
 * );
 * </pre>
 */
public class MAUDataAccessObject {

    private static final Logger LOG = LoggerFactory.getLogger(MAUDataAccessObject.class);

    private static final String SQL_UPSERT_TRACKING =
            "INSERT INTO IDN_MAU_COUNT (USER_ID, TENANT_DOMAIN, MONTH_YEAR, LAST_LOGIN) " +
            "VALUES (?, ?, ?, ?) " +
            "ON DUPLICATE KEY UPDATE LAST_LOGIN = GREATEST(LAST_LOGIN, VALUES(LAST_LOGIN))";

    private static final String SQL_PURGE_TRACKING =
            "DELETE FROM IDN_MAU_COUNT WHERE MONTH_YEAR = ?";

    private static final String SQL_COUNT_DISTINCT_USERS =
            "SELECT COUNT(DISTINCT USER_ID) FROM IDN_MAU_COUNT " +
            "WHERE TENANT_DOMAIN = ? AND MONTH_YEAR = ?";

    private static final String SQL_DISTINCT_TENANTS =
            "SELECT DISTINCT TENANT_DOMAIN FROM IDN_MAU_COUNT WHERE MONTH_YEAR = ?";

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Batch-upserts login entries into {@code IDN_MAU_COUNT}.
     *
     * @param entries   {@code "userId:tenantDomain" -> lastLoginMillis}.
     * @param monthYear MMYYYY string.
     * @throws UsageTrackingException on any SQL failure.
     */
    public void batchUpsertLogins(Map<String, Long> entries, String monthYear)
            throws UsageTrackingException {

        if (entries == null || entries.isEmpty()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("[MAU-DAO] Nothing to flush for month {}.", monthYear);
            }
            return;
        }

        Connection conn = null;
        try {
            conn = getWriteConnection();
            try (PreparedStatement ps = conn.prepareStatement(SQL_UPSERT_TRACKING)) {
                for (Map.Entry<String, Long> entry : entries.entrySet()) {
                    String key = entry.getKey();
                    int sep = key.lastIndexOf(MAUCacheManager.KEY_SEPARATOR);
                    if (sep <= 0 || sep == key.length() - 1) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("[MAU-DAO] Skipping malformed key: {}", key);
                        }
                        continue;
                    }
                    ps.setString(1, key.substring(0, sep));
                    ps.setString(2, key.substring(sep + 1));
                    ps.setString(3, monthYear);
                    ps.setLong(4, entry.getValue());
                    ps.addBatch();
                }
                int[] results = ps.executeBatch();
                conn.commit();
                if (LOG.isDebugEnabled()) {
                    LOG.debug("[MAU-DAO] Flushed {} entries to IDN_MAU_COUNT for month {}.",
                            results.length, monthYear);
                }
            }
        } catch (SQLException e) {
            rollback(conn);
            throw new UsageTrackingException(
                    "Failed to batch-upsert MAU tracking entries for month " + monthYear, e);
        } finally {
            closeQuietly(conn);
        }
    }

    /**
     * Deletes {@code IDN_MAU_COUNT} rows for months older than {@code maxAgeDays} from today.
     *
     * @throws UsageTrackingException on any SQL failure.
     */
    public void purgeOldEntries(int maxAgeDays) throws UsageTrackingException {
        List<String> expiredMonths = buildExpiredMonthList(maxAgeDays);
        if (expiredMonths.isEmpty()) {
            return;
        }

        Connection conn = null;
        try {
            conn = getWriteConnection();
            try (PreparedStatement ps = conn.prepareStatement(SQL_PURGE_TRACKING)) {
                for (String month : expiredMonths) {
                    ps.setString(1, month);
                    ps.addBatch();
                }
                int[] results = ps.executeBatch();
                conn.commit();
                if (LOG.isDebugEnabled()) {
                    LOG.debug("[MAU-DAO] Purged rows for {} expired month(s): {}",
                            results.length, expiredMonths);
                }
            }
        } catch (SQLException e) {
            rollback(conn);
            throw new UsageTrackingException("Failed to purge old MAU tracking entries.", e);
        } finally {
            closeQuietly(conn);
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Counts distinct users who logged in for the given tenant and month.
     *
     * @throws UsageTrackingException on any SQL failure.
     */
    public int countDistinctUsers(String tenantDomain, String monthYear)
            throws UsageTrackingException {

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_COUNT_DISTINCT_USERS)) {

            ps.setString(1, tenantDomain);
            ps.setString(2, monthYear);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new UsageTrackingException(
                    "Failed to count distinct MAU users for tenant " + tenantDomain, e);
        }
    }

    /**
     * Returns all tenant domains that have login data for the given month.
     *
     * @throws UsageTrackingException on any SQL failure.
     */
    public List<String> getDistinctTenants(String monthYear) throws UsageTrackingException {
        List<String> tenants = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_DISTINCT_TENANTS)) {

            ps.setString(1, monthYear);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tenants.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new UsageTrackingException(
                    "Failed to query distinct tenants for month " + monthYear, e);
        }
        return tenants;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Connection getConnection() throws SQLException {
        DataSource dataSource = UsageDataSourceProvider.getDataSource();
        return dataSource.getConnection();
    }

    private static Connection getWriteConnection() throws SQLException {
        Connection conn = getConnection();
        conn.setAutoCommit(false);
        return conn;
    }

    private static void rollback(Connection conn) {
        if (conn != null) {
            try {
                conn.rollback();
            } catch (SQLException e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("[MAU-DAO] Failed to roll back transaction.", e);
                }
            }
        }
    }

    private static void closeQuietly(Connection conn) {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("[MAU-DAO] Failed to close connection.", e);
                }
            }
        }
    }

    private static List<String> buildExpiredMonthList(int maxAgeDays) {
        List<String> result = new ArrayList<>();
        LocalDate today = LocalDate.now();
        LocalDate threshold = today.minusDays(maxAgeDays);
        for (int i = 1; i <= 24; i++) {
            LocalDate candidate = today.minusMonths(i).with(TemporalAdjusters.firstDayOfMonth());
            if (candidate.isBefore(threshold) || candidate.isEqual(threshold)) {
                result.add(String.format("%02d%04d", candidate.getMonthValue(), candidate.getYear()));
            }
        }
        return result;
    }
}
