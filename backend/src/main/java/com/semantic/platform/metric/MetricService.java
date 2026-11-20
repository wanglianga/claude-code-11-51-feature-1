package com.semantic.platform.metric;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantic.platform.dsl.Expr;
import com.semantic.platform.dsl.ExprValidator;
import com.semantic.platform.dsl.MetricType;
import com.semantic.platform.semantics.PurposeProfile;
import com.semantic.platform.semantics.PurposeProfileRepository;
import com.semantic.platform.semantics.RefundSemantics;
import com.semantic.platform.semantics.RefundSemanticsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 指标服务：定义编辑、校验（含环路与用途语义）、发布（组合重检）、影响分析。
 *
 * 退款时间口径采用“用途参数模板”方案：
 * <ul>
 *   <li>直接引用 {@code refund_attributions} 的定义是用途模板（purposeRequired=true），运行必须传 purpose；</li>
 *   <li>下游指标引用模板时必须写明 {@code purpose}（financial / order_batch），
 *       从而固定成“财务发生净销售额/运营订单净销售额”两个独立、可复算的定义；</li>
 *   <li>只有对账用途的派生指标可以同时引用两种用途，用于守恒/复算，不能作为业务序列展示。</li>
 * </ul>
 */
@Service
public class MetricService {

    public static final String PURPOSE_FINANCIAL = ExprValidator.PURPOSE_FINANCIAL;
    public static final String PURPOSE_ORDER_BATCH = ExprValidator.PURPOSE_ORDER_BATCH;
    public static final String PURPOSE_RECONCILIATION = ExprValidator.PURPOSE_RECONCILIATION;
    public static final List<String> BOUND_PURPOSES = List.of(PURPOSE_FINANCIAL, PURPOSE_ORDER_BATCH);

    private final MetricDefRepository defRepo;
    private final MetricVersionRepository versionRepo;
    private final MetricEdgeRepository edgeRepo;
    private final PurposeProfileRepository purposeProfileRepo;
    private final RefundSemanticsRepository semanticsRepo;
    private final MetricReleaseRepository releaseRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    public MetricService(MetricDefRepository defRepo, MetricVersionRepository versionRepo,
                         MetricEdgeRepository edgeRepo, PurposeProfileRepository purposeProfileRepo,
                         RefundSemanticsRepository semanticsRepo, MetricReleaseRepository releaseRepo) {
        this.defRepo = defRepo;
        this.versionRepo = versionRepo;
        this.edgeRepo = edgeRepo;
        this.purposeProfileRepo = purposeProfileRepo;
        this.semanticsRepo = semanticsRepo;
        this.releaseRepo = releaseRepo;
    }

