package com.semantic.platform.report;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "report_item")
public class ReportItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_id", nullable = false)
    private Long reportId;

    @Column(name = "metric_key", nullable = false)
    private String metricKey;

    @Column(name = "metric_version_id", nullable = false)
    private Long metricVersionId;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "display_json", columnDefinition = "jsonb")
    private String displayJson;

    /** 用途绑定：报表项必须固定用途，同一报表不能把不同用途当成可比序列。 */
    @Column(name = "purpose")
    private String purpose;

    @Column(name = "semantics_id")
    private Long semanticsId;

    @Column(name = "purpose_label")
    private String purposeLabel;

    @Column(name = "applicable_users")
    private String applicableUsers;

    @Column(name = "reference_identity")
    private String referenceIdentity;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getReportId() { return reportId; }
    public void setReportId(Long reportId) { this.reportId = reportId; }
    public String getMetricKey() { return metricKey; }
    public void setMetricKey(String metricKey) { this.metricKey = metricKey; }
    public Long getMetricVersionId() { return metricVersionId; }
    public void setMetricVersionId(Long metricVersionId) { this.metricVersionId = metricVersionId; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public String getDisplayJson() { return displayJson; }
    public void setDisplayJson(String displayJson) { this.displayJson = displayJson; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public Long getSemanticsId() { return semanticsId; }
    public void setSemanticsId(Long semanticsId) { this.semanticsId = semanticsId; }
    public String getPurposeLabel() { return purposeLabel; }
    public void setPurposeLabel(String purposeLabel) { this.purposeLabel = purposeLabel; }
    public String getApplicableUsers() { return applicableUsers; }
    public void setApplicableUsers(String applicableUsers) { this.applicableUsers = applicableUsers; }
    public String getReferenceIdentity() { return referenceIdentity; }
    public void setReferenceIdentity(String referenceIdentity) { this.referenceIdentity = referenceIdentity; }
}
