# 经营指标语义层与自助分析平台（用途绑定的退款时间口径）

面向连锁零售集团的经营指标语义层与自助分析平台。解决总部财务与门店运营对「净销售额」口径不一致的问题：
同一笔跨月部分退款，一方要按**退款实际入账日**衡量当期收入，另一方要把退款**追溯到原订单日**评估销售批次。
两种数字都正确，但不能互相替代，也不能被当成同一条可比序列。

本迭代在现有可执行指标语义层上叠加**用途绑定的退款时间口径**：
每个运行结果都明确回答「财务发生额」还是「订单批次表现」，并把用途、口径版本与归属修订固化到
定义审批、语义编译、下游依赖、报表发布的全链路。

## 核心能力

- **两种用途各自独立、可复算**：`financial`（财务发生，按实际入账日）与 `order_batch`（运营订单，按原交易日）；
  分别命名、审批、版本化，消费端固定引用 metric key，不依赖「最近使用分支」。
- **退款事实完整保留**：原订单/订单行、实际入账日、原交易日、金额方向（+扣减 / -撤销）、数量、来源修订。
- **无法关联即待归属**：无法可靠关联原订单行的退款记为 `pending`，归属日期为空，绝不按同名商品或当前价格猜填。
- **用途参数模板**：直接引用 `refund_attributions` 的定义是用途模板；运行、缓存键、依赖指标、发布清单都必须显式固定用途；
  下游引用模板必须写明用途，否则拒绝编译。
- **语义编译与一致性**：单用途运行中混用另一用途、引用无用途模板、引用指标固定口径版本不一致，都会被拒绝生成结果。
- **同一仪表盘不得混用**：报表项加入不同用途的运行会被拒绝；每项固定用途、适用用户与引用身份。
- **归属修订可复演**：退款晚到 / 退款更正 / 原订单行更正都会追加归属修订；只标出受影响的用途与期间并生成新运行候选，
  旧报表继续按原输入（输入快照）复演。
- **并发修改用途**：用途档案带 `profileVersion`，修改必须携带期望版本，陈旧写入返回 409。
- **守恒校验**：订单金额 = 订单行合计；退款事实 = 财务口径 = 运营归属 + 待归属；同商品不同价仅作说明，不用于回填。
- **既有能力保留**：DSL 表达式校验（币种/粒度/环路）、覆盖范围标记（未到数据不当零）、同店队列、迟到退款影响、血缘下钻。

## 技术栈

- **后端**：Spring Boot 3.5 + Java 21 + Spring Data JPA + Flyway
- **前端**：Vue 3 + Vite + Element Plus
- **数据库**：PostgreSQL（本地开发使用容器内 18.1，Compose 使用 16）
- **部署**：Docker Compose 一键启动

## 快速开始

### 方式一：Docker Compose（一键启动）

```bash
docker-compose up --build
```

启动后访问：
- 前端：<http://localhost>
- 后端 API：<http://localhost:8080/api>

### 方式二：本地开发

**前置要求**：JDK 21+、Node 22+、PostgreSQL（本地默认 `127.0.0.1:5432`，库/用户/密码均为 `ccdev`）

```bash
# 1. 启动后端（Flyway 自动建表 + 播种样例数据）
cd backend
mvn spring-boot:run

# 2. 启动前端（另开终端）
cd frontend
npm install
npm run dev
```

访问 <http://localhost:5173>（前端开发服务器，代理 `/api` 到 8080）。

> 也可只启动后端：把 `frontend/dist` 复制到 `backend/src/main/resources/static` 后打包，
> 后端会托管前端构建产物，直接访问 <http://localhost:8080>。

### 测试账号

| 账号 | 密码 | 角色 |
|------|------|------|
| admin | admin123 | 指标管理员 |
| analyst | analyst123 | 分析员 |
| manager | manager123 | 经营负责人 |

### 启动后先看哪里

- 前端左侧「退款口径」：用途档案、期间归属对照、待归属清单、守恒校验
- 「运行结果」：每个运行的口径身份（用途 / 适用用户 / 引用身份 / 归属修订）、待归属提示、按原输入复演
- 「报表中心」：报表项用途与引用身份；不同用途加入同一报表会被拒绝

## 用途绑定的退款时间口径

### 口径版本与用途档案

- 口径编码 `REFUND_TIME`，版本化发布（样例为 `REFUND_TIME@v1`）。
- 用途档案：

| 用途 | 展示口径 | 时间基准 | 适用用户 |
|------|----------|----------|----------|
| `financial` | 财务发生 | 实际入账日 (`posting_date`) | 总部财务 |
| `order_batch` | 运营订单批次 | 原交易日 (`original_business_date`) | 门店运营 |
| `reconciliation` | 对账复算（仅校验） | 两者独立引用 | 数据分析 / 财务运营对账 |

