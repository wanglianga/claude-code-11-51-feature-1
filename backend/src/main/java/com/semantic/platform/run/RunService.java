package com.semantic.platform.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantic.platform.dsl.Expr;
import com.semantic.platform.dsl.ExprValidator;
import com.semantic.platform.engine.CellKey;
import com.semantic.platform.engine.CellValue;
import com.semantic.platform.engine.EvaluationContext;
import com.semantic.platform.engine.Evaluator;
import com.semantic.platform.engine.Series;
import com.semantic.platform.metric.MetricRelease;
import com.semantic.platform.metric.MetricService;
import com.semantic.platform.metric.MetricVersion;
import com.semantic.platform.metric.MetricVersionRepository;
import com.semantic.platform.semantics.PurposeProfile;
import com.semantic.platform.semantics.RefundAttribution;
import com.semantic.platform.semantics.RefundAttributionService;
import com.semantic.platform.semantics.RefundSemantics;
import com.semantic.platform.source.HolidayRepository;
import com.semantic.platform.source.OrderFactRepository;
import com.semantic.platform.source.OrderLineRepository;
import com.semantic.platform.source.RefundFactRepository;
import com.semantic.platform.source.SourceBatch;
import com.semantic.platform.source.SourceBatchRepository;
import com.semantic.platform.source.StoreStatusRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 运行服务：执行指标定义，固定引用版本、用途、退款口径版本与归属修订，
 * 标记数据截止点与覆盖范围，持久化单元格、血缘、待归属清单与固定输入摘要。
 */
@Service
public class RunService {

    private final MetricService metricService;
    private final MetricVersionRepository versionRepo;
    private final RunRepository runRepo;
    private final RunCellRepository cellRepo;
    private final RunCellLineageRepository lineageRepo;
    private final OrderFactRepository orderRepo;
    private final OrderLineRepository orderLineRepo;
    private final RefundFactRepository refundRepo;
    private final StoreStatusRepository storeStatusRepo;
    private final HolidayRepository holidayRepo;
    private final SourceBatchRepository batchRepo;
    private final RefundAttributionService attributionService;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public RunService(MetricService metricService, MetricVersionRepository versionRepo,
                      RunRepository runRepo, RunCellRepository cellRepo, RunCellLineageRepository lineageRepo,
                      OrderFactRepository orderRepo, OrderLineRepository orderLineRepo, RefundFactRepository refundRepo,
                      StoreStatusRepository storeStatusRepo, HolidayRepository holidayRepo,
                      SourceBatchRepository batchRepo, RefundAttributionService attributionService) {
        this.metricService = metricService;
        this.versionRepo = versionRepo;
        this.runRepo = runRepo;
        this.cellRepo = cellRepo;
        this.lineageRepo = lineageRepo;
        this.orderRepo = orderRepo;
        this.orderLineRepo = orderLineRepo;
        this.refundRepo = refundRepo;
        this.storeStatusRepo = storeStatusRepo;
        this.holidayRepo = holidayRepo;
        this.batchRepo = batchRepo;
        this.attributionService = attributionService;
    }

    /**
     * 执行一次运行。purpose 必须由请求显式给出（含“未选择”的拒绝），
     * 不存在默认最近使用分支。revisionOverride 用于报表复演：固定到旧运行的原归属修订。
     */
    @Transactional
    public Run execute(String metricKey, Long metricVersionId, LocalDate from, LocalDate to,
                       String grain, String scopeJson, String purpose, Long semanticsIdOverride,
                       Integer revisionOverride) {
        return execute(metricKey, metricVersionId, from, to, grain, scopeJson, purpose,
                semanticsIdOverride, revisionOverride, null);
    }

