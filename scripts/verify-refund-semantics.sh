#!/bin/bash
# 经营指标语义层 — 用途绑定的退款时间口径 端到端验证
# 覆盖：未选择用途拒绝、跨月部分退款两用途、待归属、同商品不同价、派生指标引用两种净销售额、
#       同一仪表盘不得混用用途、并发修改用途、迟到退款/迟到更正按用途标出期间、旧报表按原输入复演、守恒。
set -u
BASE="${BASE:-http://localhost:8080}"
PASS=0
FAIL=0

ok()  { PASS=$((PASS+1)); echo "  ✓ $1"; }
bad() { FAIL=$((FAIL+1)); echo "  ✗ $1"; }
note(){ echo "    $1"; }

py() { python3 -c "$1"; }

echo "=== 1. 登录 ==="
TOKEN=$(curl -s -X POST "$BASE/api/auth/login" -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | py "import sys,json;print(json.load(sys.stdin)['token'])")
if [ -n "$TOKEN" ]; then ok "登录成功"; else bad "登录失败"; exit 1; fi
AUTH="X-Token: $TOKEN"

echo "=== 2. 退款口径与用途档案 ==="
SEM=$(curl -s "$BASE/api/refund-semantics" -H "$AUTH")
REF=$(echo "$SEM" | py "import sys,json;d=json.load(sys.stdin);print(d['published']['ref'] if d.get('published') else '')")
REV=$(echo "$SEM" | py "import sys,json;d=json.load(sys.stdin);print(d['published']['revision'] if d.get('published') else '')")
[ -n "$REF" ] && ok "口径已发布：$REF（归属修订 attrRev@$REV）" || bad "口径未发布"
PURPOSES=$(curl -s "$BASE/api/refund-semantics/purposes" -H "$AUTH" | py "import sys,json;print(','.join(sorted(p['purpose'] for p in json.load(sys.stdin))))")
[ "$PURPOSES" = "financial,order_batch,reconciliation" ] && ok "用途档案：$PURPOSES" || bad "用途档案异常: $PURPOSES"

echo "=== 3. 守恒：订单金额=订单行、退款=财务口径=运营+待归属 ==="
CONS=$(curl -s "$BASE/api/refund-semantics/conservation" -H "$AUTH")
echo "$CONS" | py "import sys,json;d=json.load(sys.stdin);print(d['refundFactsTotal'],d['financialTotal'],d['orderBatchAttributed'],d['pendingTotal'])" | \
  { read RF FT OB PD; note "退款事实=$RF 财务口径=$FT 运营归属=$OB 待归属=$PD"; }
BAL=$(echo "$CONS" | py "import sys,json;print(json.load(sys.stdin)['allBalanced'])")
[ "$BAL" = "True" ] && ok "守恒平衡（allBalanced=true）" || bad "守恒未平衡"
MULTI=$(echo "$CONS" | py "import sys,json;d=json.load(sys.stdin);print(len(d['sameSkuDifferentPrice']))")
[ "$MULTI" -ge 1 ] && ok "存在同商品不同价 $MULTI 个（归属只看原订单行，不按当前价格回填）" || bad "同商品不同价样例缺失"

echo "=== 4. 跨月部分退款：财务发生 vs 运营订单 ==="
NCV=$(curl -s "$BASE/api/metrics/net_sales_financial/published" -H "$AUTH" | py "import sys,json;print(json.load(sys.stdin)['id'])")
NOV=$(curl -s "$BASE/api/metrics/net_sales_order_batch/published" -H "$AUTH" | py "import sys,json;print(json.load(sys.stdin)['id'])")

