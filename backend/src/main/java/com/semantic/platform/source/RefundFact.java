package com.semantic.platform.source;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "refund_fact")
public class RefundFact {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "refund_id", nullable = false, unique = true)
    private String refundId;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(name = "refunded_at", nullable = false)
    private OffsetDateTime refundedAt;

    @Column(name = "business_date")
    private LocalDate businessDate;

    @Column(nullable = false)
    private BigDecimal amount;

    private String reason;

    @Column(name = "created_batch")
    private Long createdBatch;

    /** 原订单行：可空；为空表示无法可靠关联（不得按同名商品猜填）。 */
    @Column(name = "order_line_id")
    private String orderLineId;

    /** 原交易日（订单行/订单的营业日），用于“运营订单”口径追溯。 */
    @Column(name = "original_business_date")
    private LocalDate originalBusinessDate;

    /** 退款数量（可选，行级退款时填写）。 */
    private Integer quantity;

    /** 金额方向：+1 退款扣减，-1 退款撤销。 */
    @Column(nullable = false)
    private Integer direction = 1;

    @Column(name = "sku")
    private String sku;

    /** linked | unlinked；unlinked 的退款只能进入待归属，不能按同名商品或当前价格回填。 */
    @Column(name = "link_status", nullable = false)
    private String linkStatus = "unlinked";

    @Column(name = "link_note")
    private String linkNote;

    /** 来源修订：退款事实每次更正递增，用于生成新归属修订。 */
    @Column(name = "source_revision", nullable = false)
    private Integer sourceRevision = 1;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRefundId() { return refundId; }
    public void setRefundId(String refundId) { this.refundId = refundId; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getStoreId() { return storeId; }
    public void setStoreId(String storeId) { this.storeId = storeId; }
    public OffsetDateTime getRefundedAt() { return refundedAt; }
    public void setRefundedAt(OffsetDateTime refundedAt) { this.refundedAt = refundedAt; }
    public LocalDate getBusinessDate() { return businessDate; }
    public void setBusinessDate(LocalDate businessDate) { this.businessDate = businessDate; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Long getCreatedBatch() { return createdBatch; }
    public void setCreatedBatch(Long createdBatch) { this.createdBatch = createdBatch; }

    public String getOrderLineId() { return orderLineId; }
    public void setOrderLineId(String orderLineId) { this.orderLineId = orderLineId; }
    public LocalDate getOriginalBusinessDate() { return originalBusinessDate; }
    public void setOriginalBusinessDate(LocalDate originalBusinessDate) { this.originalBusinessDate = originalBusinessDate; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public Integer getDirection() { return direction == null ? 1 : direction; }
    public void setDirection(Integer direction) { this.direction = direction; }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public String getLinkStatus() { return linkStatus; }
    public void setLinkStatus(String linkStatus) { this.linkStatus = linkStatus; }
    public String getLinkNote() { return linkNote; }
    public void setLinkNote(String linkNote) { this.linkNote = linkNote; }
    public Integer getSourceRevision() { return sourceRevision; }
    public void setSourceRevision(Integer sourceRevision) { this.sourceRevision = sourceRevision; }
}
