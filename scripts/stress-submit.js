/**
 * k6 压测脚本：纪念币预约「提交」接口（峰值主链路）
 *
 * 目标接口：POST /api/reservation/submit
 * 链路特点：时间窗校验(内存) + 幂等 + 资格校验 + **Lua 原子扣网点额度**，
 *           不写 DB；落库由 Kafka 异步完成。提交成功即代表额度已占用。
 *
 * 业务失败码是**有效观测指标**，不要一律当故障：
 *   1001 该网点已约满  → 额度被正确扣完（先到先得生效）
 *   1002 超过限购数量  → 同一身份证重复预约被正确拒绝
 *   1003 请勿重复提交  → 幂等生效
 * 因此当 BRANCHES 较小、额度有限时，峰值后期出现大量 1001 属于**符合预期**；
 * 重点关注的是「同一次请求出现 5xx / 超时」以及 p95/p99 延迟。
 *
 * 运行示例（PowerShell）：
 *   $env:K6_WEB_DASHBOARD="true"; $env:K6_WEB_DASHBOARD_OPEN="true"
 *   k6 run -e BASE=http://localhost:8080 -e RATE=10000 scripts/stress-submit.js
 *
 * 环境变量：
 *   BASE      被测地址，默认 http://localhost:8080（网关）
 *   RATE      峰值目标 QPS，默认 5000
 *   USERS     预热的用户数（userId 取值范围），默认 200000
 *   BRANCHES  网点数（=1 时模拟「单网点极端热点」），默认 50
 *   PRODUCT   产品 ID，默认 1
 *
 * 前置条件（必做，否则结果失真）：
 *   1. Redis 已预热资格 key：qual:ok:{userId}（见 README 压测章节 / preheat 命令）
 *   2. 已完成额度预热：POST /api/product/{productId}/init-stock
 *   3. 已用 scripts/e2e-test.sh 跑通全链路
 */

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';

/** 预期内的业务拒绝码（额度约满 / 重复预约 / 重复提交），不算故障 */
const EXPECTED_REJECT = new Set([1001, 1002, 1003]);
/** 真正的业务失败码（非预期） */
const bizFail = new Counter('biz_fail');
/** 预期内的业务拒绝计数，按码分布 */
const bizReject = new Counter('biz_reject');

const BASE     = __ENV.BASE     || 'http://localhost:8080';
const RATE     = +(__ENV.RATE     || 5000);
const USERS    = +(__ENV.USERS    || 200000);
const BRANCHES = +(__ENV.BRANCHES || 50);
const PRODUCT  = +(__ENV.PRODUCT  || 1);
const RAMP     = __ENV.RAMP     || '30s';  // 爬坡时长
const HOLD     = __ENV.HOLD     || '2m';   // 峰值持续时长

export const options = {
  scenarios: {
    submit: {
      // 按「目标 QPS」施压（开模型），最贴近真实抢购流量
      executor: 'ramping-arrival-rate',
      startRate: Math.max(1, Math.round(RATE * 0.05)),
      timeUnit: '1s',
      preAllocatedVUs: Math.min(20000, Math.max(500, RATE)),
      maxVUs: Math.min(60000, Math.max(2000, RATE * 2)),
      stages: [
        { target: Math.round(RATE * 0.2), duration: RAMP  }, // 预热爬坡
        { target: RATE,                   duration: HOLD  }, // 峰值持续
        { target: 0,                      duration: '20s' }, // 优雅停止
      ],
    },
  },
  thresholds: {
    http_req_failed:   ['rate<0.01'],          // HTTP 层失败率 < 1%
    http_req_duration: ['p(95)<300', 'p(99)<800'],
  },
};

export default function () {
  const i      = exec.scenario.iterationInTest; // 全局递增迭代号，用于构造唯一数据
  const userId = 100000 + (i % USERS);
  const branch = 1 + (i % BRANCHES);

  const payload = JSON.stringify({
    productId: PRODUCT,
    branchId:  branch,
    userId:    userId,
    idCard:    '110101' + String(userId).padStart(12, '0'),
    // 幂等 token 必须全局唯一！重复会直接返回 DUPLICATE_SUBMIT(1003)
    token:     `vu${__VU}-it${i}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
  });

  const res = http.post(`${BASE}/api/reservation/submit`, payload, {
    headers: {
      'Content-Type':  'application/json',
      // 网关 AuthGlobalFilter 只校验 header 存在性，不验签
      'Authorization': 'Bearer test-token',
    },
  });

  // 解析业务码：成功码为 0（ResultCode.SUCCESS）
  let code = -1;
  if (res.status === 200) {
    try { code = res.json('code'); } catch (e) { code = -999; }
  }

  // 区分「预期内的业务拒绝」与「真正的失败」
  if (code === 0) {
    // 预约成功
  } else if (EXPECTED_REJECT.has(code)) {
    bizReject.add(1, { code: String(code) });
  } else {
    bizFail.add(1, { code: String(code) });
  }

  check(res, {
    'HTTP 200':                () => res.status === 200,
    '无预期外业务失败':        () => code === 0 || EXPECTED_REJECT.has(code),
  });
}