run_s001() {
  local key=$1 vid=$2 from=$3 to=$4 purpose=$5
  local r=$(curl -s -X POST "$BASE/api/runs" -H "$AUTH" -H "Content-Type: application/json" \
    -d "{\"metricKey\":\"$key\",\"metricVersionId\":$vid,\"from\":\"$from\",\"to\":\"$to\",\"grain\":\"month\",\"purpose\":\"$purpose\"}")
  local id=$(echo "$r" | py "import sys,json;d=json.load(sys.stdin);print(d.get('id','ERR'))" 2>/dev/null)
  if [ "$id" = "ERR" ]; then echo "ERR $(echo "$r" | py "import sys,json;print(json.load(sys.stdin).get('error',''))")"; return; fi
  curl -s "$BASE/api/runs/$id/cells" -H "$AUTH" | py "
import sys,json
for c in json.load(sys.stdin):
    if 'S001' in str(c.get('dimsJson','')): print('$id', c['valueNum']); break
"
}
read JAN_F_RUN JAN_F <<< "$(run_s001 net_sales_financial $NCV 2026-01-01 2026-01-31 financial)"
read JAN_O_RUN JAN_O <<< "$(run_s001 net_sales_order_batch $NOV 2026-01-01 2026-01-31 order_batch)"
read FEB_F_RUN FEB_F <<< "$(run_s001 net_sales_financial $NCV 2026-02-01 2026-02-28 financial)"
read FEB_O_RUN FEB_O <<< "$(run_s001 net_sales_order_batch $NOV 2026-02-01 2026-02-28 order_batch)"
note "1月：财务=$JAN_F 运营=$JAN_O ；2月：财务=$FEB_F 运营=$FEB_O"
# R101 200 元：1/28 订单，2/3 入账 → 财务只在 2 月扣，运营只在 1 月扣
JANCOMP=$(curl -s "$BASE/api/refund-semantics/period-comparison?from=2026-01-01&to=2026-01-31" -H "$AUTH")
FEBCOMP=$(curl -s "$BASE/api/refund-semantics/period-comparison?from=2026-02-01&to=2026-02-28" -H "$AUTH")
JAN_OB_R101=$(echo "$JANCOMP" | py "import sys,json;print(sum(r['amountSigned'] for r in json.load(sys.stdin)['order_batch']['rows'] if r['refundId']=='R101'))")
JAN_F_R101=$(echo "$JANCOMP" | py "import sys,json;print(sum(r['amountSigned'] for r in json.load(sys.stdin)['financial']['rows'] if r['refundId']=='R101'))")
FEB_F_R101=$(echo "$FEBCOMP" | py "import sys,json;print(sum(r['amountSigned'] for r in json.load(sys.stdin)['financial']['rows'] if r['refundId']=='R101'))")
FEB_OB_R101=$(echo "$FEBCOMP" | py "import sys,json;print(sum(r['amountSigned'] for r in json.load(sys.stdin)['order_batch']['rows'] if r['refundId']=='R101'))")
[ "$JAN_OB_R101" = "200.0" ] && [ "$JAN_F_R101" = "0" ] && ok "跨月退款 R101(200) 在运营订单口径计入 1 月" || bad "1月归属异常: ob=$JAN_OB_R101 f=$JAN_F_R101"
[ "$FEB_F_R101" = "200.0" ] && [ "$FEB_OB_R101" = "0" ] && ok "同一笔退款在财务发生口径计入 2 月（不可互换）" || bad "2月归属异常: f=$FEB_F_R101 ob=$FEB_OB_R101"

echo "=== 5. 未选择用途的请求被拒绝（不默认最近分支） ==="
R=$(curl -s -X POST "$BASE/api/runs" -H "$AUTH" -H "Content-Type: application/json" \
  -d "{\"metricKey\":\"net_sales_financial\",\"metricVersionId\":$NCV,\"from\":\"2026-01-01\",\"to\":\"2026-01-31\",\"grain\":\"month\"}")
echo "$R" | grep -q "未选择用途" && ok "未选择用途被拒绝生成结果" || bad "未选择用途未被拒绝: $R"
R=$(curl -s -X POST "$BASE/api/runs" -H "$AUTH" -H "Content-Type: application/json" \
  -d "{\"metricKey\":\"net_sales_financial\",\"metricVersionId\":$NCV,\"from\":\"2026-01-01\",\"to\":\"2026-01-31\",\"grain\":\"month\",\"purpose\":\"order_batch\"}")
echo "$R" | grep -q "用途不匹配" && ok "固定用途指标不能被另一种用途运行" || bad "跨用途运行未被拒绝: $R"

echo "=== 6. 退款无法关联订单行 → 待归属（不猜填） ==="
PEND=$(curl -s "$BASE/api/refund-semantics/pending" -H "$AUTH")
PAMT=$(echo "$PEND" | py "import sys,json;print(json.load(sys.stdin)['byPurpose']['financial']['pendingAmount'])")
NAMT=$(echo "$PEND" | py "import sys,json;print(len(json.load(sys.stdin)['rows']))")
[ "$NAMT" -ge 1 ] && ok "待归属退款 $NAMT 笔，金额 $PAMT（不进入任何期间）" || bad "待归属退款缺失"
NUL=$(echo "$PEND" | py "import sys,json;d=json.load(sys.stdin);print(all(r['attributionDate'] is None for r in d['rows']))")
[ "$NUL" = "True" ] && ok "待归属行归属日期为空，未按同名商品/当前价格回填" || bad "待归属行被错误回填"

