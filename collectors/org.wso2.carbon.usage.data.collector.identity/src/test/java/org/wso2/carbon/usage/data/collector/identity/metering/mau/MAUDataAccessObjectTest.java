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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.wso2.carbon.usage.data.collector.identity.util.UsageDataSourceProvider;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Integration tests for {@link MAUDataAccessObject} using an in-memory H2 database that
 * mimics MySQL with {@code MODE=MySQL}.
 */
class MAUDataAccessObjectTest {

    private static final String H2_URL = "jdbc:h2:mem:usage_test;DB_CLOSE_DELAY=-1;MODE=MySQL";

    private Connection schemaConn;
    private MAUDataAccessObject dao;
    private MockedStatic<UsageDataSourceProvider> mockProvider;

    @BeforeEach
    void setUp() throws Exception {
        schemaConn = DriverManager.getConnection(H2_URL, "sa", "");
        createSchema(schemaConn);
        dao = new MAUDataAccessObject();

        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenAnswer(inv -> DriverManager.getConnection(H2_URL, "sa", ""));

        mockProvider = mockStatic(UsageDataSourceProvider.class);
        mockProvider.when(UsageDataSourceProvider::getDataSource).thenReturn(dataSource);
    }

    @AfterEach
    void tearDown() throws Exception {
        mockProvider.close();
        try (Statement st = schemaConn.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
        schemaConn.close();
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void testBatchUpsertInsertsRows() throws Exception {
        Map<String, Long> entries = new HashMap<>();
        entries.put("u1:alpha.com", System.currentTimeMillis());
        entries.put("u2:alpha.com", System.currentTimeMillis());
        dao.batchUpsertLogins(entries, "062026");

        assertEquals(2, queryLong("SELECT COUNT(*) FROM IDN_MAU_COUNT WHERE MONTH_YEAR='062026'"));
    }

    @Test
    void testBatchUpsertUpdatesLastLogin() throws Exception {
        Map<String, Long> first = new HashMap<>();
        first.put("u1:beta.com", 1000L);
        dao.batchUpsertLogins(first, "062026");

        Map<String, Long> second = new HashMap<>();
        second.put("u1:beta.com", 9999L);
        dao.batchUpsertLogins(second, "062026");

        long ts = queryLong("SELECT LAST_LOGIN FROM IDN_MAU_COUNT "
                + "WHERE USER_ID='u1' AND TENANT_DOMAIN='beta.com'");
        assertEquals(9999L, ts, "LAST_LOGIN should be updated to the larger value");
    }

    @Test
    void testBatchUpsertEmptyMapDoesNothing() throws Exception {
        dao.batchUpsertLogins(new HashMap<>(), "062026");
        assertEquals(0, queryLong("SELECT COUNT(*) FROM IDN_MAU_COUNT"));
    }

    @Test
    void testCountDistinctUsers() throws Exception {
        Map<String, Long> entries = new HashMap<>();
        entries.put("u1:acme.com", System.currentTimeMillis());
        entries.put("u2:acme.com", System.currentTimeMillis());
        entries.put("u1:other.com", System.currentTimeMillis());
        dao.batchUpsertLogins(entries, "062026");

        assertEquals(2, dao.countDistinctUsers("acme.com", "062026"),
                "Should count only distinct users for acme.com");
    }

    @Test
    void testCountDistinctUsersReturnsZeroWhenNoData() throws Exception {
        assertEquals(0, dao.countDistinctUsers("nobody.com", "012000"));
    }

    @Test
    void testGetDistinctTenants() throws Exception {
        Map<String, Long> entries = new HashMap<>();
        entries.put("u1:alpha.com", System.currentTimeMillis());
        entries.put("u1:beta.com", System.currentTimeMillis());
        dao.batchUpsertLogins(entries, "032026");

        List<String> tenants = dao.getDistinctTenants("032026");
        assertEquals(2, tenants.size());
        assertTrue(tenants.contains("alpha.com"));
        assertTrue(tenants.contains("beta.com"));
    }

    @Test
    void testPurgeOldEntriesRemovesExpiredRows() throws Exception {
        // Use the previous calendar month — always within the 24-month scan window
        // and always older than 1 day from today.
        String expiredMonth = LocalDate.now().minusMonths(1).format(DateTimeFormatter.ofPattern("MMyyyy"));
        try (Statement st = schemaConn.createStatement()) {
            st.execute("INSERT INTO IDN_MAU_COUNT (USER_ID, TENANT_DOMAIN, MONTH_YEAR, LAST_LOGIN) "
                    + "VALUES ('u1','old.com','" + expiredMonth + "',1)");
            schemaConn.commit();
        }

        dao.purgeOldEntries(1);

        assertEquals(0, queryLong(
                "SELECT COUNT(*) FROM IDN_MAU_COUNT WHERE MONTH_YEAR='" + expiredMonth + "'"),
                "Expired rows should be purged");
    }

    @Test
    void testMalformedCacheKeyIsSkipped() throws Exception {
        Map<String, Long> entries = new HashMap<>();
        entries.put("malformed-no-colon", 123L);
        entries.put("u1:good.com", 456L);
        dao.batchUpsertLogins(entries, "062026");

        assertEquals(1, queryLong("SELECT COUNT(*) FROM IDN_MAU_COUNT"),
                "Malformed key should be skipped; valid key should be inserted");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void createSchema(Connection c) throws Exception {
        try (Statement st = c.createStatement()) {
            st.execute(
                    "CREATE TABLE IF NOT EXISTS IDN_MAU_COUNT ("
                    + "  ID BIGINT NOT NULL AUTO_INCREMENT,"
                    + "  USER_ID VARCHAR(255) NOT NULL,"
                    + "  TENANT_DOMAIN VARCHAR(255) NOT NULL,"
                    + "  MONTH_YEAR CHAR(6) NOT NULL,"
                    + "  LAST_LOGIN BIGINT NOT NULL,"
                    + "  PRIMARY KEY (ID),"
                    + "  UNIQUE KEY UK_MAU_TRACK (USER_ID, TENANT_DOMAIN, MONTH_YEAR)"
                    + ")");
        }
        c.setAutoCommit(false);
    }

    private long queryLong(String sql) throws Exception {
        try (Statement st = schemaConn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }
}
