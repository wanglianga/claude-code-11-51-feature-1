package com.semantic.platform.source;

import com.semantic.platform.metric.MetricService;
import com.semantic.platform.semantics.AttributionRevision;
import com.semantic.platform.semantics.PurposeProfile;
import com.semantic.platform.semantics.PurposeProfileRepository;
import com.semantic.platform.semantics.RefundAttributionService;
import com.semantic.platform.semantics.RefundSemantics;
import com.semantic.platform.semantics.RefundSemanticsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 样例数据播种：订单/订单行/退款/门店状态/节假日/批次，以及预置指标与报表。
 * 覆盖回放场景：跨营业日订单、多次部分退款、临时闭店、跨期新店、缺批次数据、迟到退款。
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final OrderFactRepository orderRepo;
    private final OrderLineRepository orderLineRepo;
    private final RefundFactRepository refundRepo;
    private final StoreStatusRepository storeStatusRepo;
    private final HolidayRepository holidayRepo;
    private final SourceBatchRepository batchRepo;
    private final MetricService metricService;
    private final RefundSemanticsRepository semanticsRepo;
    private final PurposeProfileRepository purposeRepo;
    private final RefundAttributionService attributionService;

    public DataSeeder(OrderFactRepository orderRepo, OrderLineRepository orderLineRepo,
                      RefundFactRepository refundRepo, StoreStatusRepository storeStatusRepo,
                      HolidayRepository holidayRepo, SourceBatchRepository batchRepo,
                      MetricService metricService, RefundSemanticsRepository semanticsRepo,
                      PurposeProfileRepository purposeRepo, RefundAttributionService attributionService) {
        this.orderRepo = orderRepo;
        this.orderLineRepo = orderLineRepo;
        this.refundRepo = refundRepo;
        this.storeStatusRepo = storeStatusRepo;
        this.holidayRepo = holidayRepo;
        this.batchRepo = batchRepo;
        this.metricService = metricService;
        this.semanticsRepo = semanticsRepo;
        this.purposeRepo = purposeRepo;
        this.attributionService = attributionService;
    }

    @Override
    public void run(String... args) {
        if (orderRepo.count() > 0) {
            log.info("样例数据已存在，跳过播种");
            return;
        }
        log.info("开始播种样例数据...");
        seedStoreStatus();
        seedHolidays();
        seedOrders();
        seedRefunds();
        seedBatches();
        seedSemantics();
        seedMetrics();
        // 生成首个归属修订（依据退款事实）
        RefundSemantics sem = attributionService.publishedSemantics();
        AttributionRevision rev = attributionService.rebuild(sem.getId(), "system");
        log.info("初始退款归属修订 v{}（口径 {}）", rev.getRevision(), sem.refIdentity());
        log.info("样例数据播种完成");
    }

    // ---------- 门店营业状态 ----------
    private void seedStoreStatus() {
        List<StoreStatus> list = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 3, 31);
        String[] stores = {"S001", "S002", "S003", "S004", "S005"};
        for (String store : stores) {
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                StoreStatus s = new StoreStatus();
                s.setStoreId(store);
                s.setBusinessDate(d);
                // S004 新店 2/10 才开业
                if (store.equals("S004") && d.isBefore(LocalDate.of(2026, 2, 10))) {
                    s.setStatus("closed");
                    s.setOpenHours(BigDecimal.ZERO);
                }
                // S005 2/15-2/21 临时闭店
                else if (store.equals("S005") && !d.isBefore(LocalDate.of(2026, 2, 15)) && !d.isAfter(LocalDate.of(2026, 2, 21))) {
                    s.setStatus("temp_closed");
                    s.setOpenHours(BigDecimal.ZERO);
                } else {
                    s.setStatus("open");
                    s.setOpenHours(new BigDecimal("13"));
                }
                list.add(s);
            }
        }
        storeStatusRepo.saveAll(list);
    }

    // ---------- 节假日 ----------
    private void seedHolidays() {
        holidayRepo.save(holiday(LocalDate.of(2026, 1, 1), "元旦", "public_holiday"));
        holidayRepo.save(holiday(LocalDate.of(2026, 2, 16), "春节", "public_holiday"));
        holidayRepo.save(holiday(LocalDate.of(2026, 2, 17), "春节", "public_holiday"));
        holidayRepo.save(holiday(LocalDate.of(2026, 2, 18), "春节", "public_holiday"));
    }

    private Holiday holiday(LocalDate date, String name, String type) {
        Holiday h = new Holiday();
        h.setHolidayDate(date);
        h.setName(name);
        h.setType(type);
        return h;
    }

    // ---------- 订单（含跨营业日、多商品、迟到补录、外币） ----------
    private void seedOrders() {
        // S001 深夜门店（营业到次日 02:00）
        order("O001", "S001", "2026-01-09T20:00:00Z", "2026-01-09T20:05:00Z", LocalDate.of(2026, 1, 9), "500.00", "CNY", "completed", "pos");
        orderLine("L011", "O001", "S001", LocalDate.of(2026, 1, 9), "SKU-A", 2, "200.00", "400.00");
        orderLine("L012", "O001", "S001", LocalDate.of(2026, 1, 9), "SKU-B", 1, "100.00", "100.00");
        // 跨营业日：1/10 01:15 下单，计入 1/9 营业日
        order("O002", "S001", "2026-01-10T01:15:00Z", "2026-01-10T01:16:00Z", LocalDate.of(2026, 1, 9), "300.00", "CNY", "completed", "pos");
        // 多商品订单（3 行，合计 900）
        order("O003", "S001", "2026-01-15T12:00:00Z", "2026-01-15T12:01:00Z", LocalDate.of(2026, 1, 15), "900.00", "CNY", "completed", "pos");
        orderLine("L001", "O003", "S001", LocalDate.of(2026, 1, 15), "SKU-A", 2, "200.00", "400.00");
        orderLine("L002", "O003", "S001", LocalDate.of(2026, 1, 15), "SKU-B", 1, "300.00", "300.00");
        orderLine("L003", "O003", "S001", LocalDate.of(2026, 1, 15), "SKU-C", 2, "100.00", "200.00");
        // 外币订单（USD），用于币种校验演示
        order("O004", "S001", "2026-01-20T10:00:00Z", "2026-01-20T10:01:00Z", LocalDate.of(2026, 1, 20), "100.00", "USD", "completed", "online");
        // 部分退款订单：同商品 SKU-A 在 O001(200)/O003(200)/O005(300) 出现不同单价，
        // 退款只按原订单行追溯，绝不按同名商品当前价格回填。
        order("O005", "S001", "2026-02-05T14:00:00Z", "2026-02-05T14:01:00Z", LocalDate.of(2026, 2, 5), "800.00", "CNY", "partial_refund", "pos");
        orderLine("L051", "O005", "S001", LocalDate.of(2026, 2, 5), "SKU-A", 2, "300.00", "600.00");
        orderLine("L052", "O005", "S001", LocalDate.of(2026, 2, 5), "SKU-B", 1, "200.00", "200.00");

        // 跨月部分退款场景：O008（1/28 订单，3 行），R101 在 2/3 部分退款 SKU-A（追溯原订单日 1/28）
        order("O008", "S001", "2026-01-28T10:00:00Z", "2026-01-28T10:01:00Z", LocalDate.of(2026, 1, 28), "600.00", "CNY", "partial_refund", "pos");
        orderLine("L081", "O008", "S001", LocalDate.of(2026, 1, 28), "SKU-A", 1, "200.00", "200.00");
        orderLine("L082", "O008", "S001", LocalDate.of(2026, 1, 28), "SKU-D", 1, "150.00", "150.00");
        orderLine("L083", "O008", "S001", LocalDate.of(2026, 1, 28), "SKU-E", 1, "250.00", "250.00");
        // 无法关联订单行的退款：O009 只有 SKU-B 行，但退款只给了不可定位的行号 L-NOT-EXIST
        order("O009", "S001", "2026-02-20T10:00:00Z", "2026-02-20T10:01:00Z", LocalDate.of(2026, 2, 20), "450.00", "CNY", "completed", "pos");
        orderLine("L091", "O009", "S001", LocalDate.of(2026, 2, 20), "SKU-A", 1, "450.00", "450.00");
        // S001 2月其它订单
        order("O006", "S001", "2026-02-10T11:00:00Z", "2026-02-10T11:01:00Z", LocalDate.of(2026, 2, 10), "650.00", "CNY", "completed", "pos");
        order("O007", "S001", "2026-03-05T11:00:00Z", "2026-03-05T11:01:00Z", LocalDate.of(2026, 3, 5), "720.00", "CNY", "completed", "pos");

        // S002 普通门店（22:00 闭店）
        order("O101", "S002", "2026-01-20T21:00:00Z", "2026-01-20T21:01:00Z", LocalDate.of(2026, 1, 20), "400.00", "CNY", "completed", "pos");
        // 迟到补录：1/20 23:30（闭店后）下单，补录计入下一营业日 1/21
        order("O102", "S002", "2026-01-20T23:30:00Z", "2026-01-20T23:31:00Z", LocalDate.of(2026, 1, 21), "250.00", "CNY", "completed", "pos");
        order("O103", "S002", "2026-02-10T10:00:00Z", "2026-02-10T10:01:00Z", LocalDate.of(2026, 2, 10), "600.00", "CNY", "completed", "pos");
        order("O104", "S002", "2026-03-08T10:00:00Z", "2026-03-08T10:01:00Z", LocalDate.of(2026, 3, 8), "550.00", "CNY", "completed", "pos");

        // S003（2/1-2/7 缺批次）
        order("O201", "S003", "2026-01-15T10:00:00Z", "2026-01-15T10:01:00Z", LocalDate.of(2026, 1, 15), "700.00", "CNY", "completed", "pos");
        order("O202", "S003", "2026-02-10T10:00:00Z", "2026-02-10T10:01:00Z", LocalDate.of(2026, 2, 10), "450.00", "CNY", "completed", "pos");
        order("O203", "S003", "2026-03-03T10:00:00Z", "2026-03-03T10:01:00Z", LocalDate.of(2026, 3, 3), "520.00", "CNY", "completed", "pos");

        // S004 新店（2/10 开业）
        order("O301", "S004", "2026-02-15T10:00:00Z", "2026-02-15T10:01:00Z", LocalDate.of(2026, 2, 15), "550.00", "CNY", "completed", "pos");
        order("O302", "S004", "2026-03-10T10:00:00Z", "2026-03-10T10:01:00Z", LocalDate.of(2026, 3, 10), "610.00", "CNY", "completed", "pos");

        // S005（2/15-2/21 临时闭店）
        order("O401", "S005", "2026-02-10T10:00:00Z", "2026-02-10T10:01:00Z", LocalDate.of(2026, 2, 10), "380.00", "CNY", "completed", "pos");
        order("O402", "S005", "2026-02-25T10:00:00Z", "2026-02-25T10:01:00Z", LocalDate.of(2026, 2, 25), "420.00", "CNY", "completed", "pos");
        order("O403", "S005", "2026-01-12T10:00:00Z", "2026-01-12T10:01:00Z", LocalDate.of(2026, 1, 12), "460.00", "CNY", "completed", "pos");
    }

    private void order(String id, String store, String orderedAt, String paidAt, LocalDate businessDate,
                       String amount, String currency, String status, String channel) {
        OrderFact o = new OrderFact();
        o.setOrderId(id);
        o.setStoreId(store);
        o.setOrderedAt(OffsetDateTime.parse(orderedAt));
        o.setPaidAt(OffsetDateTime.parse(paidAt));
        o.setBusinessDate(businessDate);
        o.setAmount(new BigDecimal(amount));
        o.setCurrency(currency);
        o.setStatus(status);
        o.setChannel(channel);
        orderRepo.save(o);
    }

    private void orderLine(String lineId, String orderId, String store, LocalDate businessDate,
                           String sku, int qty, String price, String amount) {
        OrderLine l = new OrderLine();
        l.setLineId(lineId);
        l.setOrderId(orderId);
        l.setStoreId(store);
        l.setBusinessDate(businessDate);
        l.setSku(sku);
        l.setQuantity(qty);
        l.setUnitPrice(new BigDecimal(price));
        l.setAmount(new BigDecimal(amount));
        orderLineRepo.save(l);
    }

    // ---------- 退款（含多次部分退款；迟到退款作为回放场景单独处理） ----------
    private void seedRefunds() {
        // O005 两次部分退款（行级，追溯 L051）
        refund("R001", "O005", "L051", "S001", "2026-02-06T10:00:00Z",
                LocalDate.of(2026, 2, 6), LocalDate.of(2026, 2, 5), 1, 1,
                "200.00", "SKU-A", "linked", "质量问题");
        refund("R002", "O005", "L052", "S001", "2026-02-07T10:00:00Z",
                LocalDate.of(2026, 2, 7), LocalDate.of(2026, 2, 5), 1, 1,
                "100.00", "SKU-B", "linked", "少发");
        // 跨月部分退款：O008(1/28) 的 SKU-A，退款实际入账 2/3 —— 财务发生计 2 月，运营订单追溯 1 月
        refund("R101", "O008", "L081", "S001", "2026-02-03T09:00:00Z",
                LocalDate.of(2026, 2, 3), LocalDate.of(2026, 1, 28), 1, 1,
                "200.00", "SKU-A", "linked", "跨月部分退款");
        // 无法可靠关联原订单行的退款：退款给的行号不存在 -> 待归属，不按同名商品/当前价格猜填
        refund("R201", "O009", "L-NOT-EXIST", "S001", "2026-02-25T10:00:00Z",
                LocalDate.of(2026, 2, 25), null, null, 1,
                "80.00", "SKU-A", "linked", "POS 退款行号未回传，无法可靠定位原订单行");
        // 退款撤销（方向 -1）：冲回 R002 的一部分
        refund("R301", "O005", "L052", "S001", "2026-03-02T10:00:00Z",
                LocalDate.of(2026, 3, 2), LocalDate.of(2026, 2, 5), 1, -1,
                "40.00", "SKU-B", "linked", "退款撤销（少发补回）");
        // 迟到退款 R003（O001，1月订单）在批次回放中作为“迟到退款”到达，不预置
    }

    private void refund(String id, String orderId, String orderLineId, String store, String refundedAt,
                        LocalDate postingDate, LocalDate originalDate, Integer quantity, int direction,
                        String amount, String sku, String linkStatus, String reason) {
        RefundFact r = new RefundFact();
        r.setRefundId(id);
        r.setOrderId(orderId);
        r.setOrderLineId(orderLineId);
        r.setStoreId(store);
        r.setRefundedAt(OffsetDateTime.parse(refundedAt));
        r.setBusinessDate(postingDate);
        r.setOriginalBusinessDate(originalDate);
        r.setQuantity(quantity);
        r.setDirection(direction);
        r.setAmount(new BigDecimal(amount));
        r.setSku(sku);
        r.setLinkStatus(linkStatus);
        r.setReason(reason);
        refundRepo.save(r);
    }

    // ---------- 批次（含缺批次、迟到批次） ----------
    private void seedBatches() {
        // 订单批次：按门店分批
        batch("orders", "S001", "loaded", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31), 7);
        batch("orders", "S002", "loaded", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31), 4);
        batch("orders", "S003", "loaded", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), 1);
        // S003 2/1-2/7 仍在补数（缺批次场景）
        batch("orders", "S003", "backfilling", LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 7), 0);
        batch("orders", "S003", "loaded", LocalDate.of(2026, 2, 8), LocalDate.of(2026, 3, 31), 2);
        batch("orders", "S004", "loaded", LocalDate.of(2026, 2, 10), LocalDate.of(2026, 3, 31), 2);
        batch("orders", "S005", "loaded", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31), 3);
        // 退款批次：先到一批（不含 R003）
        batch("refunds", null, "loaded", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31), 4);
        // 门店状态与节假日批次
        batch("store_hours", null, "loaded", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31), 450);
        batch("holidays", null, "loaded", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), 4);
    }

    private void batch(String type, String store, String status, LocalDate start, LocalDate end, int count) {
        SourceBatch b = new SourceBatch();
        b.setSourceType(type);
        b.setStoreId(store);
        b.setStatus(status);
        b.setPeriodStart(start);
        b.setPeriodEnd(end);
        b.setRecordCount(count);
        batchRepo.save(b);
    }

    // ---------- 退款时间口径与用途档案 ----------
    private void seedSemantics() {
        RefundSemantics sem = new RefundSemantics();
        sem.setCode("REFUND_TIME");
        sem.setVersion(1);
        sem.setStatus("published");
        sem.setNote("用途绑定的退款时间口径 v1：财务发生按实际入账日，运营订单按原交易日");
        sem.setRulesJson("{"
                + "\"purposes\":{"
                + "\"financial\":{\"label\":\"财务发生\",\"timeBasis\":\"posting_date\","
                + "\"appliesTo\":\"总部财务当期收入\",\"rule\":\"退款计入实际入账日所在期间\"},"
                + "\"order_batch\":{\"label\":\"运营订单\",\"timeBasis\":\"original_business_date\","
                + "\"appliesTo\":\"门店运营销售批次评估\",\"rule\":\"退款追溯原订单日，只修订对应订单批次\"},"
                + "\"reconciliation\":{\"label\":\"对账复算\",\"timeBasis\":\"both\","
                + "\"appliesTo\":\"财务与运营对账排障\",\"rule\":\"仅用于守恒校验，不作为业务序列\"}},"
                + "\"pendingRule\":\"无法可靠关联原订单行的退款列为待归属，不按同名商品或当前价格回填\","
                + "\"comparabilityRule\":\"同一仪表盘不得把 financial 与 order_batch 当成可比的同一序列\""
                + "}");
        sem.setCreatedBy("system");
        sem = semanticsRepo.save(sem);
        sem.setStatus("published");
        sem.setPublishedAt(OffsetDateTime.now());
        sem = semanticsRepo.save(sem);

        purpose("financial", "财务发生", "总部财务", sem);
        purpose("order_batch", "运营订单批次", "门店运营", sem);
        purpose("reconciliation", "对账复算（仅校验）", "数据分析/财务运营对账", sem);
    }

    private void purpose(String code, String label, String users, RefundSemantics sem) {
        PurposeProfile p = new PurposeProfile();
        p.setPurpose(code);
        p.setLabel(label);
        p.setApplicableUsers(users);
        p.setSemanticsId(sem.getId());
        p.setProfileVersion(1);
        p.setUpdatedBy("system");
        purposeRepo.save(p);
    }

    // ---------- 预置指标 ----------
    private void seedMetrics() {
        // 毛销售额（仅 CNY，演示币种过滤）
        publish("gross_sales", "毛销售额", "已完成及部分退款订单的销售额（CNY）", "day",
                "{\"op\":\"agg\",\"measure\":\"sum\",\"expr\":{\"op\":\"field\",\"source\":\"orders\",\"field\":\"amount\"},"
                + "\"filter\":[{\"source\":\"orders\",\"field\":\"status\",\"operator\":\"in\",\"value\":[\"completed\",\"partial_refund\"]},"
                + "{\"source\":\"orders\",\"field\":\"currency\",\"operator\":\"eq\",\"value\":\"CNY\"}],"
                + "\"by\":[\"store_id\"]}");

        // 退款时间口径模板：定义本身不带用途，运行/下游必须显式固定 financial 或 order_batch。
        String refundTemplate =
                "{\"op\":\"agg\",\"measure\":\"sum\",\"expr\":{\"op\":\"field\",\"source\":\"refund_attributions\",\"field\":\"amount\"},"
                + "\"filter\":[{\"source\":\"refund_attributions\",\"field\":\"purpose\",\"operator\":\"eq\",\"value\":\"__PURPOSE__\"}],"
                + "\"by\":[\"store_id\"]}";
        publish("refund_amount_financial", "退款金额（财务发生）", "按实际入账日归属的退款金额", "day",
                refundTemplate.replace("__PURPOSE__", "financial"), "financial");
        publish("refund_amount_order_batch", "退款金额（运营订单）", "按原交易日追溯订单批次的退款金额", "day",
                refundTemplate.replace("__PURPOSE__", "order_batch"), "order_batch");

        // 两个独立、可复算的净销售额定义：各自命名、审批、版本化，消费端固定引用 key。
        publish("net_sales_financial", "净销售额（财务发生）", "毛销售额 - 按实际入账日归属的退款；用于当期收入", "day",
                "{\"op\":\"arith\",\"operator\":\"sub\",\"left\":{\"op\":\"ref\",\"metric\":\"gross_sales\"},"
                + "\"right\":{\"op\":\"ref\",\"metric\":\"refund_amount_financial\",\"purpose\":\"financial\"}}",
                "financial");
        publish("net_sales_order_batch", "净销售额（运营订单）", "毛销售额 - 追溯原交易日归属的退款；用于订单批次表现", "day",
                "{\"op\":\"arith\",\"operator\":\"sub\",\"left\":{\"op\":\"ref\",\"metric\":\"gross_sales\"},"
                + "\"right\":{\"op\":\"ref\",\"metric\":\"refund_amount_order_batch\",\"purpose\":\"order_batch\"}}",
                "order_batch");

        // 不再发布“无用途 net_sales”：原始退款事实没有时间口径，必须经归属来源并固定用途。
        // 对账派生指标：显式引用两种用途，独立复算，不作为业务序列展示。
        publish("net_sales_reconciliation", "净销售额用途对账差异", "财务发生净销售额与运营订单净销售额的差额（仅对账）", "day",
                "{\"op\":\"arith\",\"operator\":\"sub\","
                + "\"left\":{\"op\":\"ref\",\"metric\":\"net_sales_financial\",\"purpose\":\"financial\"},"
                + "\"right\":{\"op\":\"ref\",\"metric\":\"net_sales_order_batch\",\"purpose\":\"order_batch\"}}",
                "reconciliation");

        // 订单数
        publish("order_count", "订单数", "已完成及部分退款订单数", "day",
                "{\"op\":\"agg\",\"measure\":\"count\",\"expr\":{\"op\":\"field\",\"source\":\"orders\",\"field\":\"order_id\"},"
                + "\"filter\":[{\"source\":\"orders\",\"field\":\"status\",\"operator\":\"in\",\"value\":[\"completed\",\"partial_refund\"]}],"
                + "\"by\":[\"store_id\"]}");
        // 客单价（财务发生口径，显式用途）
        publish("aov_financial", "客单价（财务发生）", "财务发生净销售额 / 订单数", "day",
                "{\"op\":\"arith\",\"operator\":\"div\","
                + "\"left\":{\"op\":\"ref\",\"metric\":\"net_sales_financial\",\"purpose\":\"financial\"},"
                + "\"right\":{\"op\":\"ref\",\"metric\":\"order_count\"}}",
                "financial");
        // 商品件数（演示订单行一对多）
        publish("units_sold", "商品件数", "订单行数量合计", "day",
                "{\"op\":\"agg\",\"measure\":\"sum\",\"expr\":{\"op\":\"field\",\"source\":\"order_lines\",\"field\":\"quantity\"},\"by\":[\"store_id\"]}");
        // 同店增长（财务发生口径环比）
        publish("same_store_growth_financial", "同店增长（财务发生）", "同店队列财务发生净销售额环比增长", "month",
                "{\"op\":\"cohort\",\"base\":{\"op\":\"ref\",\"metric\":\"net_sales_financial\",\"purpose\":\"financial\"},"
                + "\"compare\":\"prev_period\",\"cohortKey\":\"store_id\"}",
                "financial");
    }

    private void publish(String key, String name, String desc, String grain, String definition) {
        publish(key, name, desc, grain, definition, null);
    }

    private void publish(String key, String name, String desc, String grain, String definition, String purpose) {
        try {
            metricService.saveDraft(key, name, desc, grain, definition, "system", purpose);
            metricService.publish(key, 1, "system");
            log.info("发布指标 {} v1（用途 {}）", key, purpose == null ? "无" : purpose);
        } catch (Exception e) {
            log.warn("发布指标 {} 失败: {}", key, e.getMessage());
        }
    }
}
