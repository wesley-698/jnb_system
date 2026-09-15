# 邮储银行纪念币预约系统

基于 **Spring Cloud Alibaba + Nacos + Sentinel + Kafka + Redis + PostgreSQL** 的纪念币预约系统，
支撑瞬时百万级并发，严格防超卖/防超限购。

完整架构设计见 [docs/01-技术架构与业务流程设计.md](docs/01-技术架构与业务流程设计.md)。

## 技术栈

| 组件 | 版本 |
|------|------|
| JDK | 21 |
| Spring Boot | 3.2.4 |
| Spring Cloud | 2023.0.1 |
| Spring Cloud Alibaba | 2023.0.1.0（Nacos 2.3.2 / Sentinel 1.8.6） |
| PostgreSQL | 16（单库 1主2从） |
| Redis | 7.2 |
| Kafka | 3.7（KRaft） |

## 模块结构

```
coin-reservation-system/
├── common/                   公共模块（统一返回/异常/雪花ID/JSON/常量）
├── gateway-service/          网关（路由/JWT/Sentinel）
├── coin-reservation-service/ 预约核心（资格校验/抽签排队/Lua防超卖/Kafka/补偿）★
├── order-service/            订单（Kafka消费落库/查询/补偿）
├── user-service/             用户（实名预填）
├── product-service/          产品（产品/网点/额度/库存预热）
├── exchange-service/         兑换（线下柜面兑换登记）
├── sql/init.sql              数据库初始化
└── docker-compose.yml        本地基础设施
```

## 快速开始

### 1. 启动基础设施

```bash
docker compose up -d        # Nacos:8848 Redis:6379 Kafka:9092 PostgreSQL:5432
# 若本机无 compose 插件：brew install docker-compose 后 docker-compose up -d
```

### 2. 编译（首次需 install，将 common 装进本地仓库供各服务引用）

> ⚠️ **JDK 必须用 21**：Spring Boot 3.2.4 自带的 Lombok 不支持 Java 26，用 26 会报
> `java.lang.ExceptionInInitializerError: com.sun.tools.javac.code.TypeTag :: UNKNOWN`。
> 已在本机 `~/.zshrc` 写入 JDK 21 配置（JAVA_HOME=/usr/local/opt/openjdk@21），**新开终端**即可；
> 若用 IDE，请把 Project SDK / Maven Runner JVM 设为 JDK 21。

```bash
export JAVA_HOME="/usr/local/opt/openjdk@21"
export PATH="$JAVA_HOME/bin:$PATH"
mvn clean install -DskipTests
```

### 3. 启动服务（按顺序）

```bash
# 各服务目录下 mvn spring-boot:run，端口如下
# gateway:8080  reservation:8081  order:8082  user:8083  product:8084  exchange:8085
```

## 核心业务链路

1. **实名预填**：`POST /api/user/qualify`
2. **库存预热**：`POST /api/product/{productId}/init-stock`（把网点额度拆分片写入 Redis）
3. **提交预约**（百万并发峰值）：`POST /api/reservation/submit`（资格校验 + 入队，不扣库存不写 DB）
4. **抽签分配**：`POST /api/reservation/draw/{productId}`（Lua 原子扣库存 + 发 Kafka）
5. **落库**：order-service 消费 Kafka，单库事务落库（唯一约束 + 乐观锁兜底）
6. **查询**：`GET /api/order/list?idCard=xxx`
7. **兑换登记**：`POST /api/exchange/register`

## 防超卖三层防线

1. Redis Lua 原子扣减（库存 + 限购）
2. 幂等 Token 防重
3. DB 唯一约束 `(product_id, id_card)` + 乐观锁 `remain>0`

## Redis 故障降级与恢复（已实现）

Redis 不可用时自动降级为「DB 直扣」，保可用且不超卖：

| 步骤 | 实现 |
|------|------|
| ① 检测 | Redis 超时 500ms + 异常识别 → `DegradeManager.markRedisDown()` |
| ② 切换 | 手动开关（Nacos 动态）/ 自动切换；Sentinel 对直扣做强限流（默认 200 QPS） |
| ③ 运行 | `DbDirectReservationService`：单库事务，乐观锁 `remain>0` + 唯一约束 |
| ④ 恢复 | `RedisHealthProbe` 每 5s 探活 → `StockRebuildService` 按 PG 重建 Redis 库存 → 切回主模式 |

**对账**：`ReservationReconcileTask` 定时扫描待确认单，超时未落库则「先查 PG 再补偿回补」。

**运维接口**：
```bash
GET  /api/reservation/degrade/status      # 查看降级状态
POST /api/reservation/rebuild/{productId} # 手动重建 Redis 库存
```

**手动降级开关**（Nacos：`coin-reservation-service.yml`）：
```yaml
coin:
  degrade:
    db-direct-enabled: true    # 强制走 DB 直扣（动态刷新生效）
    db-direct-qps-limit: 200   # 降级直扣限流阈值
```

## 说明

- 本工程为**可编译可运行的骨架 + 核心业务代码**；三要素校验、真实 JWT、Sentinel 规则持久化以 TODO 标注。
- 生产拓扑：Redis Cluster、Kafka 多 broker、PostgreSQL 1主2从（Patroni/Repmgr）见设计文档。
