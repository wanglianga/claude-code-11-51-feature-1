package com.semantic.platform.api;

import com.semantic.platform.run.ImpactService;
import com.semantic.platform.source.OrderFact;
import com.semantic.platform.source.OrderFactRepository;
import com.semantic.platform.source.OrderLine;
import com.semantic.platform.source.OrderLineRepository;
import com.semantic.platform.source.RefundFact;
import com.semantic.platform.source.RefundFactRepository;
import com.semantic.platform.source.SourceBatchRepository;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 源数据维护 API：退款事实更正/关联、原订单行更正（迟到更正）、外键（退款撤销）等。
 * 每次变更都会追加新的归属修订并触发影响计算，只标出受影响的用途与期间。
 */
@RestController
@RequestMapping("/api/sources")
public class SourceCorrectionController {

    private final RefundFactRepository refundRepo;
    private final OrderFactRepository orderRepo;
    private final OrderLineRepository orderLineRepo;
    private final SourceBatchRepository batchRepo;
    private final ImpactService impactService;

    public SourceCorrectionController(RefundFactRepository refundRepo, OrderFactRepository orderRepo,
                                      OrderLineRepository orderLineRepo, SourceBatchRepository batchRepo,
                                      ImpactService impactService) {
        this.refundRepo = refundRepo;
        this.orderRepo = orderRepo;
        this.orderLineRepo = orderLineRepo;
        this.batchRepo = batchRepo;
        this.impactService = impactService;
    }

    /**
     * 退款更正/重新关联：可修改原订单行、入账日、原交易日、方向、数量、金额、SKU。
     * 更正后生成新归属修订；旧报表继续按原输入复演。
     */
    @PostMapping("/refunds/{refundId}/correct")
    public Map<String, Object> correctRefund(@PathVariable String refundId,
                                             @RequestBody Map<String, Object> body) {
        RefundFact refund = refundRepo.findAll().stream()
                .filter(r -> r.getRefundId().equals(refundId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("退款不存在: " + refundId));
        if (body.get("orderLineId") != null) {
            String lineId = body.get("orderLineId").toString();
            if (lineId.isBlank() || "null".equals(lineId)) {
                refund.setOrderLineId(null);
                refund.setLinkStatus("unlinked");
                refund.setLinkNote("手工置为无法关联原订单行");
            } else {
                OrderLine line = orderLineRepo.findAll().stream()
                        .filter(l -> l.getLineId().equals(lineId)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "原订单行不存在: " + lineId + "（不得按同名商品或当前价格猜测回填）"));
                refund.setOrderLineId(lineId);
                refund.setOriginalBusinessDate(line.getBusinessDate());
                refund.setLinkStatus("linked");
                refund.setLinkNote(null);
            }
        }
        if (body.get("postingDate") != null) refund.setBusinessDate(LocalDate.parse(body.get("postingDate").toString()));
        if (body.get("originalBusinessDate") != null) {
            refund.setOriginalBusinessDate(LocalDate.parse(body.get("originalBusinessDate").toString()));
        }
        if (body.get("amount") != null) refund.setAmount(new BigDecimal(body.get("amount").toString()));
        if (body.get("direction") != null) refund.setDirection(Integer.valueOf(body.get("direction").toString()));
        if (body.get("quantity") != null) refund.setQuantity(Integer.valueOf(body.get("quantity").toString()));
        if (body.get("sku") != null) refund.setSku(body.get("sku").toString());
        if (body.get("reason") != null) refund.setReason(body.get("reason").toString());
        refund.setSourceRevision(refund.getSourceRevision() + 1);
        refundRepo.save(refund);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("refund", refund);
        out.put("impact", impactService.onBatchImported(refundBatch(refund)));
        return out;
    }

    /** 原订单行更正：只标出受影响的用途与期间，生成新归属修订。 */
    @PostMapping("/order-lines/{lineId}/correct")
    public Map<String, Object> correctOrderLine(@PathVariable String lineId, @RequestBody Map<String, Object> body) {
        OrderLine line = orderLineRepo.findAll().stream()
                .filter(l -> l.getLineId().equals(lineId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("订单行不存在: " + lineId));
        if (body.get("businessDate") != null) line.setBusinessDate(LocalDate.parse(body.get("businessDate").toString()));
        if (body.get("quantity") != null) line.setQuantity(Integer.valueOf(body.get("quantity").toString()));
        if (body.get("unitPrice") != null) {
            line.setUnitPrice(new BigDecimal(body.get("unitPrice").toString()));
            line.setAmount(line.getUnitPrice().multiply(BigDecimal.valueOf(line.getQuantity())));
        }
        if (body.get("amount") != null) line.setAmount(new BigDecimal(body.get("amount").toString()));
        line.setSourceRevision(line.getSourceRevision() + 1);
        orderLineRepo.save(line);
        // 同步订单原交易日（订单行更正可能改变原交易日）
        OrderFact order = orderRepo.findAll().stream()
                .filter(o -> o.getOrderId().equals(line.getOrderId())).findFirst().orElse(null);
        if (order != null && body.get("businessDate") != null) {
            order.setBusinessDate(line.getBusinessDate());
            order.setSourceRevision(order.getSourceRevision() + 1);
            orderRepo.save(order);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderLine", line);
        out.put("impact", impactService.onOrderLineCorrected(lineId,
                String.valueOf(body.getOrDefault("reason", "原订单行更正"))));
        return out;
    }

    private com.semantic.platform.source.SourceBatch refundBatch(RefundFact refund) {
        var batch = new com.semantic.platform.source.SourceBatch();
        batch.setSourceType("refunds");
        batch.setStoreId(refund.getStoreId());
        batch.setStatus("loaded");
        batch.setPeriodStart(refund.getBusinessDate());
        batch.setPeriodEnd(refund.getBusinessDate());
        batch.setRecordCount(1);
        return batchRepo.save(batch);
    }
}
