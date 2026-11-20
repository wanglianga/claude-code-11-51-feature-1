package com.semantic.platform.metric;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * 指标发布清单：发布时固定用途、口径版本与归属修订。
 * 下游指标依赖与报表发布必须引用同一口径版本，否则拒绝。
 */
@Entity
@Table(name = "metric_release")
public class MetricRelease {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "metric_key", nullable = false)
    private String metricKey;

    @Column(nullable = false)
    private Integer version;

    @Column(nullable = false)
    private String purpose;

    @Column(name = "semantics_id")
    private Long semanticsId;

    @Column(name = "semantics_code")
    private String semanticsCode;

    @Column(name = "semantics_version")
    private Integer semanticsVersion;

    @Column(name = "profile_version")
    private Integer profileVersion;

    @Column(name = "reference_identity")
    private String referenceIdentity;

    @Column(name = "released_at", nullable = false)
    private OffsetDateTime releasedAt = OffsetDateTime.now();

    @Column(name = "released_by")
    private String releasedBy;

    private String note;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getMetricKey() { return metricKey; }
    public void setMetricKey(String metricKey) { this.metricKey = metricKey; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public Long getSemanticsId() { return semanticsId; }
    public void setSemanticsId(Long semanticsId) { this.semanticsId = semanticsId; }
    public String getSemanticsCode() { return semanticsCode; }
    public void setSemanticsCode(String semanticsCode) { this.semanticsCode = semanticsCode; }
    public Integer getSemanticsVersion() { return semanticsVersion; }
    public void setSemanticsVersion(Integer semanticsVersion) { this.semanticsVersion = semanticsVersion; }
    public Integer getProfileVersion() { return profileVersion; }
    public void setProfileVersion(Integer profileVersion) { this.profileVersion = profileVersion; }
    public String getReferenceIdentity() { return referenceIdentity; }
    public void setReferenceIdentity(String referenceIdentity) { this.referenceIdentity = referenceIdentity; }
    public OffsetDateTime getReleasedAt() { return releasedAt; }
    public void setReleasedAt(OffsetDateTime releasedAt) { this.releasedAt = releasedAt; }
    public String getReleasedBy() { return releasedBy; }
    public void setReleasedBy(String releasedBy) { this.releasedBy = releasedBy; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
