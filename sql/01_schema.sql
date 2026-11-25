-- SCHEMA CHINH cua Accounting System: day la nguon dinh nghia SQL duy nhat.
-- Tao database bang sql/00_create_database.sql, sau do chon database truoc khi chay file nay.
-- JDBC doc cac khoi BOOTSTRAP, V001, V002, V003, V004 tu chinh file nay. Maven dong goi file vao JAR.
-- Giu nguyen noi dung khoi da ap dung. Thay doi schema sau nay can migration moi.
-- Chay bang MySQL client voi che do dung khi gap loi (khong dung --force).
SET NAMES utf8mb4;
SET SESSION default_storage_engine = 'InnoDB';
SET @acc_schema_install_lock = GET_LOCK('accounting_system_schema', 30);

-- BEGIN BOOTSTRAP
CREATE TABLE IF NOT EXISTS acc_schema_history (
    version_no INT PRIMARY KEY,
    description VARCHAR(120) NOT NULL,
    checksum VARCHAR(64) NOT NULL,
    success BOOLEAN NOT NULL,
    started_at VARCHAR(35) NOT NULL,
    completed_at VARCHAR(35)
);
-- END BOOTSTRAP

-- Checksum cac khoi ben duoi, duoc kiem tra tu dong trong test.
SET @acc_v001_checksum = '728f47e7fb96c36c067950034e4e3ec0a419d96e995b2f9ec4fd9bdd8c0e4f17';
SET @acc_v002_checksum = '411d26653afa0db84156adae09145240753103ccc91785a3091b485935bf8c5d';
SET @acc_v003_checksum = '258908c92725be54e4f8613e399a0c5b29d891a50e64a532379001e262ce148f';
SET @acc_v004_checksum = '7bea31179c57752813d6f11e34c8494525bdb688d3d8f03f669d0185d6f186bc';

-- Chi khoi tao database trong hoac tiep tuc schema co lich su hop le.
-- Neu co migration dang loi, dung lenh --db=resume-migration sau khi kiem tra.
SET @acc_schema_install_allowed =
    @acc_schema_install_lock = 1
    AND NOT EXISTS (
        SELECT 1 FROM acc_schema_history
        WHERE success = FALSE OR version_no NOT IN (1, 2, 3, 4)
           OR checksum <> CASE version_no
               WHEN 1 THEN @acc_v001_checksum
               WHEN 2 THEN @acc_v002_checksum
               WHEN 3 THEN @acc_v003_checksum
               WHEN 4 THEN @acc_v004_checksum
               ELSE ''
           END
    )
    AND (
        NOT EXISTS (SELECT 1 FROM acc_schema_history WHERE version_no = 2)
        OR EXISTS (SELECT 1 FROM acc_schema_history WHERE version_no = 1)
    )
    AND (
        NOT EXISTS (SELECT 1 FROM acc_schema_history WHERE version_no = 3)
        OR EXISTS (SELECT 1 FROM acc_schema_history WHERE version_no = 2)
    )
    AND (
        NOT EXISTS (SELECT 1 FROM acc_schema_history WHERE version_no = 4)
        OR EXISTS (SELECT 1 FROM acc_schema_history WHERE version_no = 3)
    )
    AND (
        EXISTS (SELECT 1 FROM acc_schema_history)
        OR NOT EXISTS (
            SELECT 1 FROM information_schema.tables
            WHERE table_schema = DATABASE()
              AND LEFT(table_name, 4) = 'acc_'
              AND table_name <> 'acc_schema_history'
        )
    );
CREATE TEMPORARY TABLE IF NOT EXISTS acc_schema_install_guard (
    allowed INT NOT NULL,
    CONSTRAINT ck_acc_install_ready CHECK (allowed = 1)
);
INSERT INTO acc_schema_install_guard (allowed) VALUES (@acc_schema_install_allowed);

