package com.semantic.platform.report;

import com.semantic.platform.metric.MetricVersion;
import com.semantic.platform.metric.MetricVersionRepository;
import com.semantic.platform.run.Run;
import com.semantic.platform.run.RunRepository;
import com.semantic.platform.semantics.RefundAttributionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 报表服务：报表组装、粒度/用途一致性校验、发布锁定、更正。
 *
 * 用途约束：
 * <ul>
 *   <li>同一报表（同一仪表盘）不允许把 financial 与 order_batch 当成可比序列混排；</li>
 *   <li>每个报表项固定用途、口径版本与归属修订身份，消费端始终能看到“这是财务发生还是订单批次表现”；</li>
 *   <li>已发布报表继续引用原运行，迟到退款/更正只生成候选，由负责人决定是否更正；</li>
 *   <li>更正必须是同一用途、同一口径版本、同一归属修订的复演，并发修改用途会被拒绝。</li>
 * </ul>
 */
@Service
public class ReportService {

    private final ReportRepository reportRepo;
    private final ReportItemRepository itemRepo;
    private final MetricVersionRepository versionRepo;
    private final RunRepository runRepo;

    public ReportService(ReportRepository reportRepo, ReportItemRepository itemRepo,
                         MetricVersionRepository versionRepo, RunRepository runRepo) {
        this.reportRepo = reportRepo;
        this.itemRepo = itemRepo;
        this.versionRepo = versionRepo;
        this.runRepo = runRepo;
    }

    @Transactional
    public Report create(String name, String createdBy) {
        Report r = new Report();
        r.setName(name);
        r.setCreatedBy(createdBy);
        r.setStatus("draft");
        return reportRepo.save(r);
    }

    /**
     * 添加报表项。校验：
     * 1) 展示粒度一致，禁止门店日与城市月混排；
     * 2) 用途一致，同一仪表盘不允许把财务发生与运营订单当成同一条可比序列；
     * 3) 引用身份完整（用途 + 口径版本 + 归属修订）。
     */
    @Transactional
    public ReportItem addItem(Long reportId, String metricKey, Long metricVersionId, Long runId, String displayJson) {
        Report report = reportRepo.findById(reportId).orElseThrow();
        if (!"draft".equals(report.getStatus())) {
            throw new IllegalStateException("报表已发布，不能修改；如需更正请走更正流程");
        }
        MetricVersion version = versionRepo.findById(metricVersionId).orElseThrow();
        Run run = runRepo.findById(runId).orElseThrow();
        List<ReportItem> existing = itemRepo.findByReportId(reportId);
        for (ReportItem item : existing) {
            Run otherRun = runRepo.findById(item.getRunId()).orElseThrow();
            if (!otherRun.getGrain().equals(run.getGrain())) {
                throw new IllegalStateException(
                        "粒度不一致：报表内已有 " + otherRun.getGrain() + " 粒度指标，不能加入 " + run.getGrain()
                                + " 粒度指标（禁止门店颗粒度与城市/月颗粒度混排）");
            }
            if (!Objects.equals(normalize(item.getPurpose()), normalize(run.getPurpose()))) {
                throw new IllegalStateException(
                        "用途不一致：同一仪表盘不能把不同退款用途当成可比的同一序列（已有 "
                                + describe(item.getPurpose()) + "，新加入 " + describe(run.getPurpose())
                                + "）。请拆成两个报表，或统一用途后再引用");
            }
            if (item.getSemanticsId() != null && run.getSemanticsId() != null
                    && !item.getSemanticsId().equals(run.getSemanticsId())) {
                throw new IllegalStateException("口径版本不一致：报表项必须引用同一退款口径版本");
            }
        }
        ReportItem item = new ReportItem();
        item.setReportId(reportId);
        item.setMetricKey(metricKey);
        item.setMetricVersionId(metricVersionId);
        item.setRunId(runId);
        item.setDisplayJson(displayJson);
        item.setPurpose(run.getPurpose());
        item.setSemanticsId(run.getSemanticsId());
        item.setPurposeLabel(run.getPurposeLabel());
        item.setApplicableUsers(run.getApplicableUsers());
        item.setReferenceIdentity(run.getReferenceIdentity());
        return itemRepo.save(item);
    }

