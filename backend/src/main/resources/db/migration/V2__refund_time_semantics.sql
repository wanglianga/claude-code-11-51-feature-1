-- ============================================================
-- V2：用途绑定的退款时间口径
--   退款事实保留原订单/订单行、实际入账日、原交易日、金额方向、数量、来源修订；
--   退款归属明细账按口径版本 + 修订号追加，运行固定引用修订号；
--   指标模板必须带“财务发生/运营订单”语义参数，运行、审批、报表、发布清单均固定该参数与口径版本。
-- ============================================================

-- ---------- 退款事实：补齐原订单行、原交易日、方向、数量、来源修订 ----------
ALTER TABLE refund_fact ADD COLUMN IF NOT EXISTS order_line_id VARCHAR(64);
ALTER TABLE refund_fact ADD COLUMN IF NOT EXISTS original_business_date DATE;
ALTER TABLE refund_fact ADD COLUMN IF NOT EXISTS quantity INTEGER;
ALTER TABLE refund_fact ADD COLUMN IF NOT EXISTS direction SMALLINT NOT NULL DEFAULT 1; -- +1 退款扣减 | -1 退款撤销
ALTER TABLE refund_fact ADD COLUMN IF NOT EXISTS sku VARCHAR(32);
ALTER TABLE refund_fact ADD COLUMN IF NOT EXISTS link_status VARCHAR(16) NOT NULL DEFAULT 'unlinked'; -- linked | unlinked
ALTER TABLE refund_fact ADD COLUMN IF NOT EXISTS link_note VARCHAR(256);
ALTER TABLE refund_fact ADD COLUMN IF NOT EXISTS source_revision INTEGER NOT NULL DEFAULT 1;
CREATE INDEX IF NOT EXISTS idx_refund_order_line ON refund_fact(order_line_id);

-- 订单行/订单也保留来源修订，原订单行更正时据此生成新归属修订
ALTER TABLE order_line ADD COLUMN IF NOT EXISTS source_revision INTEGER NOT NULL DEFAULT 1;
ALTER TABLE order_fact ADD COLUMN IF NOT EXISTS source_revision INTEGER NOT NULL DEFAULT 1;

-- ---------- 退款时间口径（版本化） ----------
CREATE TABLE IF NOT EXISTS refund_semantics (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(64) NOT NULL,            -- REFUND_TIME
    version         INTEGER NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'draft',  -- draft | published | deprecated
    rules_json      JSONB NOT NULL,                  -- 两个用途的归属规则
    note            VARCHAR(512),
    created_by      VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    UNIQUE (code, version)
);

