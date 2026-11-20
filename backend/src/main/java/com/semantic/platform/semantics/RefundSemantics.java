package com.semantic.platform.semantics;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;

import java.time.OffsetDateTime;

/**
 * 退款时间口径（版本化）。同一口径版本同时被：定义审批、退款归属处理、语义编译、
 * 下游指标依赖与报表发布引用；任何一处引用身份都能追溯到这个版本。
 */
@Entity
@Table(name = "refund_semantics")
public class RefundSemantics {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String code = "REFUND_TIME";

    @Column(nullable = false)
    private Integer version;

    @Column(nullable = false)
    private String status = "draft"; // draft | published | deprecated

    @JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "rules_json", nullable = false, columnDefinition = "jsonb")
    private String rulesJson;

    private String note;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRulesJson() { return rulesJson; }
    public void setRulesJson(String rulesJson) { this.rulesJson = rulesJson; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(OffsetDateTime publishedAt) { this.publishedAt = publishedAt; }

    /** 引用身份：口径编码 + 版本，如 REFUND_TIME@v2。 */
    public String refIdentity() {
        return code + "@v" + version;
    }
}