-- Danh dau truoc DDL de loi giua chung khong bi coi la da cai dat thanh cong.
INSERT INTO acc_schema_history (version_no, description, checksum, success, started_at)
SELECT 1, 'Catalogs and vouchers', @acc_v001_checksum, FALSE,
       DATE_FORMAT(UTC_TIMESTAMP(6), '%Y-%m-%dT%H:%i:%s.%fZ')
WHERE @acc_schema_install_allowed = 1
  AND NOT EXISTS (SELECT 1 FROM acc_schema_history WHERE version_no = 1);

-- BEGIN V001
-- Audit instants use ISO-8601 UTC text to preserve Java nanoseconds exactly.
-- Posting/report dates are native SQL DATE values. Money is whole VND.
CREATE TABLE IF NOT EXISTS acc_transaction_lock (
    id INT PRIMARY KEY,
    CHECK (id = 1)
);
INSERT INTO acc_transaction_lock (id)
SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM acc_transaction_lock WHERE id = 1);
CREATE TABLE IF NOT EXISTS acc_accounts (
    code VARCHAR(20) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    account_type VARCHAR(16) NOT NULL,
    normal_balance VARCHAR(6) NOT NULL,
    parent_code VARCHAR(20),
    posting_allowed BOOLEAN NOT NULL,
    tracking VARCHAR(16) NOT NULL,
    active BOOLEAN NOT NULL,
    FOREIGN KEY (parent_code) REFERENCES acc_accounts(code),
    CHECK (account_type IN ('ASSET','LIABILITY','EQUITY','REVENUE','EXPENSE','RESULT')),
    CHECK (normal_balance IN ('DEBIT','CREDIT')),
    CHECK (tracking IN ('NONE','PARTNER','MONEY_ACCOUNT')),
    CHECK (posting_allowed OR tracking = 'NONE')
);
CREATE TABLE IF NOT EXISTS acc_partners (
    code VARCHAR(32) PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    is_customer BOOLEAN NOT NULL,
    is_supplier BOOLEAN NOT NULL,
    active BOOLEAN NOT NULL,
    CHECK (is_customer OR is_supplier)
);
CREATE TABLE IF NOT EXISTS acc_money_accounts (
    code VARCHAR(32) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    ledger_account_code VARCHAR(20) NOT NULL,
    kind VARCHAR(4) NOT NULL,
    active BOOLEAN NOT NULL,
    UNIQUE (code, ledger_account_code),
    FOREIGN KEY (ledger_account_code) REFERENCES acc_accounts(code),
    CHECK (kind IN ('CASH','BANK'))
);
CREATE TABLE IF NOT EXISTS acc_periods (
    month_key VARCHAR(7) PRIMARY KEY,
    status VARCHAR(7) NOT NULL,
    CHECK (status IN ('OPEN','CLOSING','CLOSED'))
);
CREATE TABLE IF NOT EXISTS acc_users (
    id BIGINT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(80) NOT NULL,
    active BOOLEAN NOT NULL,
    CHECK (id > 0)
);
CREATE TABLE IF NOT EXISTS acc_user_roles (
    user_id BIGINT NOT NULL,
    role_code VARCHAR(10) NOT NULL,
    PRIMARY KEY (user_id, role_code),
    FOREIGN KEY (user_id) REFERENCES acc_users(id),
    CHECK (role_code IN ('ACCOUNTANT','CHIEF','VIEWER','ADMIN'))
);
CREATE TABLE IF NOT EXISTS acc_vouchers (
    id VARCHAR(36) PRIMARY KEY,
    reference VARCHAR(32) NOT NULL UNIQUE,
    voucher_type VARCHAR(7) NOT NULL,
    document_date DATE NOT NULL,
    posting_date DATE NOT NULL,
    description VARCHAR(255) NOT NULL,
    created_by BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    version_no BIGINT NOT NULL,
    created_at VARCHAR(35) NOT NULL,
    updated_at VARCHAR(35) NOT NULL,
    FOREIGN KEY (created_by) REFERENCES acc_users(id),
    CHECK (version_no >= 0),
    CHECK (voucher_type IN ('REVENUE','EXPENSE','RECEIPT','PAYMENT','GENERAL')),
    CHECK (status IN ('DRAFT','PENDING_APPROVAL','RETURNED','APPROVED','POSTED','CANCELLED'))
);
-- Drafts may be incomplete. Catalog FKs are enforced on the posted journal instead.
CREATE TABLE IF NOT EXISTS acc_voucher_lines (
    voucher_id VARCHAR(36) NOT NULL,
    line_no INT NOT NULL,
    account_code VARCHAR(20) NOT NULL,
    side VARCHAR(6) NOT NULL,
    amount DECIMAL(19,0) NOT NULL,
    partner_code VARCHAR(32),
    money_account_code VARCHAR(32),
    PRIMARY KEY (voucher_id, line_no),
    FOREIGN KEY (voucher_id) REFERENCES acc_vouchers(id),
    CHECK (line_no > 0 AND amount > 0),
    CHECK (side IN ('DEBIT','CREDIT'))
);
CREATE TABLE IF NOT EXISTS acc_voucher_history (
    voucher_id VARCHAR(36) NOT NULL,
    version_no BIGINT NOT NULL,
    action_code VARCHAR(20) NOT NULL,
    actor_id BIGINT NOT NULL,
    from_status VARCHAR(16),
    to_status VARCHAR(16) NOT NULL,
    occurred_at VARCHAR(35) NOT NULL,
    note VARCHAR(255) NOT NULL,
    snapshot_type VARCHAR(7) NOT NULL,
    snapshot_reference VARCHAR(32) NOT NULL,
    document_date DATE NOT NULL,
    posting_date DATE NOT NULL,
    description VARCHAR(255) NOT NULL,
    PRIMARY KEY (voucher_id, version_no),
    FOREIGN KEY (voucher_id) REFERENCES acc_vouchers(id),
    FOREIGN KEY (actor_id) REFERENCES acc_users(id),
    CHECK (version_no >= 0)
);
CREATE TABLE IF NOT EXISTS acc_history_lines (
    voucher_id VARCHAR(36) NOT NULL,
    version_no BIGINT NOT NULL,
    line_no INT NOT NULL,
    account_code VARCHAR(20) NOT NULL,
    side VARCHAR(6) NOT NULL,
    amount DECIMAL(19,0) NOT NULL,
    partner_code VARCHAR(32),
    money_account_code VARCHAR(32),
    PRIMARY KEY (voucher_id, version_no, line_no),
    FOREIGN KEY (voucher_id, version_no) REFERENCES acc_voucher_history(voucher_id, version_no),
    CHECK (line_no > 0 AND amount > 0),
    CHECK (side IN ('DEBIT','CREDIT'))
);
-- END V001

