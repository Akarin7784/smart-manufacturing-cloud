-- 主数据服务初始表结构（Flyway V1，由原 schema.sql 转换）。只建表，不含任何业务数据；
-- 演示数据在 db/demo，只在 demo profile 由 Flyway 加载。各表以业务编码为主键，便于其他服务按编码引用且无需跨库外键。

-- 物料主数据；version 为乐观锁版本号，更新时条件匹配，防止并发覆盖。
CREATE TABLE IF NOT EXISTS material (
  code VARCHAR(64) PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  type VARCHAR(16) NOT NULL,
  uom VARCHAR(16) NOT NULL,
  trace_mode VARCHAR(16) NOT NULL,
  source VARCHAR(8) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  version BIGINT NOT NULL DEFAULT 0
);

-- 工作中心：工艺工序引用的能力单元。
CREATE TABLE IF NOT EXISTS work_center (
  code VARCHAR(64) PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  workshop VARCHAR(64) NOT NULL,
  outsourced BOOLEAN NOT NULL DEFAULT FALSE
);

-- 工位：属于唯一工作中心；同服务内的外键可以保留，跨服务（设备）只存编码。
CREATE TABLE IF NOT EXISTS station (
  code VARCHAR(64) PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  work_center_code VARCHAR(64) NOT NULL,
  CONSTRAINT fk_station_wc FOREIGN KEY (work_center_code) REFERENCES work_center (code)
);

-- BOM 版本头；同产品同版本唯一，主键前缀 product_code 已覆盖按产品查询生效版本的访问路径。生效区间 [effective_from, effective_to)，发布新版本时自动截断上一版本。
CREATE TABLE IF NOT EXISTS bom_header (
  product_code VARCHAR(64) NOT NULL,
  version VARCHAR(8) NOT NULL,
  status VARCHAR(16) NOT NULL,
  effective_from DATE NOT NULL,
  effective_to DATE NULL,
  CONSTRAINT pk_bom_header PRIMARY KEY (product_code, version),
  CONSTRAINT fk_bom_product FOREIGN KEY (product_code) REFERENCES material (code)
);

-- BOM 行；草稿修改时整体替换，发布后只读。
CREATE TABLE IF NOT EXISTS bom_line (
  product_code VARCHAR(64) NOT NULL,
  version VARCHAR(8) NOT NULL,
  line_no INT NOT NULL,
  component_code VARCHAR(64) NOT NULL,
  qty_per DECIMAL(12,4) NOT NULL,
  uom VARCHAR(16) NOT NULL,
  scrap_rate DECIMAL(5,4) NOT NULL DEFAULT 0,
  issue_mode VARCHAR(16) NOT NULL,
  consume_op_no VARCHAR(8) NULL,
  phantom BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT pk_bom_line PRIMARY KEY (product_code, version, line_no),
  CONSTRAINT fk_bom_line_header FOREIGN KEY (product_code, version) REFERENCES bom_header (product_code, version),
  CONSTRAINT fk_bom_line_component FOREIGN KEY (component_code) REFERENCES material (code)
);

-- 工艺路线版本头；规则与 BOM 一致。
CREATE TABLE IF NOT EXISTS routing_header (
  product_code VARCHAR(64) NOT NULL,
  version VARCHAR(8) NOT NULL,
  status VARCHAR(16) NOT NULL,
  effective_from DATE NOT NULL,
  effective_to DATE NULL,
  CONSTRAINT pk_routing_header PRIMARY KEY (product_code, version),
  CONSTRAINT fk_routing_product FOREIGN KEY (product_code) REFERENCES material (code)
);

-- 工序；检验点编码属于质量上下文，以逗号分隔存储，不做跨库校验。
CREATE TABLE IF NOT EXISTS routing_operation (
  product_code VARCHAR(64) NOT NULL,
  version VARCHAR(8) NOT NULL,
  op_no VARCHAR(8) NOT NULL,
  seq INT NOT NULL,
  name VARCHAR(128) NOT NULL,
  work_center_code VARCHAR(64) NOT NULL,
  setup_minutes INT NOT NULL,
  run_seconds_per_unit INT NOT NULL,
  report_point BOOLEAN NOT NULL,
  first_article_required BOOLEAN NOT NULL,
  inspection_point_codes VARCHAR(512) NOT NULL DEFAULT '',
  CONSTRAINT pk_routing_operation PRIMARY KEY (product_code, version, op_no),
  CONSTRAINT fk_routing_op_header FOREIGN KEY (product_code, version) REFERENCES routing_header (product_code, version),
  CONSTRAINT fk_routing_op_wc FOREIGN KEY (work_center_code) REFERENCES work_center (code)
);
