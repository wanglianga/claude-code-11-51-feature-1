package com.semantic.platform.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantic.platform.metric.MetricEdge;
import com.semantic.platform.metric.MetricEdgeRepository;
import com.semantic.platform.metric.MetricVersion;
import com.semantic.platform.metric.MetricVersionRepository;
import com.semantic.platform.report.ReportItem;
import com.semantic.platform.report.ReportItemRepository;
import com.semantic.platform.semantics.RefundAttributionService;
import com.semantic.platform.semantics.RefundSemantics;
import com.semantic.platform.source.SourceBatch;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 影响计算：新批次（尤其迟到退款/余额更正）到达后，只标出受影响的用途与期间，
 * 生成新运行候选；已发布报表继续引用原运行，直到负责人发布更正。
 *
 * 用途隔离：
 * <ul>
 *   <li>进入退款发生期间的财务发生（financial）运行，只受入账期间所在批次影响；</li>
 *   <li>按原交易日归属的运营订单（order_batch）运行，只受原订单批次影响；</li>
 *   <li>待归属退款不进入任何期间，但会以 pending 信息影响两个用途的候选；</li>
 *   <li>下游指标按发布时固定的用途参数继承受影响用途。</li>
 * </ul>
 */
@Service
public class ImpactService {

    private final MetricVersionRepository versionRepo;
    private final MetricEdgeRepository edgeRepo;
    private final ReportItemRepository reportItemRepo;
    private final RunRepository runRepo;
    private final RunService runService;
    private final RefundAttributionService attributionService;
    private final com.semantic.platform.source.RefundFactRepository refundRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    public ImpactService(MetricVersionRepository versionRepo, MetricEdgeRepository edgeRepo,
                         ReportItemRepository reportItemRepo, RunRepository runRepo, RunService runService,
                         RefundAttributionService attributionService,
                         com.semantic.platform.source.RefundFactRepository refundRepo) {
        this.versionRepo = versionRepo;
        this.edgeRepo = edgeRepo;
        this.reportItemRepo = reportItemRepo;
        this.runRepo = runRepo;
        this.runService = runService;
        this.attributionService = attributionService;
        this.refundRepo = refundRepo;
    }

