package com.psbc.coin.common.constant;

/**
 * 全局常量：Redis Key 模板、Kafka Topic、预约状态。
 */
public final class CommonConstants {

    private CommonConstants() {
    }

    /** Redis Key 前缀 */
    public static final String KEY_STOCK = "stock";            // 网点库存分片：stock:{productId}:{branchId}:{shardId}
    public static final String KEY_LIMIT = "resv:limit";       // 限购 Hash：resv:limit:{productId}
    public static final String KEY_IDEMPOTENT = "resv:idempotent"; // 幂等：resv:idempotent:{token}
    public static final String KEY_QUEUE = "queue";            // 排队池 ZSet：queue:{productId}:{shardId}
    public static final String KEY_POOL = "pool";              // 抽签池 Set：pool:{productId}:{shardId}
    public static final String KEY_SEQ = "resv:seq";           // 序号号段基准：resv:seq:{productId}
    public static final String KEY_PENDING = "resv:pending";   // 待确认单 ZSet：resv:pending:{productId}
    public static final String KEY_QUALIFICATION = "qual:ok";  // 实名资格：qual:ok:{userId}
    public static final String KEY_PRODUCT_INFO = "product:info"; // 产品缓存：product:info:{productId}
    public static final String KEY_RESERVATION_DETAIL = "resv:detail"; // 预约详情缓存

    /** Kafka Topic */
    public static final String TOPIC_RESV_RESULT = "RESV_RESULT";     // 中签结果落库
    public static final String TOPIC_RESV_COMPENSATE = "RESV_COMPENSATE"; // 补偿回补

    /** 预约状态（t_reservation.status） */
    public static final int STATUS_RESERVED = 1;     // 已预约
    public static final int STATUS_CANCELLED = 2;    // 已取消
    public static final int STATUS_EXCHANGED = 3;    // 已兑换
    public static final int STATUS_MODIFIED = 4;     // 已修改

    /** 抽签模式 */
    public static final int DRAW_MODE_QUEUE = 1;   // 排队（先到先得）
    public static final int DRAW_MODE_LOTTERY = 2; // 抽签（随机）
}
