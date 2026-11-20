package com.semantic.platform.semantics;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 退款归属明细账：一行 = 某退款在“某口径版本 + 归属修订 + 用途”下的归属结果。
 * - 财务发生：attribution_date = 实际入账日（posting_date），退款计入发生期间；
 * - 运营订单：attribution_date = 原交易日（original_business_date），只修订对应订单批次；
 * - 无法可靠关联原订单行的退款：attribution_date = NULL、status = pending，进入待归属，不猜填。
 * amount_signed = amount * direction（+1 扣减 / -1 撤销）。
 */
@Entity
@Table(name = "refund_attribution")
public class RefundAttribution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "semantics_id", nullable = false)
    private Long semanticsId;

    @Column(nullable = false)
    private Integer revision;

    @Column(name = "refund_id", nullable = false)
    private String refundId;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Column(name = "order_line_id")
    private String orderLineId;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(nullable = false)
    private String purpose;

    @Column(name = "attribution_date")
    private LocalDate attributionDate;

    @Column(name = "posting_date", nullable = false)
    private LocalDate postingDate;

    @Column(name = "original_business_date")
    private LocalDate originalBusinessDate;

    @Column(name = "amount_signed", nullable = false)
    private BigDecimal amountSigned;

    private Integer quantity;

    @Column(nullable = false)
    private Integer direction = 1;

    private String sku;

    @Column(name = "link_status", nullable = false)
    private String linkStatus;

    @Column(name = "attribution_status", nullable = false)
    private String attributionStatus;

    private String reason;

    @Column(name = "refund_source_revision", nullable = false)
    private Integer refundSourceRevision = 1;

    @Column(name = "line_source_revision")
    private Integer lineSourceRevision;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSemanticsId() { return semanticsId; }
    public void setSemanticsId(Long semanticsId) { this.semanticsId = semanticsId; }
    public Integer getRevision() { return revision; }
    public void setRevision(Integer revision) { this.revision = revision; }
    public String getRefundId() { return refundId; }
    public void setRefundId(String refundId) { this.refundId = refundId; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getOrderLineId() { return orderLineId; }
    public void setOrderLineId(String orderLineId) { this.orderLineId = orderLineId; }
    public String getStoreId() { return storeId; }
    public void setStoreId(String storeId) { this.storeId = storeId; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public LocalDate getAttributionDate() { return attributionDate; }
    public void setAttributionDate(LocalDate attributionDate) { this.attributionDate = attributionDate; }
    public LocalDate getPostingDate() { return postingDate; }
    public void setPostingDate(LocalDate postingDate) { this.postingDate = postingDate; }
    public LocalDate getOriginalBusinessDate() { return originalBusinessDate; }
    public void setOriginalBusinessDate(LocalDate originalBusinessDate) { this.originalBusinessDate = originalBusinessDate; }
    public BigDecimal getAmountSigned() { return amountSigned; }
    public void setAmountSigned(BigDecimal amountSigned) { this.amountSigned = amountSigned; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public Integer getDirection() { return direction; }
    public void setDirection(Integer direction) { this.direction = direction; }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public String getLinkStatus() { return linkStatus; }
    public void setLinkStatus(String linkStatus) { this.linkStatus = linkStatus; }
    public String getAttributionStatus() { return attributionStatus; }
    public void setAttributionStatus(String attributionStatus) { this.attributionStatus = attributionStatus; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Integer getRefundSourceRevision() { return refundSourceRevision; }
    public void setRefundSourceRevision(Integer refundSourceRevision) { this.refundSourceRevision = refundSourceRevision; }
    public Integer getLineSourceRevision() { return lineSourceRevision; }
    public void setLineSourceRevision(Integer lineSourceRevision) { this.lineSourceRevision = lineSourceRevision; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