    /**
     * 批次到达后的影响计算。返回受影响的指标/用途/期间、新归属修订与新运行候选。
     */
    @Transactional
    public Map<String, Object> onBatchImported(SourceBatch batch) {
        Map<String, Object> out = new LinkedHashMap<>();
        String sourceType = batch.getSourceType();
        out.put("sourceType", sourceType);
        out.put("batchId", batch.getId());

        // 1) 退款批次到达 -> 重建归属修订（追加，不覆盖旧修订）
        RefundSemantics sem = null;
        Integer newRevision = null;
        Map<String, Object> attributionBefore = new LinkedHashMap<>();
        Map<String, Object> attributionAfter = new LinkedHashMap<>();
        if ("refunds".equals(sourceType)) {
            try {
                sem = attributionService.publishedSemantics();
                Integer oldRev = attributionService.latestRevision(sem.getId());
                if (oldRev != null) {
                    attributionBefore.putAll(attributionService.summary(sem, oldRev,
                            attributionService.rows(sem.getId(), oldRev)));
                }
                newRevision = attributionService.rebuild(sem.getId(), "batch-import").getRevision();
                attributionAfter.putAll(attributionService.summary(sem, newRevision,
                        attributionService.rows(sem.getId(), newRevision)));
                out.put("attributionRevisionBefore", oldRev);
                out.put("attributionRevisionAfter", newRevision);
                out.put("attributionBefore", attributionBefore);
                out.put("attributionAfter", attributionAfter);
                out.put("pendingByPurpose",
                        attributionService.pendingByPurposeAndPeriod(sem.getId(), newRevision));
                out.put("conservation", attributionService.conservation(sem.getId(), newRevision));
            } catch (Exception e) {
                out.put("attributionError", e.getMessage());
            }
        }

        // 2) 找出依赖该来源的已发布指标
        List<String> affectedMetrics = new ArrayList<>();
        for (MetricVersion v : versionRepo.findByStatus("published")) {
            if (v.getDefinitionJson() != null && definitionUsesSource(v.getDefinitionJson(), sourceType)) {
                affectedMetrics.add(v.getMetricKey());
            }
        }
        List<String> withDependents = transitiveDependents(affectedMetrics);
        out.put("affectedMetrics", withDependents);

        // 3) 受影响报表项 -> 按（用途, 期间）生成候选；旧报表继续引用原运行
        List<Map<String, Object>> candidates = new ArrayList<>();
        Map<String, Set<String>> affectedPurposesPeriods = new LinkedHashMap<>();
        for (String metricKey : withDependents) {
            for (ReportItem item : reportItemRepo.findByMetricKey(metricKey)) {
                Run oldRun = runRepo.findById(item.getRunId()).orElse(null);
                if (oldRun == null || oldRun.getPeriodFrom() == null) continue;
                String purpose = item.getPurpose() != null ? item.getPurpose() : oldRun.getPurpose();
                // 用途与期间命中判断：退款批次只影响相应用途
                String hit = periodHit(sourceType, batch, oldRun, purpose);
                if (hit == null) continue;

                Map<String, Object> cand = new LinkedHashMap<>();
                cand.put("reportItemId", item.getId());
                cand.put("metricKey", metricKey);
                cand.put("purpose", purpose);
                cand.put("purposeLabel", item.getPurposeLabel());
                cand.put("applicableUsers", item.getApplicableUsers());
                cand.put("referenceIdentity", item.getReferenceIdentity());
                cand.put("oldRunId", oldRun.getId());
                cand.put("period", oldRun.getPeriodFrom() + " ~ " + oldRun.getPeriodTo());
                cand.put("affectedPeriod", hit);
                cand.put("status", "pending");
                cand.put("note", "迟到数据触发的新运行候选；已发布报表仍引用原运行，待负责人发布更正");
                candidates.add(cand);
                affectedPurposesPeriods.computeIfAbsent(purpose == null ? "(none)" : purpose,
                        k -> new LinkedHashSet<>()).add(hit);
            }
        }
        out.put("candidates", candidates);
        out.put("affectedPurposesPeriods", affectedPurposesPeriods);
        out.put("message", "已生成 " + candidates.size() + " 个运行候选；已发布报表继续引用原运行"
                + (newRevision != null ? "；归属修订追加为 " + newRevision : ""));
        return out;
    }

    /**
     * 判断批次是否影响某运行的某用途期间：
     * financial -> 看入账日/批次期间；order_batch -> 看原交易日/订单批次。
     * 退款批次对 order_batch 的影响来自其原交易日，用归属修订中的原交易日判断。
     */
    private String periodHit(String sourceType, SourceBatch batch, Run run, String purpose) {
        int fromMonth = run.getPeriodFrom().getYear() * 12 + run.getPeriodFrom().getMonthValue();
        int toMonth = run.getPeriodTo().getYear() * 12 + run.getPeriodTo().getMonthValue();
        if ("refunds".equals(sourceType)) {
            if (batch.getPeriodStart() == null) return null;
            String batchPeriod = java.time.YearMonth.from(batch.getPeriodStart()).toString();
            // 用批次关联的退款事实（同批次/同入账日）判断：财务看入账日，运营看原交易日。
            String purposePeriod = refundAttributionPeriod(batch, purpose);
            if (purposePeriod == null) return null;
            int m = java.time.YearMonth.parse(purposePeriod).getYear() * 12
                    + java.time.YearMonth.parse(purposePeriod).getMonthValue();
            if (m < fromMonth || m > toMonth) return null;
            return RefundAttributionService.PURPOSE_ORDER_BATCH.equals(purpose)
                    ? purposePeriod + "（原交易日追溯）" : purposePeriod;
        }
        // 其它来源（订单补数等）：期间重叠即命中
        if (batch.getPeriodStart() == null) return null;
        int bm = java.time.YearMonth.from(batch.getPeriodStart()).getYear() * 12
                + java.time.YearMonth.from(batch.getPeriodStart()).getMonthValue();
        if (bm >= fromMonth && bm <= toMonth) return java.time.YearMonth.from(batch.getPeriodStart()).toString();
        return null;
    }

