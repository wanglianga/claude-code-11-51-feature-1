package com.semantic.platform.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantic.platform.metric.MetricService;
import com.semantic.platform.metric.MetricVersion;
import com.semantic.platform.metric.MetricVersionRepository;
import com.semantic.platform.report.ReportItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审批服务：提交审批（含影响预览）、批准（发布，组合重检）、拒绝。
 * 处理并发修改：同一指标多个待审批草稿时提示冲突。
 */
@Service
public class ApprovalService {

    private final ApprovalRepository approvalRepo;
    private final MetricVersionRepository versionRepo;
    private final MetricService metricService;
    private final ReportItemRepository reportItemRepo;
    private final com.semantic.platform.semantics.RefundAttributionService attributionService;
    private final ObjectMapper mapper = new ObjectMapper();

    public ApprovalService(ApprovalRepository approvalRepo, MetricVersionRepository versionRepo,
                           MetricService metricService, ReportItemRepository reportItemRepo,
                           com.semantic.platform.semantics.RefundAttributionService attributionService) {
        this.approvalRepo = approvalRepo;
        this.versionRepo = versionRepo;
        this.metricService = metricService;
        this.reportItemRepo = reportItemRepo;
        this.attributionService = attributionService;
    }

    /** 提交审批：计算影响（依赖指标、受影响报表、层级差异、并发冲突）。 */
    @Transactional
    public Approval submit(String metricKey, Integer toVersion, String changeType, String submittedBy) {
        return submit(metricKey, toVersion, changeType, submittedBy, null);
    }

    /**
     * 提交审批：固定用途与口径版本。审批与发布清单、下游依赖、报表发布引用同一口径版本；
     * 并发修改用途时，若待审批的用途与当前用途档案不一致则提示冲突。
     */
    @Transactional
    public Approval submit(String metricKey, Integer toVersion, String changeType, String submittedBy,
                           String purpose) {
        MetricVersion target = versionRepo.findByMetricKeyAndVersion(metricKey, toVersion)
                .orElseThrow(() -> new IllegalArgumentException("版本不存在"));
        // 指标若绑定退款口径，审批必须固定用途（不允许无用途模板直接发布）
        String effectivePurpose = purpose != null ? purpose : target.getBoundPurpose();
        MetricVersion current = versionRepo
                .findFirstByMetricKeyAndStatusOrderByVersionDesc(metricKey, "published").orElse(null);

        Map<String, Object> impact = new LinkedHashMap<>();
        // 依赖指标（下游）
        Map<String, Object> mi = metricService.impact(metricKey);
        impact.put("downstreamMetrics", mi.get("downstreamMetrics"));
        // 受影响报表
        List<Map<String, Object>> affectedReports = new ArrayList<>();
        reportItemRepo.findByMetricKey(metricKey).forEach(item -> {
            affectedReports.add(Map.of("reportId", item.getReportId(), "metricKey", item.getMetricKey(),
                    "pinnedVersionId", item.getMetricVersionId()));
        });
        impact.put("affectedReports", affectedReports);
        // 层级差异：当前发布版本 vs 目标版本的 grain
        if (current != null) {
            Map<String, Object> grainDiff = new LinkedHashMap<>();
            grainDiff.put("fromVersion", current.getVersion());
            grainDiff.put("toVersion", toVersion);
            grainDiff.put("note", "若分组层级（grain/维度）变化，依赖报表需重新核对展示粒度");
            impact.put("hierarchyDiff", grainDiff);
        }
        if (Boolean.TRUE.equals(target.getPurposeRequired()) && (effectivePurpose == null || effectivePurpose.isBlank())) {
            throw new IllegalArgumentException("该指标定义依赖退款时间口径，提交审批时必须固定用途 financial 或 order_batch");
        }
        // 口径版本一致性：审批固定到当前发布口径版本，并记入 impact 供发布清单核对
        com.semantic.platform.semantics.RefundSemantics sem = null;
        Long semanticsId = null;
        Integer profileVersion = null;
        if (effectivePurpose != null && !effectivePurpose.isBlank()) {
            sem = attributionService.profile(effectivePurpose) == null ? null
                    : attributionService.semantics(attributionService.profile(effectivePurpose).getSemanticsId());
            if (sem != null) {
                semanticsId = sem.getId();
                profileVersion = attributionService.profile(effectivePurpose).getProfileVersion();
                impact.put("purpose", effectivePurpose);
                impact.put("semanticsId", semanticsId);
                impact.put("semanticsRef", sem.refIdentity());
                impact.put("profileVersion", profileVersion);
                impact.put("referenceIdentity", com.semantic.platform.semantics.RefundAttributionService
                        .referenceIdentity(effectivePurpose, sem, profileVersion,
                                attributionService.latestRevision(sem.getId())));
            }
        }
        // 并发冲突：同指标其他待审批（同一用途的并发草稿才构成冲突，不同用途是独立定义）
        List<Map<String, Object>> concurrent = new ArrayList<>();
        for (Approval a : approvalRepo.findByMetricKeyOrderBySubmittedAtDesc(metricKey)) {
            if ("pending".equals(a.getStatus()) && !a.getToVersion().equals(toVersion)) {
                boolean samePurpose = java.util.Objects.equals(a.getPurpose(), effectivePurpose);
                concurrent.add(Map.of("approvalId", a.getId(), "toVersion", a.getToVersion(),
                        "purpose", a.getPurpose() == null ? "" : a.getPurpose(),
                        "samePurpose", samePurpose,
                        "submittedBy", a.getSubmittedBy() == null ? "" : a.getSubmittedBy()));
            }
        }
        impact.put("concurrentPending", concurrent);

        Approval approval = new Approval();
        approval.setMetricKey(metricKey);
        approval.setFromVersion(current == null ? null : current.getVersion());
        approval.setToVersion(toVersion);
        approval.setChangeType(changeType);
        approval.setStatus("pending");
        approval.setSubmittedBy(submittedBy);
        approval.setPurpose(effectivePurpose);
        approval.setSemanticsId(semanticsId);
        approval.setProfileVersion(profileVersion);
        try {
            approval.setImpactJson(mapper.writeValueAsString(impact));
        } catch (Exception e) {
            approval.setImpactJson("{}");
        }
        return approvalRepo.save(approval);
    }

