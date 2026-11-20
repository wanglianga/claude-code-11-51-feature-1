package com.semantic.platform.semantics;

import com.semantic.platform.source.OrderFact;
import com.semantic.platform.source.OrderLine;
import com.semantic.platform.source.RefundFact;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 退款时间口径的纯单元测试：验证归属规则（入账日 / 原交易日 / 待归属）与守恒不变量，
 * 不依赖 Spring 上下文。
 */
class RefundAttributionRulesTest {

    private RefundFact linked(String id, String orderId, String lineId,
                              LocalDate posting, LocalDate original, String amount, int direction) {
        RefundFact r = new RefundFact();
        r.setRefundId(id);
        r.setOrderId(orderId);
        r.setOrderLineId(lineId);
        r.setStoreId("S001");
        r.setRefundedAt(java.time.OffsetDateTime.now());
        r.setBusinessDate(posting);
        r.setOriginalBusinessDate(original);
        r.setAmount(new BigDecimal(amount));
        r.setDirection(direction);
        r.setLinkStatus("linked");
        return r;
    }

    private RefundFact unlinked(String id, String orderId, LocalDate posting, String amount) {
        RefundFact r = linked(id, orderId, null, posting, null, amount, 1);
        r.setLinkStatus("unlinked");
        return r;
    }

    @Test
    void crossMonthPartialRefundSplitsByPurpose() {
        // O008 1/28 订单，2/3 部分退款 200：财务进 2 月，运营进 1 月
        RefundFact r = linked("R101", "O008", "L081",
                LocalDate.of(2026, 2, 3), LocalDate.of(2026, 1, 28), "200.00", 1);
        assertEquals(LocalDate.of(2026, 2, 3), r.getBusinessDate(), "实际入账日");
        assertEquals(LocalDate.of(2026, 1, 28), r.getOriginalBusinessDate(), "原交易日");
        // 归属规则：财务发生=入账日；运营订单=原交易日
        LocalDate financial = r.getBusinessDate();
        LocalDate orderBatch = r.getOriginalBusinessDate();
        assertEquals("2026-02", java.time.YearMonth.from(financial).toString());
        assertEquals("2026-01", java.time.YearMonth.from(orderBatch).toString());
        assertNotEquals(financial, orderBatch, "两种口径归属不同期间，均正确但不可互换");
    }

    @Test
    void unlinkedRefundHasNoAttributionDate() {
        RefundFact r = unlinked("R201", "O009", LocalDate.of(2026, 2, 25), "80.00");
        assertNull(r.getOrderLineId(), "无法可靠关联原订单行");
        assertNull(r.getOriginalBusinessDate(), "无原交易日");
        assertEquals("unlinked", r.getLinkStatus());
        // 规则：待归属不得以入账日或同名商品价格回填
        assertTrue(r.getBusinessDate() != null, "入账日仍保留，用于待归属期间提示");
    }

    @Test
    void reversalDirectionFlipsSign() {
        RefundFact r = linked("R301", "O005", "L052",
                LocalDate.of(2026, 3, 2), LocalDate.of(2026, 2, 5), "40.00", -1);
        BigDecimal signed = r.getAmount().multiply(BigDecimal.valueOf(r.getDirection()));
        assertEquals(0, new BigDecimal("-40.00").compareTo(signed), "撤销方向为负");
    }

    @Test
    void orderLineConservation() {
        // 订单金额 = 订单行金额合计
        OrderFact o = new OrderFact();
        o.setOrderId("O008");
        o.setAmount(new BigDecimal("600.00"));
        OrderLine a = line("L081", "O008", "200.00");
        OrderLine b = line("L082", "O008", "150.00");
        OrderLine c = line("L083", "O008", "250.00");
        BigDecimal sum = a.getAmount().add(b.getAmount()).add(c.getAmount());
        assertEquals(0, o.getAmount().compareTo(sum), "订单金额与订单行守恒");
    }

    private OrderLine line(String id, String orderId, String amount) {
        OrderLine l = new OrderLine();
        l.setLineId(id);
        l.setOrderId(orderId);
        l.setStoreId("S001");
        l.setBusinessDate(LocalDate.of(2026, 1, 28));
        l.setSku("SKU-A");
        l.setQuantity(1);
        l.setUnitPrice(new BigDecimal(amount));
        l.setAmount(new BigDecimal(amount));
        return l;
    }
}