    @Transactional
    public Run execute(String metricKey, Long metricVersionId, LocalDate from, LocalDate to,
                       String grain, String scopeJson, String purpose, Long semanticsIdOverride,
                       Integer revisionOverride, String snapshotJson) {
        MetricVersion version = versionRepo.findById(metricVersionId)
                .orElseThrow(() -> new IllegalArgumentException("指标版本不存在: " + metricVersionId));
        if (!version.getMetricKey().equals(metricKey)) {
            throw new IllegalArgumentException("指标键 '" + metricKey + "' 与版本 " + metricVersionId
                    + "（属于 '" + version.getMetricKey() + "'）不一致");
        }
        Expr expr = metricService.parseExpr(version.getDefinitionJson());
        boolean purposeBound = isPurposeBound(expr);

        // 0) 已绑定用途的定义只能在其固定用途下运行；用途模板必须显式传参。
        if (version.getBoundPurpose() != null && purpose != null && !purpose.isBlank()
                && !version.getBoundPurpose().equals(purpose)) {
            throw new IllegalArgumentException("用途不匹配：指标 '" + metricKey + "' 固定为用途 '"
                    + version.getBoundPurpose() + "'，不能以 '" + purpose + "' 运行；两种用途不可互换");
        }

        // 1) 用途必须显式选择；只有完全不含退款口径的指标可以无用途运行。
        PurposeProfile profile = null;
        RefundSemantics semantics = null;
        Integer revision = null;
        if (purpose == null || purpose.isBlank()) {
            if (purposeBound) {
                throw new IllegalArgumentException(
                        "未选择用途：请求必须显式指定 purpose=financial（财务发生）或 order_batch（运营订单），"
                                + "不能默认为最近使用分支");
            }
        } else {
            if (version.getBoundPurpose() == null && !"reconciliation".equals(purpose)) {
                if (!"financial".equals(purpose) && !"order_batch".equals(purpose)) {
                    throw new IllegalArgumentException(
                            "用途模板指标必须以 financial 或 order_batch 运行（对账用途仅用于显式双用途的派生指标）");
                }
            }
            profile = attributionService.profile(purpose);
            semantics = semanticsIdOverride != null
                    ? attributionService.semantics(semanticsIdOverride)
                    : attributionService.semantics(profile.getSemanticsId());
            if ("published".equals(semantics.getStatus()) == false) {
                throw new IllegalStateException("退款口径版本未发布: " + semantics.refIdentity());
            }
            revision = attributionService.latestRevision(semantics.getId());
            if (revisionOverride != null) revision = revisionOverride;
            if (revision == null && revisionOverride == null) {
                // 首次运行：依据当前退款事实生成首个归属修订
                revision = attributionService.rebuild(semantics.getId(), "system").getRevision();
            }
            // 2) 语义编译：同一运行/下游依赖必须固定同一口径版本
            verifySemanticsConsistency(metricKey, purpose, semantics);
        }

        Run run = new Run();
        run.setMetricKey(metricKey);
        run.setMetricVersionId(metricVersionId);
        run.setGrain(grain);
        run.setPeriodFrom(from);
        run.setPeriodTo(to);
        run.setScopeJson(scopeJson);
        run.setResultKind(expr instanceof Expr.Cohort ? "cohort_growth" : "metric");

        EvaluationContext ctx = new EvaluationContext(from, to, grain);
        ctx.purpose = purpose;
        // 归属日期可能早于/晚于运行期间（跨年比较、追溯订单）：源事实按运行窗口放宽加载，
        // 实际参与由归属行的归属日期与期间覆盖双重决定。
        java.time.LocalDate loadFrom = periodWindowStart(from, grain);
        java.time.LocalDate loadTo = periodWindowEnd(to, grain);
        ctx.orders = orderRepo.findByBusinessDateBetween(loadFrom, loadTo);
        ctx.orderLines = orderLineRepo.findByBusinessDateBetween(loadFrom, loadTo);
        ctx.refunds = refundRepo.findByBusinessDateBetween(loadFrom, loadTo);
        ctx.storeStatus = storeStatusRepo.findByBusinessDateBetween(loadFrom, loadTo);
        ctx.holidays = holidayRepo.findByHolidayDateBetween(loadFrom, loadTo);
        ctx.refResolver = k -> {
            MetricVersion pv = metricService.publishedVersion(k);
            return metricService.parseExpr(pv.getDefinitionJson());
        };
        ctx.refTypes = metricService.publishedTypes(purpose);

        if (snapshotJson != null && !snapshotJson.isBlank()) {
            restoreSnapshot(ctx, snapshotJson);
            run.setInputSnapshotJson(snapshotJson);
        }

        if (semantics != null) {
            ctx.semanticsId = semantics.getId();
            ctx.attributionRevision = revision;
            if (ctx.refundAttributions.isEmpty()) {
                ctx.refundAttributions = attributionRowsFor(from, to, purpose, semantics, revision);
            }
            run.setPurpose(purpose);
            run.setProfileVersion(profile.getProfileVersion());
            run.setSemanticsId(semantics.getId());
            run.setSemanticsCode(semantics.getCode());
            run.setSemanticsVersion(semantics.getVersion());
            run.setAttributionRevision(revision);
            run.setPurposeLabel(profile.getLabel());
            run.setApplicableUsers(profile.getApplicableUsers());
            run.setReferenceIdentity(RefundAttributionService.referenceIdentity(
                    purpose, semantics, profile.getProfileVersion(), revision));
            run.setPendingJson(pendingSummary(semantics, revision, purpose));
        }

        buildCoverage(ctx, from, to);

        // 输入快照：冻结参与本运行的源事实与归属行（复演时优先使用，保证旧报表不随新批次改变）
        run.setInputSnapshotJson(toJson(snapshot(ctx)));

        Evaluator evaluator = new Evaluator(ctx);
        Series series;
        try {
            series = evaluator.eval(expr);
        } catch (Exception e) {
            run.setStatus("failed");
            String msg = e.getMessage();
            run.setError((msg == null ? e.getClass().getSimpleName() : msg)
                    + " [" + (e.getStackTrace().length > 0 ? e.getStackTrace()[0].toString() : "") + "]");
            run.setFinishedAt(java.time.OffsetDateTime.now());
            return runRepo.save(run);
        }

        run.setDataCutoffJson(computeCutoff(ctx, from, to));
        run.setInputDigestJson(inputDigest(ctx, semantics, revision, purpose));
        if (snapshotJson == null || snapshotJson.isBlank()) run.setInputSnapshotJson(toJson(snapshot(ctx)));
        run.setStatus("success");
        run.setFinishedAt(java.time.OffsetDateTime.now());
        Run saved = runRepo.save(run);

        for (Map.Entry<CellKey, CellValue> en : series.cells().entrySet()) {
            CellKey key = en.getKey();
            CellValue cv = en.getValue();
            RunCell cell = new RunCell();
            cell.setRunId(saved.getId());
            cell.setPeriodKey(key.periodKey());
            cell.setDimsJson(toJson(key.dims()));
            cell.setValueNum(cv.getNum());
            cell.setValueDen(cv.getDen());
            cell.setCellStatus(cv.getStatus().name().toLowerCase());
            cell.setMessage(cv.getMessage() != null ? cv.getMessage() : cv.getTrace());
            cellRepo.save(cell);

            String cellKey = key.asString();
            for (CellValue.Lineage lin : cv.getLineage()) {
                RunCellLineage l = new RunCellLineage();
                l.setRunId(saved.getId());
                l.setCellKey(cellKey);
                l.setRecordType(lin.recordType());
                l.setRecordId(lin.recordId());
                l.setContribution(lin.contribution());
                l.setIncluded(lin.included());
                l.setReason(lin.reason());
                lineageRepo.save(l);
            }
        }
        return saved;
    }

