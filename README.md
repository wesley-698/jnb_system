# 某银行纪念币预约系统

基于 **Spring Cloud Alibaba + Nacos + Sentinel + Kafka + Redis + PostgreSQL** 的纪念币预约系统，
支撑瞬时百万级并发，严格防超卖/防超限购。

业务模型对齐真实普通纪念币预约：**预约期内先到先得、约满为止**（无抽签环节），
用户在**提交那一刻即占用所选网点额度并拿到预约号**。

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
├── coin-reservation-service/ 预约核心（时间窗/资格校验/Lua防超卖/Kafka/对账）★
├── order-service/            订单（Kafka消费落库/查询/补偿）
├── user-service/             用户（实名预填）
├── product-service/          产品（产品/网点/额度/额度预热）
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
2. **额度预热**：`POST /api/product/{productId}/init-stock`（把网点额度拆分片写入 Redis + 登记网点清单）
3. **查网点余量**：`GET /api/reservation/stock/{productId}`（前端据此让用户避开已约满网点）
4. **提交预约**（百万并发峰值）：`POST /api/reservation/submit`
   —— 活动时间窗校验 + 幂等 + 资格校验 + **Lua 原子扣所选网点额度**，
   **当场返回预约号 `orderNo`**（即预约成功）/ 或明确失败原因（1001 该网点已约满、1002 已约过…）
5. **落库**：reservation-service 发 Kafka → order-service 消费，单库事务落库（唯一约束 + 乐观锁兜底）
6. **查询**：`GET /api/order/list?idCard=xxx`（含 orderNo、sequenceNo 预约顺序号）
7. **兑换登记**：`POST /api/exchange/register`

> 提交与扣减在同一时刻完成：Redis 是额度的权威持有者，落库是异步最终一致的镜像。
> 失败原因必须明确到「网点级」，用户才能按真实业务那样换网点再抢。

## 防超卖三层防线

1. Redis Lua 原子扣减（网点额度 + 限购），扣减与限购在同一脚本内完成
2. 幂等 Token 防重（失败即释放，成功才保留）
3. DB 唯一约束 `(product_id, id_card)` + 乐观锁 `remain>0`
4. 在途单对账 + **幂等回补**（同一 orderNo 只回补一次，避免重复 +1 造成超发）

## Redis 故障降级与恢复（已实现）

Redis 不可用时自动降级为「DB 直扣」，保可用且不超卖：

| 步骤 | 实现 |
|------|------|
| ① 检测 | Redis 超时 500ms + 异常识别 → `DegradeManager.markRedisDown()` |
| ② 切换 | 手动开关（Nacos 动态）/ 自动切换；Sentinel 对直扣做强限流（默认 200 QPS） |
| ③ 运行 | `DbDirectReservationService`：单库事务，乐观锁 `remain>0` + 唯一约束 |
| ④ 恢复 | `RedisHealthProbe` 每 5s 探活 → `StockRebuildService` 按 PG 重建 Redis 库存 → 切回主模式 |

**对账**：`ReservationReconcileTask` 定时扫描**在途单**（`resv:pending:{productId}`，在 Redis 扣额成功后、
投递 Kafka 之前写入），超时未落库则「先查 PG 确认没落库 → 幂等回补额度」。
因此「进程在扣额后崩溃」「Kafka 投递成功但消费失败」「落库失败且补偿失败」三类场景都能兜住。

**运维接口**：
```bash
GET  /api/reservation/degrade/status      # 查看降级状态
POST /api/reservation/rebuild/{productId} # 手动重建 Redis 额度（同时刷新网点清单）
```

**手动降级开关**（Nacos：`coin-reservation-service.yml`）：
```yaml
coin:
  degrade:
    db-direct-enabled: true    # 强制走 DB 直扣（动态刷新生效）
    db-direct-qps-limit: 200   # 降级直扣限流阈值
```

## 已知取舍

### 1. 落库是异步的

用户看到「预约成功」的依据是 **Redis 扣额成功**（额度的权威持有者），落库走 Kafka 异步。
若 Kafka 投递最终失败，会回补额度（用户实际没约上，但当时已看到成功）——
这个窗口由 Kafka `delivery.timeout.ms`（3s）界定，且仅在后端消息通道故障时出现，
日常由在途单对账保证「额度要么被一张落库的单占用，要么被回补」。

若要把这个窗口彻底消除，需改为提交时同步等待落库确认（用延迟换确定性）；
当前实现选择保吞吐。这是本工程一处「当场返回的结果」与「最终落库状态」可能短暂不一致的地方。

### 2. 降级只覆盖扣额之前

`submit` 的降级（转 DB 直扣）只在**幂等占位 + 资格校验**阶段生效（阶段 A）。
一旦进入扣额度（阶段 B）就**不再降级**：Redis 的 Lua 可能已在服务端执行成功、只是响应超时丢失，
此时若转 DB 直扣，同一用户会在 Redis 与 DB 各占一份额度，即重复预约。
因此扣额阶段遇 Redis 异常直接返回「系统繁忙」，保留幂等键防重复提交，并引导用户查询预约结果。

残余风险：极小概率出现「额度已扣但既无落库记录、也无在途单」的漏单（命令已生效但响应丢失，
且在途单在扣额之后才写入）。彻底兜住需要一层「库存会计对账」（比对 `resv:limit` 已约人数
与 `t_reservation` 落库数），尚未实现。

### 3. 运维接口暴露在网关下

`/api/reservation/**` 整段被网关转发，因此 `/degrade/status` 与 `/rebuild/{productId}` 当前对外可达。
应改为内网或 admin 鉴权。

## 说明

- 本工程为**可编译可运行的骨架 + 核心业务代码**；三要素校验、真实 JWT、Sentinel 规则持久化以 TODO 标注。
- 生产拓扑：Redis Cluster、Kafka 多 broker、PostgreSQL 1主2从（Patroni/Repmgr）见设计文档。
- 真实业务还有**人民银行核查期**（跨行重复预约、往期预约未兑换的客户核查不通过）、
  **兑换期**与**余量兑换**，以及**违约记录**（预约未兑换影响下次预约）；
  这些属于预约成功之后的下游环节，尚未建模，见设计文档「待补齐」小节。
