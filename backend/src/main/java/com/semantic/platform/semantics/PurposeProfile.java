package com.semantic.platform.semantics;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * 用途档案：把“财务发生（financial）/ 运营订单（order_batch）/ 对账（reconciliation）”
 * 绑定到某一退款口径版本。用途查询必须显式选择，不存在“默认最近使用分支”。
 */
@Entity
@Table(name = "purpose_profile")
public class PurposeProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String purpose;

    @Column(nullable = false)
    private String label;

    @Column(name = "applicable_users", nullable = false)
    private String applicableUsers;

    @Column(name = "semantics_id", nullable = false)
    private Long semanticsId;

    @Column(name = "profile_version", nullable = false)
    private Integer profileVersion = 1;

    @Column(name = "updated_by")
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getApplicableUsers() { return applicableUsers; }
    public void setApplicableUsers(String applicableUsers) { this.applicableUsers = applicableUsers; }
    public Long getSemanticsId() { return semanticsId; }
    public void setSemanticsId(Long semanticsId) { this.semanticsId = semanticsId; }
    public Integer getProfileVersion() { return profileVersion; }
    public void setProfileVersion(Integer profileVersion) { this.profileVersion = profileVersion; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