    /**
     * 退款批次在指定用途下的归属期间：财务发生=实际入账日，运营订单=原交易日。
     * 取该批次入账日对应的退款事实；无法关联原订单行的退款返回 null（待归属不占用期间）。
     */
    private String refundAttributionPeriod(SourceBatch batch, String purpose) {
        if (batch.getPeriodStart() == null) return null;
        java.util.List<com.semantic.platform.source.RefundFact> facts = refundFactsForBatch(batch);
        String linked = facts.stream()
                .filter(r -> "linked".equals(r.getLinkStatus()))
                .map(r -> RefundAttributionService.PURPOSE_ORDER_BATCH.equals(purpose)
                        ? r.getOriginalBusinessDate() : r.getBusinessDate())
                .filter(java.util.Objects::nonNull)
                .map(java.time.YearMonth::from)
                .map(java.time.YearMonth::toString)
                .findFirst().orElse(null);
        if (linked != null) return linked;
        // 无法关联原订单行的退款：以入账期间提示“待归属”，不进入任何期间金额
        boolean hasPending = facts.stream().anyMatch(r -> !"linked".equals(r.getLinkStatus()));
        return hasPending ? java.time.YearMonth.from(batch.getPeriodStart()).toString() : null;
    }

    private List<com.semantic.platform.source.RefundFact> refundFactsForBatch(SourceBatch batch) {
        return refundRepo.findAll().stream()
                .filter(r -> batch.getStoreId() == null || batch.getStoreId().equals(r.getStoreId()))
                .filter(r -> r.getBusinessDate() != null && batch.getPeriodStart() != null
                        && r.getBusinessDate().equals(batch.getPeriodStart()))
                .toList();
    }

    /** 原订单行被更正：只标出受影响的用途与期间，生成新归属修订与候选。 */
    @Transactional
    public Map<String, Object> onOrderLineCorrected(String orderLineId, String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        RefundSemantics sem = attributionService.publishedSemantics();
        Integer oldRev = attributionService.latestRevision(sem.getId());
        Integer newRev = attributionService.rebuild(sem.getId(), "line-correction").getRevision();
        out.put("orderLineId", orderLineId);
        out.put("reason", reason);
        out.put("attributionRevisionBefore", oldRev);
        out.put("attributionRevisionAfter", newRev);
        out.put("affected", attributionService.correctionsForLine(sem.getId(), orderLineId));
        out.put("pendingByPurpose", attributionService.pendingByPurposeAndPeriod(sem.getId(), newRev));
        out.put("conservation", attributionService.conservation(sem.getId(), newRev));
        out.put("note", "只标出受影响的用途与期间并生成新归属修订；旧报表继续按原输入复演");
        return out;
    }

    private boolean definitionUsesSource(String definitionJson, String sourceType) {
        if ("refunds".equals(sourceType)) {
            // 退款事实/归属来源都算
            return definitionJson.contains("\"refunds\"") || definitionJson.contains("\"refund_attributions\"");
        }
        return definitionJson.contains("\"" + sourceType + "\"");
    }

    private List<String> transitiveDependents(List<String> seeds) {
        Map<String, List<String>> reverse = new LinkedHashMap<>();
        edgeRepo.findAll().forEach(e -> reverse.computeIfAbsent(e.getToMetric(), k -> new ArrayList<>()).add(e.getFromMetric()));
        Set<String> seen = new LinkedHashSet<>(seeds);
        List<String> queue = new ArrayList<>(seeds);
        while (!queue.isEmpty()) {
            String cur = queue.remove(0);
            for (String d : reverse.getOrDefault(cur, List.of())) {
                if (seen.add(d)) queue.add(d);
            }
        }
        return new ArrayList<>(seen);
    }
}
