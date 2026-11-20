package com.semantic.platform.run;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "run")
public class Run {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "metric_key", nullable = false)
    private String metricKey;

    @Column(name = "metric_version_id", nullable = false)
    private Long metricVersionId;

    @Column(nullable = false)
    private String grain = "day";

    @Column(name = "period_from")
    private LocalDate periodFrom;

    @Column(name = "period_to")
    private LocalDate periodTo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scope_json", columnDefinition = "jsonb")
    private String scopeJson;

    @Column(nullable = false)
    private String status = "success";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data_cutoff_json", columnDefinition = "jsonb")
    private String dataCutoffJson;

    @Column(name = "result_kind", nullable = false)
    private String resultKind = "metric";

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt = OffsetDateTime.now();

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    private String error;

    // ---------- 退款时间口径：用途与固定引用身份 ----------
    @Column(name = "purpose")
    private String purpose;

    @Column(name = "profile_version")
    private Integer profileVersion;

    @Column(name = "semantics_id")
    private Long semanticsId;

    @Column(name = "semantics_code")
    private String semanticsCode;

    @Column(name = "semantics_version")
    private Integer semanticsVersion;

    @Column(name = "attribution_revision")
    private Integer attributionRevision;

    @Column(name = "purpose_label")
    private String purposeLabel;

    @Column(name = "applicable_users")
    private String applicableUsers;

    @Column(name = "reference_identity")
    private String referenceIdentity;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pending_json", columnDefinition = "jsonb")
    private String pendingJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_digest_json", columnDefinition = "jsonb")
    private String inputDigestJson;

    /** 运行输入快照：订单/订单行/归属行在运行时定格，旧报表按此复演，不随新批次改变。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_snapshot_json", columnDefinition = "jsonb")
    private String inputSnapshotJson;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getMetricKey() { return metricKey; }
    public void setMetricKey(String metricKey) { this.metricKey = metricKey; }
    public Long getMetricVersionId() { return metricVersionId; }
    public void setMetricVersionId(Long metricVersionId) { this.metricVersionId = metricVersionId; }
    public String getGrain() { return grain; }
    public void setGrain(String grain) { this.grain = grain; }
    public LocalDate getPeriodFrom() { return periodFrom; }
    public void setPeriodFrom(LocalDate periodFrom) { this.periodFrom = periodFrom; }
    public LocalDate getPeriodTo() { return periodTo; }
    public void setPeriodTo(LocalDate periodTo) { this.periodTo = periodTo; }
    public String getScopeJson() { return scopeJson; }
    public void setScopeJson(String scopeJson) { this.scopeJson = scopeJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDataCutoffJson() { return dataCutoffJson; }
    public void setDataCutoffJson(String dataCutoffJson) { this.dataCutoffJson = dataCutoffJson; }
    public String getResultKind() { return resultKind; }
    public void setResultKind(String resultKind) { this.resultKind = resultKind; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(OffsetDateTime finishedAt) { this.finishedAt = finishedAt; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public Integer getProfileVersion() { return profileVersion; }
    public void setProfileVersion(Integer profileVersion) { this.profileVersion = profileVersion; }
    public Long getSemanticsId() { return semanticsId; }
    public void setSemanticsId(Long semanticsId) { this.semanticsId = semanticsId; }
    public String getSemanticsCode() { return semanticsCode; }
    public void setSemanticsCode(String semanticsCode) { this.semanticsCode = semanticsCode; }
    public Integer getSemanticsVersion() { return semanticsVersion; }
    public void setSemanticsVersion(Integer semanticsVersion) { this.semanticsVersion = semanticsVersion; }
    public Integer getAttributionRevision() { return attributionRevision; }
    public void setAttributionRevision(Integer attributionRevision) { this.attributionRevision = attributionRevision; }
    public String getPurposeLabel() { return purposeLabel; }
    public void setPurposeLabel(String purposeLabel) { this.purposeLabel = purposeLabel; }
    public String getApplicableUsers() { return applicableUsers; }
    public void setApplicableUsers(String applicableUsers) { this.applicableUsers = applicableUsers; }
    public String getReferenceIdentity() { return referenceIdentity; }
    public void setReferenceIdentity(String referenceIdentity) { this.referenceIdentity = referenceIdentity; }
    public String getPendingJson() { return pendingJson; }
    public void setPendingJson(String pendingJson) { this.pendingJson = pendingJson; }
    public String getInputDigestJson() { return inputDigestJson; }
    public void setInputDigestJson(String inputDigestJson) { this.inputDigestJson = inputDigestJson; }
    public String getInputSnapshotJson() { return inputSnapshotJson; }
    public void setInputSnapshotJson(String inputSnapshotJson) { this.inputSnapshotJson = inputSnapshotJson; }
}