    @Transactional
    public Report publish(Long reportId) {
        Report report = reportRepo.findById(reportId).orElseThrow();
        List<ReportItem> items = itemRepo.findByReportId(reportId);
        if (items.isEmpty()) throw new IllegalStateException("空报表不能发布");
        // 发布清单：每项都必须写明用途与口径引用身份
        for (ReportItem item : items) {
            if (item.getPurpose() == null || item.getSemanticsId() == null || item.getReferenceIdentity() == null) {
                throw new IllegalStateException("报表项 '" + item.getMetricKey()
                        + "' 缺少用途/口径引用身份，无法发布（下游不能只引用无用途的模板名）");
            }
        }
        report.setStatus("published");
        report.setPublishedAt(java.time.OffsetDateTime.now());
        return reportRepo.save(report);
    }

    /**
     * 更正报表项：用新的运行替换原运行（迟到退款/补数后）。
     * 必须是同一用途、同一口径版本、同一归属修订的复演；旧报表在更正前继续按原输入复演。
     */
    @Transactional
    public ReportItem correctItem(Long reportId, String metricKey, Long newRunId, String purpose,
                                  Long expectedSemanticsId, Integer expectedRevision) {
        Report report = reportRepo.findById(reportId).orElseThrow();
        if (!"published".equals(report.getStatus())) {
            throw new IllegalStateException("仅已发布报表可更正");
        }
        ReportItem item = itemRepo.findByReportId(reportId).stream()
                .filter(i -> i.getMetricKey().equals(metricKey)
                        && (purpose == null || Objects.equals(i.getPurpose(), purpose)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("报表不含指标 " + metricKey
                        + (purpose == null ? "" : "（用途 " + purpose + "）")));
        Run newRun = runRepo.findById(newRunId).orElseThrow();
        Run oldRun = runRepo.findById(item.getRunId()).orElseThrow();
        if (!oldRun.getGrain().equals(newRun.getGrain())) {
            throw new IllegalStateException("更正运行的粒度与原运行不一致");
        }
        // 并发修改用途：更正携带的期望用途/口径版本必须与报表项一致，否则拒绝。
        if (!Objects.equals(normalize(item.getPurpose()), normalize(newRun.getPurpose()))) {
            throw new IllegalArgumentException("更正运行用途 '" + describe(newRun.getPurpose())
                    + "' 与报表项用途 '" + describe(item.getPurpose()) + "' 不一致，不能跨用途更正");
        }
        if (expectedSemanticsId != null && !Objects.equals(item.getSemanticsId(), expectedSemanticsId)) {
            throw new IllegalStateException("报表项口径版本已被并发修改：期望 " + expectedSemanticsId
                    + "，实际 " + item.getSemanticsId() + "，请刷新后重试");
        }
        if (expectedRevision != null && !Objects.equals(newRun.getAttributionRevision(), expectedRevision)) {
            throw new IllegalStateException("更正运行归属修订 " + newRun.getAttributionRevision()
                    + " 与期望 " + expectedRevision + " 不一致");
        }
        item.setRunId(newRunId);
        item.setMetricVersionId(newRun.getMetricVersionId());
        item.setSemanticsId(newRun.getSemanticsId());
        item.setPurposeLabel(newRun.getPurposeLabel());
        item.setApplicableUsers(newRun.getApplicableUsers());
        item.setReferenceIdentity(newRun.getReferenceIdentity());
        return itemRepo.save(item);
    }

    public ReportItem correctItem(Long reportId, String metricKey, Long newRunId) {
        return correctItem(reportId, metricKey, newRunId, null, null, null);
    }

    private String normalize(String purpose) {
        return purpose == null || purpose.isBlank() ? null : purpose;
    }

    private String describe(String purpose) {
        if (purpose == null) return "无用途";
        return switch (purpose) {
            case RefundAttributionService.PURPOSE_FINANCIAL -> "财务发生（financial）";
            case RefundAttributionService.PURPOSE_ORDER_BATCH -> "运营订单（order_batch）";
            case RefundAttributionService.PURPOSE_RECONCILIATION -> "对账（reconciliation）";
            default -> purpose;
        };
    }

    public List<Report> listAll() { return reportRepo.findAll(); }
    public List<ReportItem> items(Long reportId) { return itemRepo.findByReportId(reportId); }
    public Report get(Long id) { return reportRepo.findById(id).orElseThrow(); }
}