    public Expr parseExpr(String definitionJson) {
        try {
            return mapper.readValue(definitionJson, Expr.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("定义 JSON 解析失败: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // 语义目录：按用途编译类型/依赖图
    // ------------------------------------------------------------------

    /** 已发布指标元信息（用途模板判定 + 已绑定用途 + 固定口径版本）。 */
    public record VersionInfo(String metricKey, Integer version, Expr expr, String grain,
                              boolean purposeRequired, String boundPurpose, Long semanticsId) {}

    public List<VersionInfo> publishedInfos() {
        List<VersionInfo> out = new ArrayList<>();
        for (MetricVersion v : versionRepo.findByStatus("published")) {
            try {
                Expr expr = parseExpr(v.getDefinitionJson());
                boolean required = usesRefundAttribution(expr) || v.getPurposeRequired();
                out.add(new VersionInfo(v.getMetricKey(), v.getVersion(), expr,
                        v.getGrain() == null ? "day" : v.getGrain(), required,
                        v.getBoundPurpose(), pinnedSemantics(v.getMetricKey(), v.getVersion(), v.getBoundPurpose())));
            } catch (Exception ignored) { }
        }
        return out;
    }

    /** 指标定义是否直接使用退款归属来源（用途模板）。 */
    public boolean usesRefundAttribution(Expr expr) {
        return definitionText(expr).contains("\"" + ExprValidator.SOURCE_REFUND_ATTRIBUTIONS + "\"");
    }

    private String definitionText(Expr expr) {
        try {
            return mapper.writeValueAsString(expr);
        } catch (Exception e) {
            return "";
        }
    }

    /** 用途绑定指标的传递闭包：直接或间接依赖退款用途模板的指标。 */
    public Set<String> purposeBoundMetrics(List<VersionInfo> infos) {
        Set<String> bound = new LinkedHashSet<>();
        for (VersionInfo vi : infos) if (vi.purposeRequired()) bound.add(vi.metricKey());
        boolean changed = true;
        while (changed) {
            changed = false;
            for (VersionInfo vi : infos) {
                if (bound.contains(vi.metricKey())) continue;
                for (String ref : ExprValidator.collectRefs(vi.expr())) {
                    if (bound.contains(ref)) {
                        bound.add(vi.metricKey());
                        changed = true;
                        break;
                    }
                }
            }
        }
        return bound;
    }

    /** 对账派生指标：显式引用两种用途做独立复算。 */
    public Set<String> reconciliationMetrics(List<VersionInfo> infos) {
        Set<String> out = new LinkedHashSet<>();
        for (VersionInfo vi : infos) {
            Set<String> refPurposes = new LinkedHashSet<>();
            refPurposes.addAll(refPurposes(vi.expr()));
            if (refPurposes.contains(PURPOSE_FINANCIAL) && refPurposes.contains(PURPOSE_ORDER_BATCH)) {
                out.add(vi.metricKey());
            }
        }
        return out;
    }

    private Set<String> refPurposes(Expr e) {
        Set<String> out = new HashSet<>();
        if (e instanceof Expr.Ref r) {
            if (r.purpose() != null) out.add(r.purpose());
        } else if (e instanceof Expr.Agg a) {
            out.addAll(refPurposes(a.expr()));
        } else if (e instanceof Expr.Arith a) {
            out.addAll(refPurposes(a.left()));
            out.addAll(refPurposes(a.right()));
        } else if (e instanceof Expr.Convert c) {
            out.addAll(refPurposes(c.expr()));
        } else if (e instanceof Expr.Cohort c) {
            out.addAll(refPurposes(c.base()));
        } else if (e instanceof Expr.If i) {
            out.addAll(refPurposes(i.cond()));
            out.addAll(refPurposes(i.then()));
            out.addAll(refPurposes(i.otherwise()));
        }
        return out;
    }

    /** 已发布版本固定引用的口径版本：来自发布清单（按用途或最近一次发布）。 */
    private Long pinnedSemantics(String metricKey, Integer version, String purpose) {
        if (purpose != null) {
            java.util.Optional<MetricRelease> byPurpose =
                    releaseRepo.findFirstByMetricKeyAndPurposeOrderByReleasedAtDesc(metricKey, purpose);
            if (byPurpose.isPresent()) return byPurpose.get().getSemanticsId();
        }
        return releaseRepo.findFirstByMetricKeyOrderByReleasedAtDesc(metricKey)
                .map(MetricRelease::getSemanticsId).orElse(null);
    }

    /** 已发布指标在其服务用途列表上的发布清单（模板指标两种用途都有清单）。 */
    public List<MetricRelease> releaseManifests(String metricKey) {
        return releaseRepo.findByMetricKeyOrderByReleasedAtDesc(metricKey);
    }

    public Optional<PurposeProfile> profile(String purpose) {
        return purpose == null ? Optional.empty() : purposeProfileRepo.findByPurpose(purpose);
    }

    /**
     * 按用途编译已发布指标的类型表。purpose 为 null 时以模板哨兵编译（允许两种用途并存），
     * 但每个退款引用仍须写明用途。
     */
    public Map<String, MetricType> publishedTypes(String purpose) {
        List<VersionInfo> infos = publishedInfos();
        Set<String> bound = purposeBoundMetrics(infos);
        Set<String> recon = reconciliationMetrics(infos);
        Map<String, Expr> exprs = new HashMap<>();
        Map<String, String> grains = new HashMap<>();
        for (VersionInfo vi : infos) {
            exprs.put(vi.metricKey(), vi.expr());
            grains.put(vi.metricKey(), vi.grain());
        }
        Map<String, MetricType> types = new HashMap<>();
        String compilePurpose = purpose == null ? ExprValidator.PURPOSE_TEMPLATE : purpose;
        for (int iter = 0; iter < exprs.size() + 1 && types.size() < exprs.size(); iter++) {
            for (Map.Entry<String, Expr> en : exprs.entrySet()) {
                String key = en.getKey();
                if (types.containsKey(key)) continue;
                Set<String> refs = ExprValidator.collectRefs(en.getValue());
                if (!types.keySet().containsAll(refs)) continue;
                MetricType.TimeGrain declaredGrain = "month".equals(grains.get(key))
                        ? MetricType.TimeGrain.MONTH : MetricType.TimeGrain.DAY;
                ExprValidator validator = new ExprValidator(types, Map.of(), declaredGrain,
                        compilePurpose, Map.of(), bound, recon);
                ExprValidator.Result res = new ExprValidator.Result();
                MetricType inferred = validator.infer(en.getValue(), "$", res);
                types.put(key, new MetricType(inferred.kind(), inferred.currency(), declaredGrain, inferred.dims()));
            }
        }
        return types;
    }

    public Map<String, MetricType> publishedTypes() {
        return publishedTypes(null);
    }

    /** 已发布指标的依赖图（按用途过滤）。 */
    public Map<String, Set<String>> publishedRefs() {
        Map<String, Set<String>> refs = new HashMap<>();
        List<VersionInfo> infos = publishedInfos();
        Set<String> bound = purposeBoundMetrics(infos);
        for (VersionInfo vi : infos) {
            for (Expr.Ref r : allRefs(vi.expr())) {
                // 引用退款模板且显式写了用途 -> 依赖边按用途区分，避免两种口径被当成同一依赖。
                String purpose = r.purpose() != null ? r.purpose()
                        : (bound.contains(r.metric()) ? ExprValidator.PURPOSE_TEMPLATE : null);
                refs.computeIfAbsent(vi.metricKey(), k -> new HashSet<>()).add(r.metric());
            }
        }
        return refs;
    }

    private List<Expr.Ref> allRefs(Expr e) {
        List<Expr.Ref> out = new ArrayList<>();
        collectRefs(e, out);
        return out;
    }

    private void collectRefs(Expr e, List<Expr.Ref> out) {
        if (e instanceof Expr.Ref r) {
            out.add(r);
        } else if (e instanceof Expr.Agg a) {
            collectRefs(a.expr(), out);
        } else if (e instanceof Expr.Arith a) {
            collectRefs(a.left(), out);
            collectRefs(a.right(), out);
        } else if (e instanceof Expr.Convert c) {
            collectRefs(c.expr(), out);
        } else if (e instanceof Expr.Cohort c) {
            collectRefs(c.base(), out);
        } else if (e instanceof Expr.If i) {
            collectRefs(i.cond(), out);
            collectRefs(i.then(), out);
            collectRefs(i.otherwise(), out);
        }
    }

    // ------------------------------------------------------------------
    // 校验 / 保存 / 发布
    // ------------------------------------------------------------------

    public Map<String, Object> validate(String metricKey, String definitionJson) {
        return validateWithGrain(metricKey, definitionJson, null);
    }

    public Map<String, Object> validateWithGrain(String metricKey, String definitionJson, String grain) {
        return validateForPurpose(metricKey, definitionJson, grain, null);
    }

    /**
     * 校验定义。purpose 为 null 时按“用途参数模板”校验：
     * 允许出现两种用途，但每个退款引用必须写明用途，否则下游无法固定引用身份。
     */
    public Map<String, Object> validateForPurpose(String metricKey, String definitionJson, String grain,
                                                  String purpose) {
        Map<String, Object> out = new LinkedHashMap<>();
        Expr expr;
        try {
            expr = parseExpr(definitionJson);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("errors", List.of(Map.of("path", "$", "message", "JSON 解析失败: " + e.getMessage())));
            return out;
        }
        MetricType.TimeGrain currentGrain = "month".equals(grain) ? MetricType.TimeGrain.MONTH : MetricType.TimeGrain.DAY;
        List<VersionInfo> infos = publishedInfos();
        Set<String> bound = purposeBoundMetrics(infos);
        Set<String> recon = reconciliationMetrics(infos);
        String compilePurpose = purpose == null ? ExprValidator.PURPOSE_TEMPLATE : purpose;
        ExprValidator validator = new ExprValidator(publishedTypes(compilePurpose), publishedRefs(),
                currentGrain, compilePurpose, Map.of(), bound, recon);

        List<Map<String, String>> errors = new ArrayList<>();
        List<String> cycle = validator.detectCycle(metricKey, expr);
        if (!cycle.isEmpty()) {
            errors.add(Map.of("path", "$refs", "message", "检测到引用环路: " + String.join(" -> ", cycle)));
        }
        ExprValidator.Result res = new ExprValidator.Result();
        MetricType type = validator.infer(expr, "$", res);
        res.errors.forEach(i -> errors.add(Map.of("path", i.path(), "message", i.message())));

        boolean purposeRequired = usesRefundAttribution(expr);
        boolean boundMetric = purposeRequired || ExprValidator.collectRefs(expr).stream().anyMatch(bound::contains);
        Set<String> usedPurposes = refPurposes(expr);
        String binding = purpose;
        if (binding == null) {
            if (usedPurposes.size() == 1) binding = usedPurposes.iterator().next();
        }
        // 绑定用途的指标必须唯一且明确；对账指标例外（需同时引用两种用途）。
        if (boundMetric && usedPurposes.isEmpty()) {
            binding = null; // 直接使用归属来源的模板：运行期必须传用途
        }
        // 下游派生定义不能只按无用途的模板名引用：引用用途绑定指标的每个 Ref 必须写明用途。
        if (!purposeRequired && !PURPOSE_RECONCILIATION.equals(binding)) {
            for (Expr.Ref r : allRefs(expr)) {
                if (bound.contains(r.metric()) && (r.purpose() == null || r.purpose().isBlank())) {
                    errors.add(Map.of("path", "$refs", "message",
                            "引用退款用途指标 '" + r.metric() + "' 时必须显式写明用途（financial / order_batch），"
                                    + "下游不能只按无用途的模板名引用"));
                }
            }
        }
        if (usedPurposes.size() > 1 && !PURPOSE_RECONCILIATION.equals(binding)) {
            errors.add(Map.of("path", "$refs",
                    "message", "一个指标同时引用了 financial 与 order_batch 两种退款用途，只能作为对账（reconciliation）派生指标，"
                            + "不能作为业务序列；请拆分为两个用途定义"));
        }
        out.put("ok", errors.isEmpty());
        out.put("errors", errors);
        out.put("inferredType", type.describe());
        out.put("refs", ExprValidator.collectRefs(expr));
        out.put("purposeRequired", purposeRequired);
        out.put("referencedPurposes", usedPurposes);
        out.put("bindingPurpose", binding);
        return out;
    }

    /** 保存草稿（创建新版本）。purpose 为绑定的用途（可为空 = 用途模板）。 */
    @Transactional
    public MetricVersion saveDraft(String metricKey, String name, String description, String grain,
                                   String definitionJson, String createdBy, String purpose) {
        Map<String, Object> v = validateForPurpose(metricKey, definitionJson, grain, purpose);
        if (!(Boolean) v.get("ok")) {
            throw new IllegalArgumentException("定义校验失败: " + v.get("errors"));
        }
        MetricDef def = defRepo.findByMetricKey(metricKey).orElseGet(() -> {
            MetricDef d = new MetricDef();
            d.setMetricKey(metricKey);
            d.setName(name);
            d.setDescription(description);
            d.setGrain(grain);
            d.setStatus("draft");
            return defRepo.save(d);
        });
        def.setName(name);
        def.setDescription(description);
        def.setGrain(grain);
        def.setUpdatedAt(java.time.OffsetDateTime.now());
        defRepo.save(def);

        int nextVersion = versionRepo.findByMetricKeyOrderByVersionDesc(metricKey).stream()
                .mapToInt(MetricVersion::getVersion).max().orElse(0) + 1;
        MetricVersion ver = new MetricVersion();
        ver.setMetricKey(metricKey);
        ver.setVersion(nextVersion);
        ver.setDefinitionJson(definitionJson);
        ver.setGrain(grain);
        ver.setStatus("draft");
        ver.setPurposeRequired(Boolean.TRUE.equals(v.get("purposeRequired")));
        Object binding = v.get("bindingPurpose");
        ver.setBoundPurpose(binding == null ? null : binding.toString());
        ver.setDefinitionHash(hash(definitionJson));
        ver.setCreatedBy(createdBy);
        return versionRepo.save(ver);
    }

    public MetricVersion saveDraft(String metricKey, String name, String description, String grain,
                                   String definitionJson, String createdBy) {
        return saveDraft(metricKey, name, description, grain, definitionJson, createdBy, null);
    }

    /**
     * 发布指标版本。在最终组合（已发布 + 本版本）上重新检查环路与用途语义，
     * 防止并发草稿各自预览通过却同时生效成环或把两种用途混成一个序列。
     */
    @Transactional
    public MetricVersion publish(String metricKey, Integer version, String publishedBy) {
        MetricVersion ver = versionRepo.findByMetricKeyAndVersion(metricKey, version)
                .orElseThrow(() -> new IllegalArgumentException("版本不存在: " + metricKey + "@" + version));
        if ("published".equals(ver.getStatus())) {
            return ver;
        }
        Expr expr = parseExpr(ver.getDefinitionJson());
        String purpose = ver.getBoundPurpose();
        if (purpose == null) {
            Set<String> used = refPurposes(expr);
            if (used.size() == 1) purpose = used.iterator().next();
        }
        String compilePurpose = purpose == null ? ExprValidator.PURPOSE_TEMPLATE : purpose;

        Map<String, Set<String>> graph = new HashMap<>(publishedRefs());
        graph.put(metricKey, ExprValidator.collectRefs(expr));
        MetricType.TimeGrain currentGrain = "month".equals(ver.getGrain()) ? MetricType.TimeGrain.MONTH : MetricType.TimeGrain.DAY;
        List<VersionInfo> infos = publishedInfos();
        Set<String> bound = purposeBoundMetrics(infos);
        Set<String> recon = reconciliationMetrics(infos);
        ExprValidator validator = new ExprValidator(publishedTypes(compilePurpose), graph, currentGrain,
                compilePurpose, Map.of(), bound, recon);
        List<String> cycle = validator.detectCycle(metricKey, expr);
        if (!cycle.isEmpty()) {
            throw new IllegalStateException("发布被拒绝：最终组合检测到引用环路 " + String.join(" -> ", cycle));
        }
        ExprValidator.Result res = new ExprValidator.Result();
        validator.infer(expr, "$", res);
        if (!res.ok()) {
            throw new IllegalStateException("发布被拒绝：定义校验失败 " + res.errors);
        }

        versionRepo.findFirstByMetricKeyAndStatusOrderByVersionDesc(metricKey, "published")
                .ifPresent(old -> { old.setStatus("deprecated"); versionRepo.save(old); });
        ver.setStatus("published");
        ver.setPublishedAt(java.time.OffsetDateTime.now());
        MetricVersion saved = versionRepo.save(ver);

        MetricDef def = defRepo.findByMetricKey(metricKey).orElseThrow();
        def.setStatus("published");
        def.setGrain(ver.getGrain());
        def.setUpdatedAt(java.time.OffsetDateTime.now());
        defRepo.save(def);

        // 发布清单：固定用途 + 口径版本 + 当前归属修订。
        // 用途模板指标同时登记其下游可能固定引用的用途，保证运行/审批/报表引用同一口径版本。
        recordRelease(saved, purpose, publishedBy);
        if (purpose == null) {
            for (String p : BOUND_PURPOSES) recordRelease(saved, p, publishedBy);
        }

        // 重建依赖边（按用途分子键）
        edgeRepo.deleteByFromMetricAndFromVersion(metricKey, version);
        edgeRepo.flush();
        for (Expr.Ref r : allRefs(expr)) {
            MetricEdge edge = new MetricEdge();
            edge.setFromMetric(metricKey);
            edge.setToMetric(r.metric());
            edge.setFromVersion(version);
            edge.setPurpose(r.purpose() != null ? r.purpose()
                    : (bound.contains(r.metric()) ? ExprValidator.PURPOSE_TEMPLATE : null));
            edgeRepo.save(edge);
        }
        return saved;
    }

    private void recordRelease(MetricVersion ver, String purpose, String releasedBy) {
        String releasePurpose = purpose == null ? "__none__" : purpose;
        RefundSemantics sem = semanticsRepo
                .findFirstByCodeAndStatusOrderByVersionDesc("REFUND_TIME", "published").orElse(null);
        PurposeProfile profile = profilePurpose(releasePurpose);
        releaseRepo.deleteByMetricKeyAndVersionAndPurpose(ver.getMetricKey(), ver.getVersion(), releasePurpose);
        releaseRepo.flush();
        MetricRelease rel = new MetricRelease();
        rel.setMetricKey(ver.getMetricKey());
        rel.setVersion(ver.getVersion());
        rel.setPurpose(releasePurpose);
        rel.setReleasedBy(releasedBy);
        if (sem != null) {
            rel.setSemanticsId(sem.getId());
            rel.setSemanticsCode(sem.getCode());
            rel.setSemanticsVersion(sem.getVersion());
        }
        if (profile != null) {
            rel.setProfileVersion(profile.getProfileVersion());
            rel.setReferenceIdentity(sem == null ? null
                    : purpose + "/" + sem.refIdentity() + "/profile@v" + profile.getProfileVersion());
            rel.setNote("用途 " + purpose + " 绑定 " + sem.refIdentity());
        } else {
            rel.setNote("未绑定退款用途（定义模板或非退款指标）");
        }
        releaseRepo.save(rel);
    }

    private PurposeProfile profilePurpose(String purpose) {
        if (purpose == null || "reconciliation".equals(purpose)) return null;
        return purposeProfileRepo.findByPurpose(purpose).orElse(null);
    }

    public List<MetricRelease> releases(String metricKey) {
        return releaseRepo.findByMetricKeyOrderByReleasedAtDesc(metricKey);
    }

    /** 已发布指标在指定用途下的发布清单（口径身份的唯一真源）。 */
    public MetricRelease releasedFor(String metricKey, String purpose) {
        if (purpose != null) {
            java.util.Optional<MetricRelease> byPurpose =
                    releaseRepo.findFirstByMetricKeyAndPurposeOrderByReleasedAtDesc(metricKey, purpose);
            if (byPurpose.isPresent()) return byPurpose.get();
        }
        return releaseRepo.findFirstByMetricKeyOrderByReleasedAtDesc(metricKey).orElse(null);
    }

    @Transactional
    public Map<String, Object> withdraw(String metricKey, String withdrawnBy) {
        MetricVersion published = versionRepo
                .findFirstByMetricKeyAndStatusOrderByVersionDesc(metricKey, "published")
                .orElseThrow(() -> new IllegalArgumentException("指标未发布: " + metricKey));
        Map<String, Object> impact = impact(metricKey);
        published.setStatus("withdrawn");
        versionRepo.save(published);
        MetricDef def = defRepo.findByMetricKey(metricKey).orElseThrow();
        def.setStatus("deprecated");
        def.setUpdatedAt(java.time.OffsetDateTime.now());
        defRepo.save(def);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("metricKey", metricKey);
        out.put("withdrawnVersion", published.getVersion());
        out.put("blockedDownstream", impact.get("downstreamMetrics"));
        out.put("note", "已撤回；引用该指标的下游指标新运行将被阻止，历史报告仍绑定原运行");
        return out;
    }

    // ------------------------------------------------------------------
    // 影响分析
    // ------------------------------------------------------------------

    public Map<String, Object> impact(String metricKey) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Set<String>> reverse = new HashMap<>();
        for (MetricEdge e : edgeRepo.findAll()) {
            reverse.computeIfAbsent(e.getToMetric(), k -> new HashSet<>()).add(e.getFromMetric());
        }
        Set<String> downstream = new HashSet<>();
        List<String> queue = new ArrayList<>(List.of(metricKey));
        while (!queue.isEmpty()) {
            String cur = queue.remove(0);
            for (String d : reverse.getOrDefault(cur, Set.of())) {
                if (downstream.add(d)) queue.add(d);
            }
        }
        // 下游按用途分组（依赖边记录了固定用途）
        Map<String, List<String>> downstreamByPurpose = new LinkedHashMap<>();
        for (MetricEdge e : edgeRepo.findAll()) {
            if (downstream.contains(e.getFromMetric()) && e.getPurpose() != null) {
                downstreamByPurpose.computeIfAbsent(e.getPurpose(), k -> new ArrayList<>()).add(e.getFromMetric());
            }
        }
        out.put("downstreamMetrics", downstream);
        out.put("downstreamByPurpose", downstreamByPurpose);
        Expr expr = currentPublishedExpr(metricKey);
        out.put("upstreamMetrics", expr == null ? Set.of() : ExprValidator.collectRefs(expr));
        out.put("upstreamRefs", expr == null ? List.of() : allRefs(expr).stream()
                .map(r -> r.purpose() == null ? r.metric() : r.metric() + "#" + r.purpose()).toList());
        return out;
    }

    private Expr currentPublishedExpr(String metricKey) {
        return versionRepo.findFirstByMetricKeyAndStatusOrderByVersionDesc(metricKey, "published")
                .map(v -> parseExpr(v.getDefinitionJson()))
                .orElse(null);
    }

    private String hash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return s;
        }
    }

    public List<MetricDef> listAll() { return defRepo.findAll(); }

    public List<MetricVersion> versions(String metricKey) {
        return versionRepo.findByMetricKeyOrderByVersionDesc(metricKey);
    }

    public MetricVersion publishedVersion(String metricKey) {
        return versionRepo.findFirstByMetricKeyAndStatusOrderByVersionDesc(metricKey, "published")
                .orElseThrow(() -> new IllegalArgumentException("指标未发布: " + metricKey));
    }

    /** 已发布指标的口径版本固定情况（用于发布清单/报表一致性）。 */
    public Map<String, String> publishedSemantics() {
        Map<String, String> out = new LinkedHashMap<>();
        for (MetricVersion v : versionRepo.findByStatus("published")) {
            RefundSemantics sem = semanticsRepo.findFirstByCodeAndStatusOrderByVersionDesc(
                    "REFUND_TIME", "published").orElse(null);
            if (sem != null) out.put(v.getMetricKey(), sem.refIdentity());
        }
        return out;
    }
}