echo "=== 7. 派生指标引用两种净销售额（对账，仅校验） ==="
RCV=$(curl -s "$BASE/api/metrics/net_sales_reconciliation/published" -H "$AUTH" | py "import sys,json;print(json.load(sys.stdin)['id'])")
R=$(curl -s -X POST "$BASE/api/runs" -H "$AUTH" -H "Content-Type: application/json" \
  -d "{\"metricKey\":\"net_sales_reconciliation\",\"metricVersionId\":$RCV,\"from\":\"2026-01-01\",\"to\":\"2026-01-31\",\"grain\":\"month\",\"purpose\":\"reconciliation\"}")
RCID=$(echo "$R" | py "import sys,json;d=json.load(sys.stdin);print(d.get('id','ERR'))")
DIFF=$(curl -s "$BASE/api/runs/$RCID/cells" -H "$AUTH" | py "
import sys,json
for c in json.load(sys.stdin):
    if 'S001' in str(c.get('dimsJson','')): print(abs(c['valueNum'])); break")
[ "$DIFF" = "200.0" ] && ok "对账派生指标独立复算两用途，差异=跨月部分退款 200" || bad "对账派生差异异常: $DIFF"

echo "=== 8. 同一仪表盘不得混用两种用途 ==="
REP=$(curl -s -X POST "$BASE/api/reports" -H "$AUTH" -H "Content-Type: application/json" -d '{"name":"用途混用测试"}')
RID=$(echo "$REP" | py "import sys,json;print(json.load(sys.stdin)['id'])")
curl -s -X POST "$BASE/api/reports/$RID/items" -H "$AUTH" -H "Content-Type: application/json" \
  -d "{\"metricKey\":\"net_sales_financial\",\"metricVersionId\":$NCV,\"runId\":$JAN_F_RUN}" > /dev/null
MIX=$(curl -s -X POST "$BASE/api/reports/$RID/items" -H "$AUTH" -H "Content-Type: application/json" \
  -d "{\"metricKey\":\"net_sales_order_batch\",\"metricVersionId\":$NOV,\"runId\":$JAN_O_RUN}")
echo "$MIX" | grep -q "用途不一致" && ok "不同用途加入同一报表被拒绝（不可比序列）" || bad "用途混用未被拒绝: $MIX"
curl -s -X POST "$BASE/api/reports/$RID/publish" -H "$AUTH" > /dev/null
ITEMS=$(curl -s "$BASE/api/reports/$RID/items" -H "$AUTH" | py "import sys,json;it=json.load(sys.stdin);print(it[0]['purpose'], it[0]['referenceIdentity'])")
note "报表项固定引用身份：$ITEMS"
echo "$ITEMS" | grep -q "financial/REFUND_TIME" && ok "消费端展示用途、适用用户与引用身份" || bad "报表项缺少口径身份"

echo "=== 8b. 为运营订单口径建立 1 月报表（迟到退款影响候选需要报表项） ==="
REP2=$(curl -s -X POST "$BASE/api/reports" -H "$AUTH" -H "Content-Type: application/json" -d '{"name":"1月门店批次"}')
RID2=$(echo "$REP2" | py "import sys,json;print(json.load(sys.stdin)['id'])")
curl -s -X POST "$BASE/api/reports/$RID2/items" -H "$AUTH" -H "Content-Type: application/json" \
  -d "{\"metricKey\":\"net_sales_order_batch\",\"metricVersionId\":$NOV,\"runId\":$JAN_O_RUN}" > /dev/null
curl -s -X POST "$BASE/api/reports/$RID2/publish" -H "$AUTH" > /dev/null
ok "运营订单口径报表已发布（run $JAN_O_RUN）"

echo "=== 9. 迟到退款：按用途与期间标出候选 ==="
LR=$(curl -s -X POST "$BASE/api/replay/late-refund" -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"refundId":"R-VRF-LATE","orderId":"O001","orderLineId":"L011","storeId":"S001","businessDate":"2026-02-15","amount":"30.00","quantity":1,"sku":"SKU-A"}')
RVB=$(echo "$LR" | py "import sys,json;print(json.load(sys.stdin)['impact']['attributionRevisionBefore'])")
RVA=$(echo "$LR" | py "import sys,json;print(json.load(sys.stdin)['impact']['attributionRevisionAfter'])")
[ "$RVA" -gt "$RVB" ] && ok "迟到退款追加归属修订 v$RVB -> v$RVA（旧修订保留）" || bad "归属修订未追加"
HIT=$(echo "$LR" | py "import sys,json;d=json.load(sys.stdin)['impact']['affectedPurposesPeriods'];print('order_batch' in d, 'financial' in d, d.get('order_batch'))")
echo "$HIT" | grep -q "True False" && ok "迟到退款只标出受影响用途：order_batch（原交易日 1 月），2 月入账不影响 1 月财务" || bad "影响用途标记异常: $HIT"
OLDREPORT=$(curl -s "$BASE/api/reports/$RID/items" -H "$AUTH" | py "import sys,json;print(json.load(sys.stdin)[0]['runId'])")
[ "$OLDREPORT" = "$JAN_F_RUN" ] && ok "已发布报表继续引用原运行 $JAN_F_RUN" || bad "报表被自动改写"

echo "=== 10. 旧运行按原输入复演（口径版本/归属修订固定） ==="
RP=$(curl -s -X POST "$BASE/api/runs/$JAN_F_RUN/replay" -H "$AUTH")
IDENT=$(echo "$RP" | py "import sys,json;print(json.load(sys.stdin)['identical'])")
[ "$IDENT" = "True" ] && ok "旧运行复演结果一致（不随新批次变化）" || bad "复演结果不一致: $RP"

echo "=== 11. 迟到更正：原订单行更正只影响运营订单期间 ==="
COR=$(curl -s -X POST "$BASE/api/sources/order-lines/L081/correct" -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"businessDate":"2026-02-02","reason":"营业日改判"}')
CORR=$(echo "$COR" | py "import sys,json;print(json.load(sys.stdin)['impact']['attributionRevisionBefore'])")
CORA=$(echo "$COR" | py "import sys,json;print(json.load(sys.stdin)['impact']['attributionRevisionAfter'])")
[ "$CORA" -gt "$CORR" ] && ok "原订单行更正生成新归属修订 v$CORR -> v$CORA" || bad "更正未生成新修订"
AFF=$(echo "$COR" | py "
import sys,json
d=json.load(sys.stdin)['impact']['affected']['affectedPurposes']
ob=[p for p in d if p['purpose']=='order_batch'][0]
fi=[p for p in d if p['purpose']=='financial'][0]
print('financial_affected', fi['affected'])
print('order_batch_after', ob['periodsAfter'])")
echo "$AFF" | grep -q "financial_affected False" && ok "财务发生口径期间不受订单行更正影响（按入账日）" || bad "财务口径被误标: $AFF"
echo "$AFF" | grep -q "order_batch_after \['2026-02'\]" && ok "运营订单口径期间随原交易日更正更新为 2026-02" || bad "运营口径期间未更新: $AFF"

echo "=== 12. 并发修改用途：陈旧 profile 版本被拒绝 ==="
CUR=$(curl -s "$BASE/api/refund-semantics/purposes" -H "$AUTH" | py "import sys,json;print([p['profileVersion'] for p in json.load(sys.stdin) if p['purpose']=='financial'][0])")
A1=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$BASE/api/refund-semantics/purposes/financial" -H "$AUTH" \
  -H "Content-Type: application/json" -d "{\"expectedProfileVersion\":$CUR,\"applicableUsers\":\"总部财务（并发 A）\"}")
A2=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$BASE/api/refund-semantics/purposes/financial" -H "$AUTH" \
  -H "Content-Type: application/json" -d "{\"expectedProfileVersion\":$CUR,\"applicableUsers\":\"总部财务（并发 B）\"}")
[ "$A1" = "200" ] && [ "$A2" = "409" ] && ok "并发修改用途：首个成功(200)，第二个因陈旧版本被拒(409)" || bad "并发用途修改异常: $A1 / $A2"

echo "=== 13. 用途档案与口径版本一致性（修复版本/口径身份） ==="
IDN=$(curl -s "$BASE/api/refund-semantics/identity?purpose=financial" -H "$AUTH")
echo "$IDN" | grep -q "REFUND_TIME@v" && ok "引用身份=用途/口径/profile/归属修订：$(echo "$IDN" | py "import sys,json;print(json.load(sys.stdin)['purposeRef'])")" || bad "引用身份缺失"

echo "=== 14. 语义编译：引用指标固定口径版本不一致时拒绝 ==="
# 直接改动发布清单模拟“某依赖指标仍固定在旧口径”，验证编译期一致性守卫
DBHOST="${DBHOST:-127.0.0.1}"
DBPORT="${DBPORT:-5432}"
if PGPASSWORD=ccdev /opt/postgresql-18/bin/psql -h "$DBHOST" -p "$DBPORT" -U ccdev -d ccdev -c "select 1" >/dev/null 2>&1 \
   || PGPASSWORD=ccdev psql -h "$DBHOST" -p "$DBPORT" -U ccdev -d ccdev -c "select 1" >/dev/null 2>&1; then
  PSQL=""
  if PGPASSWORD=ccdev /opt/postgresql-18/bin/psql -h "$DBHOST" -p "$DBPORT" -U ccdev -d ccdev -c "select 1" >/dev/null 2>&1; then
    PSQL="/opt/postgresql-18/bin/psql"
  else
    PSQL="psql"
  fi
  PGPASSWORD=ccdev $PSQL -h "$DBHOST" -p "$DBPORT" -U ccdev -d ccdev -q \
    -c "update metric_release set semantics_id=999 where metric_key='refund_amount_financial'" >/dev/null
  R=$(curl -s -X POST "$BASE/api/runs" -H "$AUTH" -H "Content-Type: application/json" \
    -d "{\"metricKey\":\"net_sales_financial\",\"metricVersionId\":$NCV,\"from\":\"2026-01-01\",\"to\":\"2026-01-31\",\"grain\":\"month\",\"purpose\":\"financial\"}")
  echo "$R" | grep -q "语义编译失败" && ok "依赖指标口径版本不一致时拒绝生成结果" || bad "口径版本不一致未被拦截: $R"
  PGPASSWORD=ccdev $PSQL -h "$DBHOST" -p "$DBPORT" -U ccdev -d ccdev -q \
    -c "update metric_release set semantics_id=(select id from refund_semantics where code='REFUND_TIME' and status='published' order by version desc limit 1) where metric_key='refund_amount_financial'" >/dev/null
  R=$(curl -s -X POST "$BASE/api/runs" -H "$AUTH" -H "Content-Type: application/json" \
    -d "{\"metricKey\":\"net_sales_financial\",\"metricVersionId\":$NCV,\"from\":\"2026-01-01\",\"to\":\"2026-01-31\",\"grain\":\"month\",\"purpose\":\"financial\"}")
  echo "$R" | grep -q '"status":"success"' && ok "恢复口径后同一请求成功" || bad "恢复口径后运行失败: $R"
else
  note "跳过：本机无可用 psql 连接，语义编译一致性需在数据库可达时验证"
fi

echo "=== 15. 定义审批固定用途与口径版本，批准后发布清单一致 ==="
PROBE="approval_probe_$$"
curl -s -X POST "$BASE/api/metrics" -H "$AUTH" -H "Content-Type: application/json" \
  -d "{\"metricKey\":\"$PROBE\",\"name\":\"审批探针\",\"grain\":\"day\",\"purpose\":\"financial\",\"definition\":\"{\\\"op\\\":\\\"ref\\\",\\\"metric\\\":\\\"net_sales_financial\\\",\\\"purpose\\\":\\\"financial\\\"}\"}" >/dev/null
APP=$(curl -s -X POST "$BASE/api/approvals" -H "$AUTH" -H "Content-Type: application/json" \
  -d "{\"metricKey\":\"$PROBE\",\"toVersion\":1,\"changeType\":\"create\",\"purpose\":\"financial\"}")
AID=$(echo "$APP" | py "import sys,json;d=json.load(sys.stdin);print(d.get('id','ERR'))")
PUR=$(echo "$APP" | py "import sys,json;d=json.load(sys.stdin);print(d.get('purpose',''))")
[ "$AID" != "ERR" ] && [ "$PUR" = "financial" ] && ok "审批提交固定用途 financial（审批ID $AID）" || bad "审批提交异常: $APP"
curl -s -X POST "$BASE/api/approvals/$AID/approve" -H "$AUTH" -H "Content-Type: application/json" -d '{"note":"通过"}' >/dev/null
REL=$(curl -s "$BASE/api/metrics/$PROBE/releases" -H "$AUTH" | py "
import sys,json
rs=json.load(sys.stdin)
print(rs[0]['purpose'], rs[0]['semanticsVersion'], 'REF' if rs[0]['referenceIdentity'] else 'NONE')")
echo "$REL" | grep -q "financial 1 REF" && ok "批准发布清单固定同一口径版本（financial / REFUND_TIME@v1）" || bad "发布清单口径不一致: $REL"

echo ""
echo "=========================================="
echo "验证结果: 通过 $PASS 项, 失败 $FAIL 项"
echo "=========================================="
[ $FAIL -eq 0 ] && exit 0 || exit 1
