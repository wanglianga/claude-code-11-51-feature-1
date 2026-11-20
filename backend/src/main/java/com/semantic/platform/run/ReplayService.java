package com.semantic.platform.run;

import com.semantic.platform.source.OrderLine;
import com.semantic.platform.source.OrderLineRepository;
import com.semantic.platform.source.RefundFact;
import com.semantic.platform.source.RefundFactRepository;
import com.semantic.platform.source.SourceBatch;
import com.semantic.platform.source.SourceBatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 回放服务：迟到退款场景。
 *
 * 退款事实必须带原订单行与原交易日；缺原订单行时列为待归属，不按同名商品/当前价格猜填。
 * 退款晚到后追加归属修订，影响计算只标出受影响的用途与期间并生成新运行候选，
 * 已发布报表继续引用原运行。
 */
@Service
public class ReplayService {

    private final RefundFactRepository refundRepo;
    private final SourceBatchRepository batchRepo;
    private final OrderLineRepository orderLineRepo;
    private final ImpactService impactService;

    public ReplayService(RefundFactRepository refundRepo, SourceBatchRepository batchRepo,
                         OrderLineRepository orderLineRepo, ImpactService impactService) {
        this.refundRepo = refundRepo;
        this.batchRepo = batchRepo;
        this.orderLineRepo = orderLineRepo;
        this.impactService = impactService;
    }

    public Map<String, Object> lateRefund(String refundId, String orderId, String storeId,
                                          LocalDate businessDate, BigDecimal amount) {
        return lateRefund(refundId, orderId, null, storeId, businessDate, null, 1,
                amount, null, null, "迟到退款");
    }

    /**
     * 迟到退款到达：登记退款记录（含原订单行/原交易日/方向/数量/来源修订）+ 批次，触发影响计算。
     * postingDate 为实际入账日；originalBusinessDate 可显式给出，否则取原订单行营业日。
     */
    @Transactional
    public Map<String, Object> lateRefund(String refundId, String orderId, String orderLineId, String storeId,
                                          LocalDate postingDate, LocalDate originalBusinessDate, int direction,
                                          BigDecimal amount, Integer quantity, String sku, String reason) {
        RefundFact existing = refundRepo.findAll().stream()
                .filter(r -> r.getRefundId().equals(refundId)).findFirst().orElse(null);
        RefundFact refund;
        if (existing == null) {
            refund = new RefundFact();
            refund.setRefundId(refundId);
            refund.setOrderId(orderId);
            refund.setStoreId(storeId);
            refund.setRefundedAt(OffsetDateTime.now());
            refund.setBusinessDate(postingDate);
            refund.setAmount(amount);
            refund.setDirection(direction);
            refund.setQuantity(quantity);
            refund.setSku(sku);
            refund.setReason(reason);
            if (orderLineId != null && !orderLineId.isBlank()) {
                OrderLine line = orderLineRepo.findAll().stream()
                        .filter(l -> l.getLineId().equals(orderLineId)).findFirst().orElse(null);
                if (line != null) {
                    refund.setOrderLineId(line.getLineId());
                    refund.setLinkStatus("linked");
                    refund.setOriginalBusinessDate(originalBusinessDate != null
                            ? originalBusinessDate : line.getBusinessDate());
                } else {
                    refund.setOrderLineId(null);
                    refund.setLinkStatus("unlinked");
                    refund.setLinkNote("原订单行 '" + orderLineId + "' 不存在，列为待归属");
                    refund.setOriginalBusinessDate(originalBusinessDate);
                }
            } else {
                refund.setLinkStatus("unlinked");
                refund.setLinkNote("迟到退款未携带原订单行，列为待归属（不按同名商品或当前价格猜填）");
                refund.setOriginalBusinessDate(originalBusinessDate);
            }
            refund = refundRepo.save(refund);
        } else {
            refund = existing;
        }
        // 登记批次（迟到数据）
        SourceBatch batch = new SourceBatch();
        batch.setSourceType("refunds");
        batch.setStoreId(storeId);
        batch.setStatus("loaded");
        batch.setPeriodStart(postingDate);
        batch.setPeriodEnd(postingDate);
        batch.setRecordCount(1);
        SourceBatch savedBatch = batchRepo.save(batch);
        // 影响计算：追加归属修订 + 按用途/期间生成新运行候选
        Map<String, Object> impact = impactService.onBatchImported(savedBatch);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("refundId", refundId);
        out.put("amount", amount);
        out.put("businessDate", postingDate);
        out.put("originalBusinessDate", refund.getOriginalBusinessDate());
        out.put("linkStatus", refund.getLinkStatus());
        out.put("impact", impact);
        return out;
    }

    public BigDecimal refundTotalFor(String orderId) {
        return refundRepo.findAll().stream()
                .filter(r -> r.getOrderId().equals(orderId))
                .map(r -> r.getAmount().multiply(BigDecimal.valueOf(r.getDirection() == null ? 1 : r.getDirection())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