-- ---------- 用途档案：把“财务发生/运营订单”绑定到某一口径版本 ----------
CREATE TABLE IF NOT EXISTS purpose_profile (
    id              BIGSERIAL PRIMARY KEY,
    purpose         VARCHAR(32) NOT NULL UNIQUE,     -- financial | order_batch | reconciliation
    label           VARCHAR(64) NOT NULL,
    applicable_users VARCHAR(128) NOT NULL,
    semantics_id    BIGINT NOT NULL REFERENCES refund_semantics(id),
    profile_version INTEGER NOT NULL DEFAULT 1,      -- 乐观并发：并发修改用途必须带期望版本
    updated_by      VARCHAR(64),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------- 退款归属明细账（按口径版本 + 修订号追加，旧修订保留用于复演） ----------
CREATE TABLE IF NOT EXISTS refund_attribution (
    id                  BIGSERIAL PRIMARY KEY,
    semantics_id        BIGINT NOT NULL REFERENCES refund_semantics(id),
    revision            INTEGER NOT NULL,
    refund_id           VARCHAR(64) NOT NULL,
    order_id            VARCHAR(64) NOT NULL,
    order_line_id       VARCHAR(64),
    store_id            VARCHAR(32) NOT NULL,
    purpose             VARCHAR(32) NOT NULL,        -- financial | order_batch
    attribution_date    DATE,                        -- 归属期间日期；NULL 表示待归属（不得猜填）
    posting_date        DATE NOT NULL,               -- 实际入账日
    original_business_date DATE,                     -- 原交易日
    amount_signed       NUMERIC(16,2) NOT NULL,      -- amount * direction
    quantity            INTEGER,
    direction           SMALLINT NOT NULL DEFAULT 1,
    sku                 VARCHAR(32),
    link_status         VARCHAR(16) NOT NULL,        -- linked | unlinked
    attribution_status  VARCHAR(16) NOT NULL,        -- attributed | pending
    reason              VARCHAR(256),
    refund_source_revision INTEGER NOT NULL DEFAULT 1,
    line_source_revision   INTEGER,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (semantics_id, revision, purpose, refund_id)
);
CREATE INDEX IF NOT EXISTS idx_attr_lookup ON refund_attribution(semantics_id, revision, purpose, attribution_date);
CREATE INDEX IF NOT EXISTS idx_attr_pending ON refund_attribution(semantics_id, revision, purpose, attribution_status);

-- ---------- 指标定义版本：模板标记 + 固定用途 ----------
ALTER TABLE metric_version ADD COLUMN IF NOT EXISTS purpose_required BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE metric_version ADD COLUMN IF NOT EXISTS bound_purpose VARCHAR(32);

-- ---------- 依赖边：按用途固定 ----------
ALTER TABLE metric_edge ADD COLUMN IF NOT EXISTS purpose VARCHAR(32);
ALTER TABLE metric_edge ADD COLUMN IF NOT EXISTS semantics_id BIGINT;
ALTER TABLE metric_edge DROP CONSTRAINT IF EXISTS metric_edge_from_metric_to_metric_from_version_key;
ALTER TABLE metric_edge ADD CONSTRAINT metric_edge_from_metric_to_metric_from_version_purpose_key
    UNIQUE (from_metric, to_metric, from_version, purpose);

-- ---------- 运行：固定用途、口径版本与归属修订 ----------
ALTER TABLE run ADD COLUMN IF NOT EXISTS purpose VARCHAR(32);
ALTER TABLE run ADD COLUMN IF NOT EXISTS profile_version INTEGER;
ALTER TABLE run ADD COLUMN IF NOT EXISTS semantics_id BIGINT;
ALTER TABLE run ADD COLUMN IF NOT EXISTS semantics_code VARCHAR(64);
ALTER TABLE run ADD COLUMN IF NOT EXISTS semantics_version INTEGER;
ALTER TABLE run ADD COLUMN IF NOT EXISTS attribution_revision INTEGER;
ALTER TABLE run ADD COLUMN IF NOT EXISTS purpose_label VARCHAR(64);
ALTER TABLE run ADD COLUMN IF NOT EXISTS applicable_users VARCHAR(128);
ALTER TABLE run ADD COLUMN IF NOT EXISTS reference_identity VARCHAR(256);
ALTER TABLE run ADD COLUMN IF NOT EXISTS pending_json JSONB;         -- 待归属金额/清单摘要
ALTER TABLE run ADD COLUMN IF NOT EXISTS input_digest_json JSONB;    -- 固定输入摘要，用于复演核对

-- ---------- 报表项：用途与口径身份 ----------
ALTER TABLE report_item ADD COLUMN IF NOT EXISTS purpose VARCHAR(32);
ALTER TABLE report_item ADD COLUMN IF NOT EXISTS semantics_id BIGINT;
ALTER TABLE report_item ADD COLUMN IF NOT EXISTS purpose_label VARCHAR(64);
ALTER TABLE report_item ADD COLUMN IF NOT EXISTS applicable_users VARCHAR(128);
ALTER TABLE report_item ADD COLUMN IF NOT EXISTS reference_identity VARCHAR(256);
ALTER TABLE report_item DROP CONSTRAINT IF EXISTS report_item_report_id_metric_key_key;
ALTER TABLE report_item ADD CONSTRAINT report_item_report_metric_purpose_key
    UNIQUE (report_id, metric_key, purpose);

-- ---------- 审批：固定用途与口径版本 ----------
ALTER TABLE approval ADD COLUMN IF NOT EXISTS purpose VARCHAR(32);
ALTER TABLE approval ADD COLUMN IF NOT EXISTS semantics_id BIGINT;
ALTER TABLE approval ADD COLUMN IF NOT EXISTS profile_version INTEGER;

-- ---------- 发布清单：用途绑定 ----------
ALTER TABLE metric_release ADD COLUMN IF NOT EXISTS purpose VARCHAR(32);
ALTER TABLE metric_release ADD COLUMN IF NOT EXISTS semantics_id BIGINT;
ALTER TABLE metric_release ADD COLUMN IF NOT EXISTS semantics_code VARCHAR(64);
ALTER TABLE metric_release ADD COLUMN IF NOT EXISTS semantics_version INTEGER;
ALTER TABLE metric_release ADD COLUMN IF NOT EXISTS profile_version INTEGER;
ALTER TABLE metric_release ADD COLUMN IF NOT EXISTS reference_identity VARCHAR(256);
ALTER TABLE metric_release ADD CONSTRAINT metric_release_metric_version_purpose_key
    UNIQUE (metric_key, version, purpose);

-- ---------- 源字段注册（归属来源的字段血缘） ----------
INSERT INTO source_field (source_type, field_name, field_type, nullable) VALUES
    ('refund_attributions', 'refund_id', 'text', false),
    ('refund_attributions', 'order_id', 'text', false),
    ('refund_attributions', 'order_line_id', 'text', true),
    ('refund_attributions', 'store_id', 'text', false),
    ('refund_attributions', 'amount', 'amount', false),
    ('refund_attributions', 'quantity', 'quantity', true),
    ('refund_attributions', 'direction', 'quantity', false),
    ('refund_attributions', 'business_date', 'timestamp', true),
    ('refund_attributions', 'posting_date', 'timestamp', false),
    ('refund_attributions', 'original_business_date', 'timestamp', true),
    ('refund_attributions', 'purpose', 'text', false),
    ('refund_attributions', 'link_status', 'text', false),
    ('refund_attributions', 'sku', 'text', true)
ON CONFLICT (source_type, field_name) DO NOTHING;

-- ---------- 归属修订：内容摘要 + 摘要快照，保证同一修订可复算 ----------
CREATE TABLE IF NOT EXISTS attribution_revision (
    id              BIGSERIAL PRIMARY KEY,
    semantics_id    BIGINT NOT NULL REFERENCES refund_semantics(id),
    revision        INTEGER NOT NULL,
    input_digest    VARCHAR(64) NOT NULL,
    summary_json    JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      VARCHAR(64),
    UNIQUE (semantics_id, revision)
);

-- 血缘/说明可能包含较长的归属理由与算式
ALTER TABLE run_cell_lineage ALTER COLUMN reason TYPE TEXT;
ALTER TABLE run_cell_lineage ALTER COLUMN record_type TYPE VARCHAR(48);
ALTER TABLE run_cell_lineage ALTER COLUMN record_id TYPE VARCHAR(128);
ALTER TABLE run_cell ALTER COLUMN message TYPE TEXT;

-- 运行输入快照：保证旧报表按原输入复演（订单/订单行/归属行在运行时定格）
ALTER TABLE run ADD COLUMN IF NOT EXISTS input_snapshot_json JSONB;