UPDATE acc_schema_history
SET success = TRUE, completed_at = DATE_FORMAT(UTC_TIMESTAMP(6), '%Y-%m-%dT%H:%i:%s.%fZ')
WHERE version_no = 1 AND checksum = @acc_v001_checksum AND success = FALSE
  AND @acc_schema_install_allowed = 1;

INSERT INTO acc_schema_history (version_no, description, checksum, success, started_at)
SELECT 2, 'Journal and allocations', @acc_v002_checksum, FALSE,
       DATE_FORMAT(UTC_TIMESTAMP(6), '%Y-%m-%dT%H:%i:%s.%fZ')
WHERE @acc_schema_install_allowed = 1
  AND NOT EXISTS (SELECT 1 FROM acc_schema_history WHERE version_no = 2);

-- BEGIN V002
CREATE TABLE IF NOT EXISTS acc_journal_entries (
    id VARCHAR(36) PRIMARY KEY,
    source_document_id VARCHAR(36) NOT NULL UNIQUE,
    source_version BIGINT NOT NULL,
    voucher_type VARCHAR(7) NOT NULL,
    reference VARCHAR(32) NOT NULL UNIQUE,
    document_date DATE NOT NULL,
    posting_date DATE NOT NULL,
    description VARCHAR(255) NOT NULL,
    approved_by BIGINT NOT NULL,
    approved_at VARCHAR(35) NOT NULL,
    posted_by BIGINT NOT NULL,
    posted_at VARCHAR(35) NOT NULL,
    FOREIGN KEY (source_document_id) REFERENCES acc_vouchers(id),
    FOREIGN KEY (approved_by) REFERENCES acc_users(id),
    FOREIGN KEY (posted_by) REFERENCES acc_users(id),
    CHECK (source_version >= 0),
    CHECK (voucher_type IN ('REVENUE','EXPENSE','RECEIPT','PAYMENT','GENERAL'))
);
CREATE TABLE IF NOT EXISTS acc_journal_lines (
    entry_id VARCHAR(36) NOT NULL,
    line_no INT NOT NULL,
    account_code VARCHAR(20) NOT NULL,
    side VARCHAR(6) NOT NULL,
    amount DECIMAL(19,0) NOT NULL,
    partner_code VARCHAR(32),
    money_account_code VARCHAR(32),
    PRIMARY KEY (entry_id, line_no),
    FOREIGN KEY (entry_id) REFERENCES acc_journal_entries(id),
    FOREIGN KEY (account_code) REFERENCES acc_accounts(code),
    FOREIGN KEY (partner_code) REFERENCES acc_partners(code),
    FOREIGN KEY (money_account_code, account_code) REFERENCES acc_money_accounts(code, ledger_account_code),
    CHECK (line_no > 0 AND amount > 0),
    CHECK (side IN ('DEBIT','CREDIT'))
);
CREATE TABLE IF NOT EXISTS acc_debt_allocations (
    id VARCHAR(36) PRIMARY KEY,
    charge_entry_id VARCHAR(36) NOT NULL,
    charge_line_no INT NOT NULL,
    settlement_entry_id VARCHAR(36) NOT NULL,
    settlement_line_no INT NOT NULL,
    amount DECIMAL(19,0) NOT NULL,
    effective_date DATE NOT NULL,
    note VARCHAR(255) NOT NULL,
    recorded_by BIGINT NOT NULL,
    recorded_at VARCHAR(35) NOT NULL,
    FOREIGN KEY (charge_entry_id, charge_line_no) REFERENCES acc_journal_lines(entry_id, line_no),
    FOREIGN KEY (settlement_entry_id, settlement_line_no) REFERENCES acc_journal_lines(entry_id, line_no),
    FOREIGN KEY (recorded_by) REFERENCES acc_users(id),
    CHECK (amount > 0)
);
CREATE TABLE IF NOT EXISTS acc_allocation_cancellations (
    allocation_id VARCHAR(36) PRIMARY KEY,
    effective_date DATE NOT NULL,
    reason VARCHAR(255) NOT NULL,
    recorded_by BIGINT NOT NULL,
    recorded_at VARCHAR(35) NOT NULL,
    FOREIGN KEY (allocation_id) REFERENCES acc_debt_allocations(id),
    FOREIGN KEY (recorded_by) REFERENCES acc_users(id),
    CHECK (CHAR_LENGTH(TRIM(reason)) > 0)
);
-- END V002

