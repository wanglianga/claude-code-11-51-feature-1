package com.semantic.platform.dsl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 指标 DSL 校验器单元测试：币种、粒度、环路、类型规则。
 */
class ExprValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private Expr parse(String json) throws Exception {
        return mapper.readValue(json, Expr.class);
    }

    private MetricType amountType(String currency, MetricType.TimeGrain grain) {
        return MetricType.amount(currency, grain, Set.of("store_id"));
    }

    @Test
    void testCurrencyMismatchRejected() throws Exception {
        // CNY（未过滤，混合）+ USD 直接相加 → 应报错
        Expr expr = parse("{\"op\":\"arith\",\"operator\":\"add\"," +
                "\"left\":{\"op\":\"agg\",\"measure\":\"sum\",\"expr\":{\"op\":\"field\",\"source\":\"orders\",\"field\":\"amount\"},\"by\":[\"store_id\"]}," +
                "\"right\":{\"op\":\"agg\",\"measure\":\"sum\",\"expr\":{\"op\":\"field\",\"source\":\"orders\",\"field\":\"amount\"}," +
                "\"filter\":[{\"source\":\"orders\",\"field\":\"currency\",\"operator\":\"eq\",\"value\":\"USD\"}],\"by\":[\"store_id\"]}}");
        ExprValidator v = new ExprValidator(Map.of(), Map.of());
        ExprValidator.Result res = new ExprValidator.Result();
        v.infer(expr, "$", res);
        assertFalse(res.ok(), "不同币种未换算相加应被拦截");
        assertTrue(res.errors.stream().anyMatch(e -> e.message().contains("币种")), "应包含币种错误");
    }

    @Test
    void testCurrencyConvertAllowsAdd() throws Exception {
        // CNY + convert(USD->CNY) → 应通过
        Expr expr = parse("{\"op\":\"arith\",\"operator\":\"add\"," +
                "\"left\":{\"op\":\"agg\",\"measure\":\"sum\",\"expr\":{\"op\":\"field\",\"source\":\"orders\",\"field\":\"amount\"}," +
                "\"filter\":[{\"source\":\"orders\",\"field\":\"currency\",\"operator\":\"eq\",\"value\":\"CNY\"}],\"by\":[\"store_id\"]}," +
                "\"right\":{\"op\":\"convert\",\"fromCurrency\":\"USD\",\"toCurrency\":\"CNY\",\"rate\":7.1," +
                "\"expr\":{\"op\":\"agg\",\"measure\":\"sum\",\"expr\":{\"op\":\"field\",\"source\":\"orders\",\"field\":\"amount\"}," +
                "\"filter\":[{\"source\":\"orders\",\"field\":\"currency\",\"operator\":\"eq\",\"value\":\"USD\"}],\"by\":[\"store_id\"]}}}");
        ExprValidator v = new ExprValidator(Map.of(), Map.of());
        ExprValidator.Result res = new ExprValidator.Result();
        MetricType t = v.infer(expr, "$", res);
        assertTrue(res.ok(), "显式换算后应允许相加: " + res.errors);
        assertEquals("CNY", t.currency());
    }

    @Test
    void testGrainMismatchRejected() throws Exception {
        // 日指标 × 月指标 → 应报错
        Map<String, MetricType> published = Map.of(
                "net_sales", amountType("CNY", MetricType.TimeGrain.DAY),
                "monthly_sales", amountType("CNY", MetricType.TimeGrain.MONTH)
        );
        Expr expr = parse("{\"op\":\"arith\",\"operator\":\"mul\"," +
                "\"left\":{\"op\":\"ref\",\"metric\":\"net_sales\"},\"right\":{\"op\":\"ref\",\"metric\":\"monthly_sales\"}}");
        ExprValidator v = new ExprValidator(published, Map.of());
        ExprValidator.Result res = new ExprValidator.Result();
        v.infer(expr, "$", res);
        assertFalse(res.ok(), "日/月粒度混乘应被拦截");
        assertTrue(res.errors.stream().anyMatch(e -> e.message().contains("粒度")), "应包含粒度错误");
    }

    @Test
    void testAmountTimesAmountRejected() throws Exception {
        // 金额 × 金额 → 量纲错误
        Map<String, MetricType> published = Map.of(
                "a", amountType("CNY", MetricType.TimeGrain.DAY),
                "b", amountType("CNY", MetricType.TimeGrain.DAY)
        );
        Expr expr = parse("{\"op\":\"arith\",\"operator\":\"mul\"," +
                "\"left\":{\"op\":\"ref\",\"metric\":\"a\"},\"right\":{\"op\":\"ref\",\"metric\":\"b\"}}");
        ExprValidator v = new ExprValidator(published, Map.of());
        ExprValidator.Result res = new ExprValidator.Result();
        v.infer(expr, "$", res);
        assertFalse(res.ok(), "金额乘金额应被拦截");
    }

    @Test
    void testCycleDetection() throws Exception {
        // a -> b -> a 环路
        Map<String, Set<String>> publishedRefs = Map.of("a", Set.of("b"));
        Expr expr = parse("{\"op\":\"arith\",\"operator\":\"add\"," +
                "\"left\":{\"op\":\"ref\",\"metric\":\"a\"},\"right\":{\"op\":\"const\",\"value\":1}}");
        ExprValidator v = new ExprValidator(Map.of(), publishedRefs);
        List<String> cycle = v.detectCycle("b", expr);
        assertFalse(cycle.isEmpty(), "应检测到引用环路");
    }

    @Test
    void testNoCycleForLinearRefs() throws Exception {
        // a -> b -> c 线性无环
        Map<String, Set<String>> publishedRefs = Map.of("a", Set.of("b"), "b", Set.of("c"));
        Expr expr = parse("{\"op\":\"ref\",\"metric\":\"a\"}");
        ExprValidator v = new ExprValidator(Map.of(), publishedRefs);
        List<String> cycle = v.detectCycle("d", expr);
        assertTrue(cycle.isEmpty(), "线性引用不应判环");
    }

    @Test
    void testUnknownFieldRejected() throws Exception {
        Expr expr = parse("{\"op\":\"agg\",\"measure\":\"sum\",\"expr\":{\"op\":\"field\",\"source\":\"orders\",\"field\":\"nonexistent\"},\"by\":[\"store_id\"]}");
        ExprValidator v = new ExprValidator(Map.of(), Map.of());
        ExprValidator.Result res = new ExprValidator.Result();
        v.infer(expr, "$", res);
        assertFalse(res.ok(), "未知字段应被拦截");
        assertTrue(res.errors.stream().anyMatch(e -> e.message().contains("不存在字段")), "应包含字段不存在错误");
    }

    @Test
    void testRawRefundAggregationRejected() throws Exception {
        // 原始退款事实没有时间口径，直接聚合应被拒绝
        Expr expr = parse("{\"op\":\"agg\",\"measure\":\"sum\",\"expr\":{\"op\":\"field\",\"source\":\"refunds\",\"field\":\"amount\"},\"by\":[\"store_id\"]}");
        ExprValidator v = new ExprValidator(Map.of(), Map.of(), MetricType.TimeGrain.DAY,
                "financial", Map.of(), Set.of("net_sales_financial"), Set.of());
        ExprValidator.Result res = new ExprValidator.Result();
        v.infer(expr, "$", res);
        assertFalse(res.ok(), "原始退款事实必须经时间口径归属");
        assertTrue(res.errors.stream().anyMatch(e -> e.message().contains("原始退款事实")), "应提示使用归属来源");
    }

    @Test
    void testAttributionSourceRequiresPurpose() throws Exception {
        // 归属来源在未绑定用途时拒绝编译（不能只引用无用途的模板名）
        Expr expr = parse("{\"op\":\"agg\",\"measure\":\"sum\",\"expr\":{\"op\":\"field\",\"source\":\"refund_attributions\",\"field\":\"amount\"},\"by\":[\"store_id\"]}");
        ExprValidator v = new ExprValidator(Map.of(), Map.of(), MetricType.TimeGrain.DAY,
                null, Map.of(), Set.of(), Set.of());
        ExprValidator.Result res = new ExprValidator.Result();
        v.infer(expr, "$", res);
        assertFalse(res.ok(), "未选择用途应拒绝编译");
    }

    @Test
    void testPurposeMismatchRejected() throws Exception {
        // 财务发生运行中引用 order_batch 用途 -> 两种口径不可比
        Map<String, MetricType> published = Map.of(
                "net_sales_order_batch", amountType("CNY", MetricType.TimeGrain.DAY));
        Expr expr = parse("{\"op\":\"ref\",\"metric\":\"net_sales_order_batch\",\"purpose\":\"order_batch\"}");
        ExprValidator v = new ExprValidator(published, Map.of(), MetricType.TimeGrain.DAY,
                "financial", Map.of(), Set.of("net_sales_order_batch"), Set.of());
        ExprValidator.Result res = new ExprValidator.Result();
        v.infer(expr, "$", res);
        assertFalse(res.ok(), "跨用途引用应被拒绝");
        assertTrue(res.errors.stream().anyMatch(e -> e.message().contains("混用不同退款用途")), "应提示用途不可混用");
    }

    @Test
    void testReconciliationAllowsBothPurposes() throws Exception {
        Map<String, MetricType> published = Map.of(
                "net_sales_order_batch", amountType("CNY", MetricType.TimeGrain.DAY));
        Expr expr = parse("{\"op\":\"ref\",\"metric\":\"net_sales_order_batch\",\"purpose\":\"order_batch\"}");
        ExprValidator v = new ExprValidator(published, Map.of(), MetricType.TimeGrain.DAY,
                "reconciliation", Map.of(), Set.of("net_sales_order_batch"), Set.of());
        ExprValidator.Result res = new ExprValidator.Result();
        v.infer(expr, "$", res);
        assertTrue(res.ok(), "对账运行允许引用两种用途做独立复算: " + res.errors);
    }

    @Test
    void testRatioTypeInference() throws Exception {
        // 金额 / 数量 = 单价（金额）
        Map<String, MetricType> published = Map.of(
                "net_sales", amountType("CNY", MetricType.TimeGrain.DAY),
                "order_count", MetricType.quantity(MetricType.TimeGrain.DAY, Set.of("store_id"))
        );
        Expr expr = parse("{\"op\":\"arith\",\"operator\":\"div\"," +
                "\"left\":{\"op\":\"ref\",\"metric\":\"net_sales\"},\"right\":{\"op\":\"ref\",\"metric\":\"order_count\"}}");
        ExprValidator v = new ExprValidator(published, Map.of());
        ExprValidator.Result res = new ExprValidator.Result();
        MetricType t = v.infer(expr, "$", res);
        assertTrue(res.ok(), "金额/数量应通过: " + res.errors);
        assertEquals(MetricType.ValueKind.AMOUNT, t.kind(), "金额/数量=单价（金额）");
    }
}
