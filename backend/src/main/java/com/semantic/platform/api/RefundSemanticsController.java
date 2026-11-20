package com.semantic.platform.api;

import com.semantic.platform.metric.MetricService;
import com.semantic.platform.semantics.AttributionRevision;
import com.semantic.platform.semantics.PurposeProfile;
import com.semantic.platform.semantics.RefundAttribution;
import com.semantic.platform.semantics.RefundAttributionService;
import com.semantic.platform.semantics.RefundSemantics;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 退款时间口径 API：口径版本、用途档案（含并发修改）、归属修订、待归属与守恒校验。
 */
@RestController
@RequestMapping("/api/refund-semantics")
public class RefundSemanticsController {

    private final RefundAttributionService attributionService;
    private final MetricService metricService;

    public RefundSemanticsController(RefundAttributionService attributionService, MetricService metricService) {
        this.attributionService = attributionService;
        this.metricService = metricService;
    }

    /** 口径版本列表与当前发布版本。 */
    @GetMapping
    public Map<String, Object> list() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", RefundAttributionService.DEFAULT_SEMANTICS_CODE);
        out.put("versions", attributionService.semanticsVersions());
        try {
            RefundSemantics published = attributionService.publishedSemantics();
            out.put("published", Map.of(
                    "id", published.getId(),
                    "ref", published.refIdentity(),
                    "rules", attributionService.parseRules(published.getRulesJson()),
                    "revision", attributionService.latestRevision(published.getId())));
        } catch (Exception e) {
            out.put("published", null);
            out.put("note", e.getMessage());
        }
        return out;
    }

    /** 用途档案：口径版本、适用用户、profile 版本（并发修改凭据）。 */
    @GetMapping("/purposes")
    public List<PurposeProfile> purposes() {
        return attributionService.profiles();
    }

    /** 查询计划/消费端固定引用身份：用途 -> 口径 -> profile -> 归属修订。 */
    @GetMapping("/identity")
    public Map<String, Object> identity(@RequestParam String purpose) {
        PurposeProfile profile = attributionService.profile(purpose);
        RefundSemantics sem = attributionService.semantics(profile.getSemanticsId());
        Integer revision = attributionService.latestRevision(sem.getId());
        return attributionService.purposeIdentity(profile, sem, revision);
    }

    /** 发布新的退款口径版本（先草稿后发布；示例用）。 */
    @PostMapping
    public RefundSemantics publishVersion(@RequestBody Map<String, Object> body,
                                          @RequestAttribute(name = "user", required = false) String user) {
        RefundSemantics sem = new RefundSemantics();
        sem.setCode(RefundAttributionService.DEFAULT_SEMANTICS_CODE);
        List<RefundSemantics> versions = attributionService.semanticsVersions();
        sem.setVersion(versions.isEmpty() ? 1 : versions.get(0).getVersion() + 1);
        sem.setStatus("published");
        sem.setRulesJson(body.get("rules") == null ? "{}" : body.get("rules").toString());
        sem.setNote(body.get("note") == null ? null : body.get("note").toString());
        sem.setCreatedBy(user == null ? "system" : user);
        sem.setPublishedAt(java.time.OffsetDateTime.now());
        // 旧版本下线
        versions.stream().filter(v -> "published".equals(v.getStatus())).forEach(v -> {
            v.setStatus("deprecated");
        });
        return attributionServiceSaved(sem);
    }

    private RefundSemantics attributionServiceSaved(RefundSemantics sem) {
        return attributionService.saveSemantics(sem);
    }

    /**
     * 修改用途绑定（切换到另一口径版本或更新适用用户）。
     * 必须携带 expectedProfileVersion；并发修改时返回 409，不静默覆盖。
     */
    @PutMapping("/purposes/{purpose}")
    public PurposeProfile updatePurpose(@PathVariable String purpose, @RequestBody Map<String, Object> body,
                                        @RequestAttribute(name = "user", required = false) String user) {
        Integer expected = body.get("expectedProfileVersion") == null ? null
                : Integer.valueOf(body.get("expectedProfileVersion").toString());
        Long semanticsId = body.get("semanticsId") == null ? null : Long.valueOf(body.get("semanticsId").toString());
        return attributionService.updatePurpose(purpose,
                semanticsId,
                (String) body.get("label"),
                (String) body.get("applicableUsers"),
                expected, user == null ? "system" : user);
    }

    /** 归属修订列表（旧修订保留用于复演）。 */
    @GetMapping("/revisions")
    public List<AttributionRevision> revisions(@RequestParam(required = false) Long semanticsId) {
        RefundSemantics sem = semanticsId == null ? attributionService.publishedSemantics()
                : attributionService.semantics(semanticsId);
        return attributionService.revisions(sem.getId());
    }

    /** 重建归属修订（依据当前退款事实追加新修订）。 */
    @PostMapping("/revisions")
    public AttributionRevision rebuild(@RequestBody(required = false) Map<String, Object> body,
                                       @RequestAttribute(name = "user", required = false) String user) {
        Long semanticsId = body == null || body.get("semanticsId") == null ? null
                : Long.valueOf(body.get("semanticsId").toString());
        RefundSemantics sem = semanticsId == null ? attributionService.publishedSemantics()
                : attributionService.semantics(semanticsId);
        return attributionService.rebuild(sem.getId(), user == null ? "system" : user);
    }

    /** 某归属修订的明细（可按用途过滤）。 */
    @GetMapping("/attributions")
    public List<RefundAttribution> attributions(@RequestParam(required = false) Long semanticsId,
                                                @RequestParam(required = false) Integer revision,
                                                @RequestParam(required = false) String purpose) {
        RefundSemantics sem = semanticsId == null ? attributionService.publishedSemantics()
                : attributionService.semantics(semanticsId);
        Integer rev = revision == null ? attributionService.latestRevision(sem.getId()) : revision;
        return purpose == null ? attributionService.rows(sem.getId(), rev)
                : attributionService.rows(sem.getId(), rev, purpose);
    }

    /** 待归属金额与清单（按用途与入账期间）。 */
    @GetMapping("/pending")
    public Map<String, Object> pending(@RequestParam(required = false) Long semanticsId,
                                       @RequestParam(required = false) Integer revision) {
        RefundSemantics sem = semanticsId == null ? attributionService.publishedSemantics()
                : attributionService.semantics(semanticsId);
        Integer rev = revision == null ? attributionService.latestRevision(sem.getId()) : revision;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("semanticsId", sem.getId());
        out.put("semanticsRef", sem.refIdentity());
        out.put("revision", rev);
        out.put("byPurpose", attributionService.pendingByPurposeAndPeriod(sem.getId(), rev));
        out.put("rows", attributionService.pending(sem.getId(), rev,
                RefundAttributionService.PURPOSE_FINANCIAL));
        return out;
    }

    /** 守恒校验：订单=订单行、退款=财务口径、财务口径=运营归属+待归属。 */
    @GetMapping("/conservation")
    public Map<String, Object> conservation(@RequestParam(required = false) Long semanticsId,
                                            @RequestParam(required = false) Integer revision) {
        RefundSemantics sem = semanticsId == null ? attributionService.publishedSemantics()
                : attributionService.semantics(semanticsId);
        Integer rev = revision == null ? attributionService.latestRevision(sem.getId()) : revision;
        return attributionService.conservation(sem.getId(), rev);
    }

    /** 期间内两用途的归属结果对照。 */
    @GetMapping("/period-comparison")
    public Map<String, Object> periodComparison(@RequestParam String from, @RequestParam String to,
                                                @RequestParam(required = false) Long semanticsId,
                                                @RequestParam(required = false) Integer revision) {
        RefundSemantics sem = semanticsId == null ? attributionService.publishedSemantics()
                : attributionService.semantics(semanticsId);
        Integer rev = revision == null ? attributionService.latestRevision(sem.getId()) : revision;
        LocalDate f = LocalDate.parse(from);
        LocalDate t = LocalDate.parse(to);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("semanticsRef", sem.refIdentity());
        out.put("revision", rev);
        for (String purpose : MetricService.BOUND_PURPOSES) {
            List<RefundAttribution> rows = attributionService.inPeriod(sem.getId(), rev, purpose, f, t);
            out.put(purpose, Map.of(
                    "amount", rows.stream().map(RefundAttribution::getAmountSigned)
                            .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add),
                    "refunds", rows.stream().map(RefundAttribution::getRefundId).distinct().toList(),
                    "rows", rows));
        }
        out.put("pendingByPurpose", attributionService.pendingByPurposeAndPeriod(sem.getId(), rev));
        return out;
    }
}