    /** 批准：发布目标版本（发布时会做组合环路重检）。 */
    @Transactional
    public Approval approve(Long approvalId, String decidedBy, String note) {
        Approval approval = approvalRepo.findById(approvalId).orElseThrow();
        if (!"pending".equals(approval.getStatus())) {
            throw new IllegalStateException("审批已处理");
        }
        // 并发修改用途：审批提交后用途档案若已切换，不允许静默按新用途发布
        if (approval.getPurpose() != null) {
            var profile = attributionService.profile(approval.getPurpose());
            if (approval.getProfileVersion() != null
                    && !approval.getProfileVersion().equals(profile.getProfileVersion())) {
                throw new IllegalStateException("用途 '" + approval.getPurpose() + "' 在审批期间被并发修改（profile v"
                        + approval.getProfileVersion() + " -> v" + profile.getProfileVersion()
                        + "），请重新提交审批");
            }
        }
        // 发布（内部做组合环路与用途语义重检）
        metricService.publish(approval.getMetricKey(), approval.getToVersion(), decidedBy);
        approval.setStatus("approved");
        approval.setDecidedBy(decidedBy);
        approval.setDecidedAt(java.time.OffsetDateTime.now());
        approval.setNote(note);
        return approvalRepo.save(approval);
    }

    @Transactional
    public Approval reject(Long approvalId, String decidedBy, String note) {
        Approval approval = approvalRepo.findById(approvalId).orElseThrow();
        if (!"pending".equals(approval.getStatus())) {
            throw new IllegalStateException("审批已处理");
        }
        approval.setStatus("rejected");
        approval.setDecidedBy(decidedBy);
        approval.setDecidedAt(java.time.OffsetDateTime.now());
        approval.setNote(note);
        return approvalRepo.save(approval);
    }

    public List<Approval> list(String status) {
        return status == null ? approvalRepo.findAll() : approvalRepo.findByStatus(status);
    }

    public Approval get(Long id) { return approvalRepo.findById(id).orElseThrow(); }
}