UPDATE acc_schema_history
SET success = TRUE, completed_at = DATE_FORMAT(UTC_TIMESTAMP(6), '%Y-%m-%dT%H:%i:%s.%fZ')
WHERE version_no = 2 AND checksum = @acc_v002_checksum AND success = FALSE
  AND @acc_schema_install_allowed = 1;

INSERT INTO acc_schema_history (version_no, description, checksum, success, started_at)
SELECT 3, 'Corrections and prepaid expenses', @acc_v003_checksum, FALSE,
       DATE_FORMAT(UTC_TIMESTAMP(6), '%Y-%m-%dT%H:%i:%s.%fZ')
WHERE @acc_schema_install_allowed = 1
  AND NOT EXISTS (SELECT 1 FROM acc_schema_history WHERE version_no = 3);

-- BEGIN V003
CREATE TABLE IF NOT EXISTS acc_corrections (
    id VARCHAR(36) PRIMARY KEY,
    source_document_id VARCHAR(36) NOT NULL,
    target_document_id VARCHAR(36) NOT NULL UNIQUE,
    kind VARCHAR(10) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at VARCHAR(35) NOT NULL,
    FOREIGN KEY (source_document_id) REFERENCES acc_vouchers(id),
    FOREIGN KEY (target_document_id) REFERENCES acc_vouchers(id),
    FOREIGN KEY (created_by) REFERENCES acc_users(id),
    CHECK (kind IN ('ADJUSTMENT','REVERSAL')),
    CHECK (source_document_id <> target_document_id)
);
CREATE TABLE IF NOT EXISTS acc_prepaid_plans (
    id VARCHAR(36) PRIMARY KEY,
    reference VARCHAR(32) NOT NULL UNIQUE,
    source_entry_id VARCHAR(36) NOT NULL,
    source_line_no INT NOT NULL,
    expense_account_code VARCHAR(20) NOT NULL,
    first_month VARCHAR(7) NOT NULL,
    month_count INT NOT NULL,
    created_by BIGINT NOT NULL,
    created_at VARCHAR(35) NOT NULL,
    FOREIGN KEY (source_entry_id, source_line_no) REFERENCES acc_journal_lines(entry_id, line_no),
    FOREIGN KEY (expense_account_code) REFERENCES acc_accounts(code),
    FOREIGN KEY (created_by) REFERENCES acc_users(id),
    CHECK (month_count BETWEEN 1 AND 120)
);
CREATE TABLE IF NOT EXISTS acc_prepaid_schedule (
    plan_id VARCHAR(36) NOT NULL,
    month_key VARCHAR(7) NOT NULL,
    amount DECIMAL(19,0) NOT NULL,
    PRIMARY KEY (plan_id, month_key),
    FOREIGN KEY (plan_id) REFERENCES acc_prepaid_plans(id),
    CHECK (amount > 0)
);
CREATE TABLE IF NOT EXISTS acc_prepaid_allocations (
    id VARCHAR(36) PRIMARY KEY,
    plan_id VARCHAR(36) NOT NULL,
    month_key VARCHAR(7) NOT NULL,
    target_document_id VARCHAR(36) NOT NULL UNIQUE,
    created_by BIGINT NOT NULL,
    created_at VARCHAR(35) NOT NULL,
    FOREIGN KEY (plan_id, month_key) REFERENCES acc_prepaid_schedule(plan_id, month_key),
    FOREIGN KEY (target_document_id) REFERENCES acc_vouchers(id),
    FOREIGN KEY (created_by) REFERENCES acc_users(id)
);
CREATE TABLE IF NOT EXISTS acc_prepaid_cancellations (
    plan_id VARCHAR(36) PRIMARY KEY,
    effective_date DATE NOT NULL,
    reason VARCHAR(255) NOT NULL,
    recorded_by BIGINT NOT NULL,
    recorded_at VARCHAR(35) NOT NULL,
    FOREIGN KEY (plan_id) REFERENCES acc_prepaid_plans(id),
    FOREIGN KEY (recorded_by) REFERENCES acc_users(id),
    CHECK (CHAR_LENGTH(TRIM(reason)) > 0)
);
-- END V003