    public Run execute(String metricKey, Long metricVersionId, LocalDate from, LocalDate to,
                       String grain, String scopeJson) {
        return execute(metricKey, metricVersionId, from, to, grain, scopeJson, null, null, null);
    }

    /** 指标定义或依赖链是否引用退款时间口径（需要显式用途）。 */
    public boolean isPurposeBound(Expr expr) {
        if (metricService.usesRefundAttribution(expr)) return true;
        Set<String> bound = metricService.purposeBoundMetrics(metricService.publishedInfos());
        Set<String> refs = new java.util.HashSet<>(ExprValidator.collectRefs(expr));
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(refs);
        Set<String> seen = new java.util.HashSet<>(refs);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            if (bound.contains(cur)) return true;
            try {
                Expr def = metricService.parseExpr(metricService.publishedVersion(cur).getDefinitionJson());
                for (String next : ExprValidator.collectRefs(def)) {
                    if (seen.add(next)) queue.add(next);
                }
            } catch (Exception ignored) { }
        }
        return false;
    }

    /**
     * 语义编译一致性：运行所引用指标（模板及其下游依赖）的发布清单必须固定同一退款口径版本；
     * 不同口径版本不能被当成可比序列放进同一结果。
     */
    private void verifySemanticsConsistency(String metricKey, String purpose, RefundSemantics semantics) {
        List<String> refs = new ArrayList<>();
        try {
            MetricVersion version = metricService.publishedVersion(metricKey);
            Expr expr = metricService.parseExpr(version.getDefinitionJson());
            refs.addAll(ExprValidator.collectRefs(expr));
        } catch (Exception ignored) { }
        for (String ref : refs) {
            // 引用指标在本次用途下的发布清单；模板指标同时在两种用途登记，任一清单口径不符即拒绝。
            List<MetricRelease> manifests = new ArrayList<>();
            MetricRelease byPurpose = metricService.releasedFor(ref, purpose);
            if (byPurpose != null) manifests.add(byPurpose);
            for (MetricRelease m : metricService.releaseManifests(ref)) {
                if (m.getSemanticsId() != null && m.getSemanticsId() > 0
                        && manifests.stream().noneMatch(x -> x.getId().equals(m.getId()))) {
                    manifests.add(m);
                }
            }
            for (MetricRelease rel : manifests) {
                if (rel.getSemanticsId() == null) continue;
                if (!rel.getSemanticsId().equals(semantics.getId())) {
                    throw new IllegalStateException("语义编译失败：指标 '" + metricKey + "' 引用 '" + ref
                            + "'（用途 " + rel.getPurpose() + "）固定于口径 "
                            + rel.getSemanticsCode() + "@v" + rel.getSemanticsVersion()
                            + "，与本次运行口径 " + semantics.refIdentity() + " 不一致；请统一口径版本后再运行");
                }
            }
        }
    }

    /**
     * 期间内参与求值的归属行：仅已归属行（待归属绝不进入任何期间金额）。
     * 对账运行同时装载两种用途的已归属行，由 Ref 的显式用途在求值时切换。
     */
    private List<RefundAttribution> attributionRowsFor(LocalDate from, LocalDate to, String purpose,
                                                       RefundSemantics semantics, Integer revision) {
        if (purpose == null) return List.of();
        List<RefundAttribution> out = new ArrayList<>();
        if ("reconciliation".equals(purpose)) {
            for (String p : MetricService.BOUND_PURPOSES) {
                out.addAll(attributionService.periodRows(semantics.getId(), revision, p, from, to));
            }
            return out;
        }
        out.addAll(attributionService.periodRows(semantics.getId(), revision, purpose, from, to));
        return out;
    }

    private String pendingSummary(RefundSemantics semantics, Integer revision, String purpose) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<RefundAttribution> pending = attributionService.pending(semantics.getId(), revision, purpose);
        BigDecimal amount = pending.stream().map(RefundAttribution::getAmountSigned)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Map<String, Object>> items = new ArrayList<>();
        for (RefundAttribution a : pending) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("refundId", a.getRefundId());
            item.put("orderId", a.getOrderId());
            item.put("storeId", a.getStoreId());
            item.put("amount", a.getAmountSigned());
            item.put("postingDate", a.getPostingDate().toString());
            item.put("reason", a.getReason());
            items.add(item);
        }
        out.put("purpose", purpose);
        out.put("pendingAmount", amount);
        out.put("pendingRefunds", items.size());
        out.put("items", items);
        out.put("note", "待归属退款不进入任何期间金额，也不按同名商品/当前价格猜填");
        return toJson(out);
    }

    private String inputDigest(EvaluationContext ctx, RefundSemantics sem, Integer revision, String purpose) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("purpose", purpose);
        out.put("semanticsId", sem == null ? null : sem.getId());
        out.put("semanticsVersion", sem == null ? null : sem.getVersion());
        out.put("attributionRevision", revision);
        out.put("from", ctx.from.toString());
        out.put("to", ctx.to.toString());
        out.put("grain", ctx.grain);
        out.put("orders", ctx.orders.size());
        out.put("orderLines", ctx.orderLines.size());
        out.put("refundAttributions", ctx.refundAttributions.size());
        out.put("storeStatus", ctx.storeStatus.size());
        out.put("note", "固定输入摘要：旧报表按此复演，不随新批次改变");
        return toJson(out);
    }

    private String normDims(String dimsJson) {
        if (dimsJson == null) return "{}";
        try {
            Map<?, ?> m = mapper.readValue(dimsJson, Map.class);
            return new java.util.TreeMap<>(m).toString();
        } catch (Exception e) {
            return dimsJson;
        }
    }

    private Map<String, Object> snapshot(EvaluationContext ctx) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("orders", ctx.orders);
        snap.put("orderLines", ctx.orderLines);
        snap.put("refundAttributions", ctx.refundAttributions);
        snap.put("storeStatus", ctx.storeStatus);
        snap.put("holidays", ctx.holidays);
        return snap;
    }

    @SuppressWarnings("unchecked")
    private void restoreSnapshot(EvaluationContext ctx, String json) {
        try {
            Map<String, Object> snap = mapper.readValue(json, Map.class);
            ctx.orders = mapper.convertValue(snap.getOrDefault("orders", List.of()),
                    mapper.getTypeFactory().constructCollectionType(List.class,
                            com.semantic.platform.source.OrderFact.class));
            ctx.orderLines = mapper.convertValue(snap.getOrDefault("orderLines", List.of()),
                    mapper.getTypeFactory().constructCollectionType(List.class,
                            com.semantic.platform.source.OrderLine.class));
            ctx.refundAttributions = mapper.convertValue(snap.getOrDefault("refundAttributions", List.of()),
                    mapper.getTypeFactory().constructCollectionType(List.class,
                            com.semantic.platform.semantics.RefundAttribution.class));
            ctx.storeStatus = mapper.convertValue(snap.getOrDefault("storeStatus", List.of()),
                    mapper.getTypeFactory().constructCollectionType(List.class,
                            com.semantic.platform.source.StoreStatus.class));
            ctx.holidays = mapper.convertValue(snap.getOrDefault("holidays", List.of()),
                    mapper.getTypeFactory().constructCollectionType(List.class,
                            com.semantic.platform.source.Holiday.class));
        } catch (Exception e) {
            throw new IllegalStateException("运行输入快照恢复失败: " + e.getMessage(), e);
        }
    }

    /** 装载窗口起点：月份粒度放宽到上一年度（同店增长、跨年比较需要），日粒度放宽 1 天。 */
    private LocalDate periodWindowStart(LocalDate from, String grain) {
        return "month".equals(grain) ? from.minusYears(1).withDayOfMonth(1) : from.minusDays(1);
    }

    private LocalDate periodWindowEnd(LocalDate to, String grain) {
        return "month".equals(grain) ? to.plusYears(1).withDayOfMonth(1) : to.plusDays(1);
    }

    private void buildCoverage(EvaluationContext ctx, LocalDate from, LocalDate to) {
        List<SourceBatch> batches = batchRepo.findAll();
        for (SourceBatch b : batches) {
            if (!"loaded".equals(b.getStatus())) continue;
            String source = mapSourceType(b.getSourceType());
            String storeScope = b.getStoreId() == null ? "*" : b.getStoreId();
            LocalDate start = b.getPeriodStart() == null ? from : b.getPeriodStart();
            LocalDate end = b.getPeriodEnd() == null ? to : b.getPeriodEnd();
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                ctx.addCoverage(source, storeScope, d);
                // 归属来源的覆盖继承退款批次（退款晚到晚于订单批次）
                if ("refund_attributions".equals(source) || "refunds".equals(source)) {
                    ctx.addCoverage("refund_attributions", storeScope, d);
                }
            }
        }
    }

    private String mapSourceType(String batchType) {
        return switch (batchType) {
            case "orders" -> "orders";
            case "refunds" -> "refund_attributions";
            case "store_hours" -> "store_status";
            case "holidays" -> "holidays";
            default -> batchType;
        };
    }

    private String computeCutoff(EvaluationContext ctx, LocalDate from, LocalDate to) {
        Map<String, Object> info = new LinkedHashMap<>();
        Map<String, String> cutoffBySource = new LinkedHashMap<>();
        for (String source : List.of("orders", "refund_attributions", "store_status")) {
            LocalDate cutoff = null;
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                final LocalDate day = d;
                boolean covered = ctx.coverage.getOrDefault(source, Set.of()).stream()
                        .anyMatch(k -> k.endsWith("@" + day));
                if (covered) cutoff = d; else break;
            }
            cutoffBySource.put(source, cutoff == null ? null : cutoff.toString());
        }
        info.put("cutoffBySource", cutoffBySource);
        info.put("note", "截止点之后的数据视为未覆盖，不计入零值");
        return toJson(info);
    }

    private String toJson(Object o) {
        try { return mapper.writeValueAsString(o); } catch (Exception e) { return "{}"; }
    }

    public List<Run> listRuns(String metricKey) {
        return metricKey == null ? runRepo.findAll() : runRepo.findByMetricKeyOrderByStartedAtDesc(metricKey);
    }

    public List<RunCell> cells(Long runId) { return cellRepo.findByRunId(runId); }

    public List<RunCellLineage> lineage(Long runId, String cellKey) {
        return lineageRepo.findByRunIdAndCellKey(runId, cellKey);
    }

    public Run getRun(Long runId) { return runRepo.findById(runId).orElseThrow(); }

    /**
     * 按原输入复演：固定原运行的用途、口径版本与归属修订重跑（归属明细账按修订不可变）。
     * 用于证明旧报表在退款晚到/更正后仍能按原输入得到原结果。
     */
    @Transactional
    public Map<String, Object> replay(Long runId) {
        Run original = getRun(runId);
        Run replayed = execute(original.getMetricKey(), original.getMetricVersionId(),
                original.getPeriodFrom(), original.getPeriodTo(), original.getGrain(),
                original.getScopeJson(), original.getPurpose(), original.getSemanticsId(),
                original.getAttributionRevision(), original.getInputSnapshotJson());
        cellRepo.flush();
        runRepo.flush();
        List<RunCell> a = cells(runId);
        List<RunCell> b = cells(replayed.getId());
        // dimsJson 经 jsonb 往返后空白会变化，比较前先归一化维度集合。
        java.util.function.Function<RunCell, String> key = c -> c.getPeriodKey() + "|" + normDims(c.getDimsJson());
        Map<String, BigDecimal> va = new LinkedHashMap<>();
        for (RunCell c : a) va.put(key.apply(c), c.getValueNum());
        List<String> mismatches = new ArrayList<>();
        for (RunCell c : b) {
            BigDecimal prev = va.get(key.apply(c));
            BigDecimal value = c.getValueNum();
            boolean same = prev == null ? value == null : prev.compareTo(value == null ? BigDecimal.ZERO : value) == 0;
            if (!same) mismatches.add(key.apply(c) + " expected=" + prev + " actual=" + value);
        }
        boolean identical = a.size() == b.size() && mismatches.isEmpty();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("originalRunId", runId);
        out.put("replayedRunId", replayed.getId());
        out.put("purpose", original.getPurpose());
        out.put("referenceIdentity", original.getReferenceIdentity());
        out.put("attributionRevision", original.getAttributionRevision());
        out.put("identical", identical);
        out.put("originalCells", a.size());
        out.put("replayedCells", b.size());
        out.put("mismatches", mismatches);
        out.put("note", "按原用途/口径版本/归属修订复演，旧报表不随新批次改变");
        return out;
    }
}