- 引用身份格式：`purpose/REFUND_TIME@vN/profile@vM/attrRev@K`，出现在运行、报表项、发布清单中。

### 指标定义方式

退款金额的直接来源 `refund_attributions` 必须绑定用途。样例指标：

| metric key | 用途 | 定义 |
|------------|------|------|
| `net_sales_financial` | financial | `gross_sales - refund_amount_financial` |
| `net_sales_order_batch` | order_batch | `gross_sales - refund_amount_order_batch` |
| `net_sales_reconciliation` | reconciliation | `net_sales_financial - net_sales_order_batch`（仅对账，不作业务序列） |
| `aov_financial` / `same_store_growth_financial` | financial | 派生指标，引用时显式固定 financial |

引用带用途：

```json
{"op": "ref", "metric": "net_sales_financial", "purpose": "financial"}
```

**编译规则**：
- 原始 `refunds` 来源禁止直接聚合（没有时间口径）。
- `refund_attributions` 指标未固定用途时拒绝编译。
- 单用途运行只能继承运行用途；`reconciliation` 用途允许在同一表达式里分别引用两种用途。
- 一个业务指标同时引用两种用途会被拒绝，除非声明为对账派生指标。

### 运行

必须显式给 `purpose`：

```bash
curl -X POST http://localhost:8080/api/runs -H "X-Token: $TOKEN" -H "Content-Type: application/json" \
  -d '{"metricKey":"net_sales_financial","metricVersionId":<VID>,"from":"2026-01-01","to":"2026-01-31","grain":"month","purpose":"financial"}'
```

- 未带 `purpose`（且指标依赖退款口径）→ `400 未选择用途`，不默认最近使用分支。
- 固定用途指标以另一用途运行 → `400 用途不匹配`。
- 引用指标固定口径版本与本次不一致 → `409 语义编译失败`。

## 样例数据与验收场景

预置 2026 年 1-3 月订单 / 订单行 / 退款 / 门店状态 / 节假日 / 批次：

| 场景 | 数据 |
|------|------|
| 跨月部分退款 | `R101`：订单 `O008`（原交易日 2026-01-28，订单行 `L081`），实际入账 2026-02-03，金额 200，方向 +1 |
| 无法关联订单行 | `R201`：订单 `O009`，退款行号 `L-NOT-EXIST`，金额 80 → 待归属 |
| 同商品不同价 | `SKU-A` 单价 200/300/450，`SKU-B` 100/200/300；归属只看原订单行，不看当前价格 |
| 退款撤销 | `R301`：订单 `O005` 订单行 `L052`，方向 -1，金额 40 |
| 多次部分退款 | `R001`/`R002`（`O005` 订单行 `L051`/`L052`） |
| 派生指标引用两用途 | `net_sales_reconciliation` 同时引用两种净销售额 |

### 一键验证

```bash
# 确保后端已启动（localhost:8080，数据库可用）
bash scripts/verify-refund-semantics.sh   # 用途绑定退款口径专项（28 项）
bash scripts/verify.sh                    # 基础能力（11 项）
```

`verify-refund-semantics.sh` 覆盖：
1. 口径发布与用途档案
2. 订单=订单行、退款=财务口径=运营+待归属的守恒
3. 跨月部分退款在两用途分别落入 1 月 / 2 月
4. 未选择用途与跨用途运行被拒绝
5. 无法关联退款列为待归属，归属日期为空
6. 对账派生指标独立复算两用途
7. 同一仪表盘混用用途被拒绝，报表展示引用身份
8. 迟到退款只标出受影响用途/期间，旧报表保持原运行
9. 旧运行按原输入复演结果一致
10. 原订单行更正只影响运营订单期间
11. 并发修改用途：陈旧 profile 版本返回 409
12. 引用指标口径版本不一致时拒绝运行
13. 审批固定用途，批准发布清单引用同一口径版本

### 关键场景手动验证

