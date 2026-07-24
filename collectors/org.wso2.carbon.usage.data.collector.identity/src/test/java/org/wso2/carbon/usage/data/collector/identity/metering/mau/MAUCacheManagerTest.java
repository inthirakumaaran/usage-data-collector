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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MAUCacheManagerTest {

    @BeforeEach
    void resetSingleton() throws Exception {
        Field field = MAUCacheManager.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);
    }

    @Test
    void testSingletonReturnsSameInstance() {
        assertSame(MAUCacheManager.getInstance(), MAUCacheManager.getInstance());
    }

    @Test
    void testRecordLoginUpdatesCache() {
        MAUCacheManager cache = MAUCacheManager.getInstance();
        cache.recordLogin("user1", "example.com");
        assertEquals(1, cache.size());
    }

    @Test
    void testRecordLoginKeepsLatestTimestamp() throws InterruptedException {
        MAUCacheManager cache = MAUCacheManager.getInstance();
        cache.recordLogin("user1", "example.com");
        Thread.sleep(5);
        cache.recordLogin("user1", "example.com");
        assertEquals(1, cache.size(), "Same user same month should still be one entry");
    }

    @Test
    void testSnapshotGroupedByMonthContainsEntry() {
        MAUCacheManager cache = MAUCacheManager.getInstance();
        cache.recordLogin("alice", "corp.com");
        Map<String, Map<String, Long>> grouped = cache.snapshotGroupedByMonth();
        Map<String, Long> currentMonth = grouped.get(MAUCacheManager.currentMonthYear());
        assertTrue(currentMonth.containsKey(MAUCacheManager.buildKey("alice", "corp.com")));
    }

    @Test
    void testSnapshotGroupedByMonthPartitionsCorrectly() {
        MAUCacheManager cache = MAUCacheManager.getInstance();
        cache.recordLogin("u1", "a.com");
        cache.recordLogin("u2", "b.com");
        String month = MAUCacheManager.currentMonthYear();
        Map<String, Map<String, Long>> grouped = cache.snapshotGroupedByMonth();
        assertTrue(grouped.containsKey(month));
        assertEquals(2, grouped.get(month).size());
    }

    @Test
    void testBuildKeyFormat() {
        assertEquals("user:tenant", MAUCacheManager.buildKey("user", "tenant"));
    }

    @Test
    void testPurgeExpiredDoesNotRemoveCurrentMonth() {
        MAUCacheManager cache = MAUCacheManager.getInstance();
        cache.recordLogin("u1", "a.com");
        cache.purgeExpiredEntries(1);
        assertEquals(1, cache.size(), "Current month entry should not be purged");
    }

    @Test
    void testSizeReflectsRecordedLogins() {
        MAUCacheManager cache = MAUCacheManager.getInstance();
        cache.recordLogin("u1", "x.com");
        cache.recordLogin("u2", "x.com");
        assertEquals(2, cache.size());
    }
}