UPDATE acc_schema_history
SET success = TRUE, completed_at = DATE_FORMAT(UTC_TIMESTAMP(6), '%Y-%m-%dT%H:%i:%s.%fZ')
WHERE version_no = 3 AND checksum = @acc_v003_checksum AND success = FALSE
  AND @acc_schema_install_allowed = 1;

INSERT INTO acc_schema_history (version_no, description, checksum, success, started_at)
SELECT 4, 'Period closing', @acc_v004_checksum, FALSE,
       DATE_FORMAT(UTC_TIMESTAMP(6), '%Y-%m-%dT%H:%i:%s.%fZ')
WHERE @acc_schema_install_allowed = 1
  AND NOT EXISTS (SELECT 1 FROM acc_schema_history WHERE version_no = 4);

-- BEGIN V004
CREATE TABLE IF NOT EXISTS acc_closing_batches (
    id VARCHAR(36) PRIMARY KEY,
    reference VARCHAR(28) NOT NULL UNIQUE,
    month_key VARCHAR(7) NOT NULL,
    result_account_code VARCHAR(20) NOT NULL,
    retained_account_code VARCHAR(20) NOT NULL,
    source_checksum VARCHAR(64) NOT NULL,
    revenue DECIMAL(19,0) NOT NULL,
    expense DECIMAL(19,0) NOT NULL,
    profit DECIMAL(19,0) NOT NULL,
    prepared_by BIGINT NOT NULL,
    prepared_at VARCHAR(35) NOT NULL,
    FOREIGN KEY (month_key) REFERENCES acc_periods(month_key),
    FOREIGN KEY (result_account_code) REFERENCES acc_accounts(code),
    FOREIGN KEY (retained_account_code) REFERENCES acc_accounts(code),
    FOREIGN KEY (prepared_by) REFERENCES acc_users(id),
    CHECK (result_account_code <> retained_account_code),
    CHECK (profit = revenue - expense)
);
CREATE TABLE IF NOT EXISTS acc_closing_sources (
    batch_id VARCHAR(36) NOT NULL,
    position_no INT NOT NULL,
    entry_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (batch_id,position_no),
    UNIQUE (batch_id,entry_id),
    FOREIGN KEY (batch_id) REFERENCES acc_closing_batches(id),
    FOREIGN KEY (entry_id) REFERENCES acc_journal_entries(id),
    CHECK (position_no >= 0)
);
CREATE TABLE IF NOT EXISTS acc_closing_documents (
    batch_id VARCHAR(36) NOT NULL,
    position_no INT NOT NULL,
    document_id VARCHAR(36) NOT NULL UNIQUE,
    PRIMARY KEY (batch_id,position_no),
    FOREIGN KEY (batch_id) REFERENCES acc_closing_batches(id),
    FOREIGN KEY (document_id) REFERENCES acc_vouchers(id),
    CHECK (position_no BETWEEN 0 AND 2)
);
CREATE TABLE IF NOT EXISTS acc_closing_events (
    batch_id VARCHAR(36) NOT NULL,
    action VARCHAR(10) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    actor_id BIGINT NOT NULL,
    occurred_at VARCHAR(35) NOT NULL,
    PRIMARY KEY (batch_id,action),
    FOREIGN KEY (batch_id) REFERENCES acc_closing_batches(id),
    FOREIGN KEY (actor_id) REFERENCES acc_users(id),
    CHECK (action IN ('APPROVED','CLOSED','CANCELLED')),
    CHECK (CHAR_LENGTH(TRIM(reason)) > 0)
);
-- END V004

UPDATE acc_schema_history
SET success = TRUE, completed_at = DATE_FORMAT(UTC_TIMESTAMP(6), '%Y-%m-%dT%H:%i:%s.%fZ')
WHERE version_no = 4 AND checksum = @acc_v004_checksum AND success = FALSE
  AND @acc_schema_install_allowed = 1;

DROP TEMPORARY TABLE IF EXISTS acc_schema_install_guard;
DO RELEASE_LOCK('accounting_system_schema');
SELECT version_no, description, success FROM acc_schema_history ORDER BY version_no;