```bash
BASE=http://localhost:8080
TOKEN=$(curl -s -X POST $BASE/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])")
AUTH="X-Token: $TOKEN"

# 1) 期间归属对照（1 月）：R101 在运营订单口径计入 1 月，财务发生口径为 0
curl -s "$BASE/api/refund-semantics/period-comparison?from=2026-01-01&to=2026-01-31" -H "$AUTH"

# 2) 待归属清单（R201 = 80 元，归属日期为空）
curl -s "$BASE/api/refund-semantics/pending" -H "$AUTH"

# 3) 守恒校验（allBalanced=true，含同商品不同价说明）
curl -s "$BASE/api/refund-semantics/conservation" -H "$AUTH"

# 4) 未选择用途 → 400
curl -s -X POST $BASE/api/runs -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"metricKey":"net_sales_financial","metricVersionId":4,"from":"2026-01-01","to":"2026-01-31","grain":"month"}'

# 5) 迟到退款（带原订单行 → 运营订单追溯 1 月；财务发生看 2 月入账）
curl -s -X POST $BASE/api/replay/late-refund -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"refundId":"R-DEMO","orderId":"O001","orderLineId":"L011","storeId":"S001","businessDate":"2026-02-15","amount":"30.00"}'

# 6) 原订单行更正（只影响运营订单期间）
curl -s -X POST $BASE/api/sources/order-lines/L081/correct -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"businessDate":"2026-02-02","reason":"营业日改判"}'

# 7) 并发修改用途（陈旧版本 → 409）
curl -s -X PUT $BASE/api/refund-semantics/purposes/financial -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"expectedProfileVersion":1,"applicableUsers":"总部财务"}'
```

## 指标 DSL 说明

指标定义是 JSON 表达式树（AST），节点类型：

| 节点 | 说明 | 示例 |
|------|------|------|
| `field` | 取源字段 | `{"op":"field","source":"orders","field":"amount"}` |
| `const` | 常量 | `{"op":"const","value":0.05}` |
| `agg` | 聚合 | `{"op":"agg","measure":"sum","expr":{...},"filter":[...],"by":["store_id"]}` |
| `arith` | 四则运算 | `{"op":"arith","operator":"sub","left":{...},"right":{...}}` |
| `convert` | 币种换算 | `{"op":"convert","expr":{...},"fromCurrency":"USD","toCurrency":"CNY","rate":7.1}` |
| `ref` | 引用其他指标（可带用途） | `{"op":"ref","metric":"net_sales_financial","purpose":"financial"}` |
| `cohort` | 同店增长 | `{"op":"cohort","base":{...},"compare":"prev_period","cohortKey":"store_id"}` |

**来源**：`orders`、`order_lines`、`refunds`（禁止直接聚合）、`refund_attributions`（必须绑定用途）、
`store_status`、`holidays`。

**校验规则**：引用环路（DFS 三色标记）、金额/数量/比例类型、不同币种未经 `convert` 不能相加、
门店日指标不能与门店月指标逐行相乘、金额与金额不能相乘、退款口径用途一致性。

## 项目结构

```
├── backend/
│   ├── src/main/java/com/semantic/platform/
│   │   ├── dsl/               # 指标 DSL（AST、用途感知校验器、源注册表）
│   │   ├── engine/            # 求值引擎（用途切换、归属来源、覆盖、同店队列）
│   │   ├── semantics/         # 退款口径、用途档案、归属明细账、归属修订、守恒
│   │   ├── metric/            # 指标服务（校验、发布清单、依赖边）
│   │   ├── run/               # 运行（用途/口径/修订固定、输入快照、复演）、影响计算
│   │   ├── report/            # 报表（用途一致性、更正）
│   │   ├── approval/          # 审批（固定用途与口径版本）
│   │   ├── source/            # 源数据与样例播种
│   │   └── api/               # REST 控制器
│   └── src/main/resources/db/migration/   # V1 基础模型，V2 退款时间口径
├── frontend/src/views/        # 指标目录/编辑器/运行/报表/审批/数据源/影响/退款口径
├── scripts/verify.sh          # 基础端到端验证
├── scripts/verify-refund-semantics.sh  # 退款时间口径专项验证
└── docker-compose.yml         # 一键部署
```

## 验证结论

本次交付实际执行过的验证（本地 Java 25 运行、容器内 PostgreSQL 18）：

- ✅ 后端编译与单元测试 21/21 通过（`mvn test`），含退款归属规则与用途校验用例
- ✅ 前端构建通过（`npm run build`，Vue 3 + Vite）
- ✅ 单 jar 托管前端产物，`/semantics` SPA 路由返回 200
- ✅ 退款时间口径专项端到端 **28/28** 通过（`scripts/verify-refund-semantics.sh`）
- ✅ 基础能力端到端 **11/11** 通过（`scripts/verify.sh`）
- ✅ 跨月部分退款 R101：1 月运营口径 -200、2 月财务口径 -200，两用途不可互换
- ✅ 无法关联退款 R201 保持待归属（80 元，`attributionDate=null`），守恒 `allBalanced=true`
- ✅ 同一仪表盘混用用途被拒绝；旧报表在迟到退款/更正后仍按原输入复演一致
- ✅ 血缘下钻显示归属行用途、入账日、原交易日、原订单行与关联状态，消费端可确认口径身份
- ✅ 负向校验：下游引用无用途模板、单指标同时引用两种用途、原始退款事实聚合均被拒绝（400）
