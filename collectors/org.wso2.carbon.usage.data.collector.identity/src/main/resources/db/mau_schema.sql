-- ============================================================
-- MAU tracking schema for the WSO2 Identity Server usage collector.
-- Apply to the WSO2 Identity DB, or to the datasource named by
-- UsageTracking.DataSourceName if a custom one is configured.
-- ============================================================

-- ── MAU Login Tracking ────────────────────────────────────────────────────────
-- One row per user per month. Purpose: count DISTINCT users for MAU across the
-- cluster — identity is needed here, so all nodes write to this shared table and
-- only the coordinator publishes the distinct count.
-- Retention: configurable (default 60 days), purged by MAUFlushTask at month-end.
CREATE TABLE IF NOT EXISTS IDN_MAU_COUNT (
    ID            BIGINT        NOT NULL AUTO_INCREMENT,
    USER_ID       VARCHAR(255)  NOT NULL,
    TENANT_DOMAIN VARCHAR(255)  NOT NULL,
    MONTH_YEAR    CHAR(6)       NOT NULL,   -- Format: MMYYYY  e.g. "062026"
    LAST_LOGIN    BIGINT        NOT NULL,   -- Epoch millis of most recent login this month
    PRIMARY KEY (ID),
    UNIQUE KEY UK_IDN_MAU_COUNT (USER_ID, TENANT_DOMAIN, MONTH_YEAR),
    INDEX IDX_IDN_MAU_COUNT_MONTH (MONTH_YEAR)
);
