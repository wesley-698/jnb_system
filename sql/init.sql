-- 邮储银行纪念币预约系统 - 数据库初始化脚本（PostgreSQL 16）

-- 网点表
CREATE TABLE IF NOT EXISTS t_branch (
  id            BIGSERIAL PRIMARY KEY,
  branch_code   VARCHAR(32)  NOT NULL,
  branch_name   VARCHAR(128) NOT NULL,
  address       VARCHAR(256),
  status        SMALLINT NOT NULL DEFAULT 1,
  CONSTRAINT uk_branch_code UNIQUE (branch_code)
);

-- 实名资格表
CREATE TABLE IF NOT EXISTS t_qualification (
  id            BIGSERIAL PRIMARY KEY,
  user_id       BIGINT NOT NULL,
  id_card       VARCHAR(32) NOT NULL,
  name          VARCHAR(64) NOT NULL,
  phone         VARCHAR(20),
  status        SMALLINT NOT NULL DEFAULT 1,
  create_time   TIMESTAMP NOT NULL DEFAULT now(),
  CONSTRAINT uk_qualification_idcard UNIQUE (id_card)
);

-- 产品表
CREATE TABLE IF NOT EXISTS t_product (
  id              BIGSERIAL PRIMARY KEY,
  product_code    VARCHAR(64)  NOT NULL,
  name            VARCHAR(128) NOT NULL,
  total_stock     BIGINT       NOT NULL,
  limit_per_user  INT          NOT NULL DEFAULT 1,
  draw_mode       SMALLINT     NOT NULL DEFAULT 1,
  submit_start    TIMESTAMP    NOT NULL,
  submit_end      TIMESTAMP    NOT NULL,
  draw_time       TIMESTAMP    NOT NULL,
  status          SMALLINT     NOT NULL DEFAULT 0,
  CONSTRAINT uk_product_code UNIQUE (product_code)
);

-- 网点库存表（商品 × 网点）
CREATE TABLE IF NOT EXISTS t_branch_stock (
  id            BIGSERIAL PRIMARY KEY,
  product_id    BIGINT NOT NULL,
  branch_id     BIGINT NOT NULL,
  total         BIGINT NOT NULL,
  remain        BIGINT NOT NULL,
  version       BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_branch_stock UNIQUE (product_id, branch_id)
);

-- 预约记录表
CREATE TABLE IF NOT EXISTS t_reservation (
  id            BIGINT PRIMARY KEY,
  order_no      VARCHAR(64) NOT NULL,
  product_id    BIGINT NOT NULL,
  branch_id     BIGINT NOT NULL,
  user_id       BIGINT NOT NULL,
  id_card       VARCHAR(32) NOT NULL,
  sequence_no   BIGINT,
  status        SMALLINT NOT NULL DEFAULT 1,
  create_time   TIMESTAMP NOT NULL DEFAULT now(),
  update_time   TIMESTAMP NOT NULL DEFAULT now(),
  CONSTRAINT uk_reservation_orderno UNIQUE (order_no),
  CONSTRAINT uk_reservation_product_idcard UNIQUE (product_id, id_card)
);

-- 兑换登记表
CREATE TABLE IF NOT EXISTS t_exchange (
  id            BIGSERIAL PRIMARY KEY,
  order_no      VARCHAR(64) NOT NULL,
  product_id    BIGINT NOT NULL,
  branch_id     BIGINT NOT NULL,
  id_card       VARCHAR(32) NOT NULL,
  exchange_time TIMESTAMP NOT NULL DEFAULT now(),
  operator_id   BIGINT,
  status        SMALLINT NOT NULL DEFAULT 1,
  CONSTRAINT uk_exchange_orderno UNIQUE (order_no)
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_reservation_user ON t_reservation(user_id);
CREATE INDEX IF NOT EXISTS idx_reservation_idcard ON t_reservation(id_card);
CREATE INDEX IF NOT EXISTS idx_exchange_idcard ON t_exchange(id_card);

-- 示例网点数据
INSERT INTO t_branch (branch_code, branch_name, address) VALUES
  ('PSBC-BJ-001', '北京东城支行', '北京市东城区示例路1号'),
  ('PSBC-BJ-002', '北京西城支行', '北京市西城区示例路2号'),
  ('PSBC-SH-001', '上海浦东支行', '上海市浦东新区示例路3号')
ON CONFLICT (branch_code) DO NOTHING;

-- 示例产品（2025年贺岁纪念币）
INSERT INTO t_product (product_code, name, total_stock, limit_per_user, draw_mode,
                       submit_start, submit_end, draw_time, status) VALUES
  ('COIN-2025-01', '2025年贺岁纪念币', 900, 1, 1,
   now() - interval '1 day', now() + interval '7 day', now() + interval '7 day', 1)
ON CONFLICT (product_code) DO NOTHING;

-- 示例网点额度（商品 × 网点，每网点 300）
INSERT INTO t_branch_stock (product_id, branch_id, total, remain) VALUES
  (1, 1, 300, 300),
  (1, 2, 300, 300),
  (1, 3, 300, 300)
ON CONFLICT (product_id, branch_id) DO NOTHING;
