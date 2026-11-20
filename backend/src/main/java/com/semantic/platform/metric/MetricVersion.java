package com.semantic.platform.metric;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;

@Entity
@Table(name = "metric_version")
public class MetricVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "metric_key", nullable = false)
    private String metricKey;

    @Column(nullable = false)
    private Integer version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "definition_json", nullable = false, columnDefinition = "jsonb")
    private String definitionJson;

    @Column(nullable = false)
    private String grain = "day";

    @Column(nullable = false)
    private String status = "draft";

    @Column(name = "definition_hash")
    private String definitionHash;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    /** 是否“用途参数模板”：定义中引用退款时间口径，运行时必须固定 financial / order_batch。 */
    @Column(name = "purpose_required", nullable = false)
    private Boolean purposeRequired = false;

    /** 已绑定用途（单用途定义）：模板类指标为空，要求下游显式传参。 */
    @Column(name = "bound_purpose")
    private String boundPurpose;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getMetricKey() { return metricKey; }
    public void setMetricKey(String metricKey) { this.metricKey = metricKey; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getDefinitionJson() { return definitionJson; }
    public void setDefinitionJson(String definitionJson) { this.definitionJson = definitionJson; }
    public String getGrain() { return grain; }
    public void setGrain(String grain) { this.grain = grain; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDefinitionHash() { return definitionHash; }
    public void setDefinitionHash(String definitionHash) { this.definitionHash = definitionHash; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(OffsetDateTime publishedAt) { this.publishedAt = publishedAt; }
    public Boolean getPurposeRequired() { return purposeRequired == null ? Boolean.FALSE : purposeRequired; }
    public void setPurposeRequired(Boolean purposeRequired) { this.purposeRequired = purposeRequired; }
    public String getBoundPurpose() { return boundPurpose; }
    public void setBoundPurpose(String boundPurpose) { this.boundPurpose = boundPurpose; }
}
