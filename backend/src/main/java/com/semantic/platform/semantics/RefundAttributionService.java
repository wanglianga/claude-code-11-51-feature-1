package com.semantic.platform.semantics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantic.platform.source.OrderFact;
import com.semantic.platform.source.OrderFactRepository;
import com.semantic.platform.source.OrderLine;
import com.semantic.platform.source.OrderLineRepository;
import com.semantic.platform.source.RefundFact;
import com.semantic.platform.source.RefundFactRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 退款归属引擎。同一笔退款事实在两个用途下产生两行归属：
 * <ul>
 *   <li>financial：attribution_date = 实际入账日，进入退款发生期间；</li>
 *   <li>order_batch：attribution_date = 原交易日，只修订对应订单批次；</li>
 * </ul>
 * 无法可靠关联到原订单行的退款，两个用途都记为 pending（attribution_date = NULL），
 * 绝不按同名商品、当前价格或订单号猜测回填。
 *
 * 每次退款晚到 / 退款更正 / 原订单行更正都会生成新的归属修订；
 * 旧修订原样保留，旧运行按原修订复演。
 */
@Service
public class RefundAttributionService {

    public static final String PURPOSE_FINANCIAL = "financial";
    public static final String PURPOSE_ORDER_BATCH = "order_batch";
    public static final String PURPOSE_RECONCILIATION = "reconciliation";
    public static final String DEFAULT_SEMANTICS_CODE = "REFUND_TIME";

    private static final List<String> ATTRIBUTED_PURPOSES = List.of(PURPOSE_FINANCIAL, PURPOSE_ORDER_BATCH);

    private final RefundSemanticsRepository semanticsRepo;
    private final PurposeProfileRepository profileRepo;
    private final RefundAttributionRepository attributionRepo;
    private final AttributionRevisionRepository revisionRepo;
    private final RefundFactRepository refundRepo;
    private final OrderFactRepository orderRepo;
    private final OrderLineRepository orderLineRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    public RefundAttributionService(RefundSemanticsRepository semanticsRepo,
                                    PurposeProfileRepository profileRepo,
                                    RefundAttributionRepository attributionRepo,
                                    AttributionRevisionRepository revisionRepo,
                                    RefundFactRepository refundRepo,
                                    OrderFactRepository orderRepo,
                                    OrderLineRepository orderLineRepo) {
        this.semanticsRepo = semanticsRepo;
        this.profileRepo = profileRepo;
        this.attributionRepo = attributionRepo;
        this.revisionRepo = revisionRepo;
        this.refundRepo = refundRepo;
        this.orderRepo = orderRepo;
        this.orderLineRepo = orderLineRepo;
    }

    // ------------------------------------------------------------------
    // 口径版本与用途档案
    // ------------------------------------------------------------------

    public RefundSemantics publishedSemantics() {
        return semanticsRepo.findFirstByCodeAndStatusOrderByVersionDesc(DEFAULT_SEMANTICS_CODE, "published")
                .orElseThrow(() -> new IllegalStateException(
                        "退款时间口径未发布：请先发布 REFUND_TIME 口径版本，再执行用途绑定的运行"));
    }

    public RefundSemantics semantics(Long id) {
        return semanticsRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("退款口径版本不存在: " + id));
    }

    public PurposeProfile profile(String purpose) {
        if (purpose == null || purpose.isBlank()) {
            throw new IllegalArgumentException(
                    "未选择用途：请求必须显式指定 purpose=financial 或 order_batch（对账用途仅用于派生校验），"
                            + "不默认最近使用分支");
        }
        return profileRepo.findByPurpose(purpose)
                .orElseThrow(() -> new IllegalArgumentException(
                        "未知用途 '" + purpose + "'，可用：financial（财务发生）/ order_batch（运营订单）/ reconciliation（对账）"));
    }

    /** 用途身份（label / applicable users / 口径版本 / 归属修订），用于查询计划、缓存键、报表发布固定引用。 */
    public Map<String, Object> purposeIdentity(PurposeProfile profile, RefundSemantics sem, Integer revision) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("purpose", profile.getPurpose());
        out.put("purposeLabel", profile.getLabel());
        out.put("applicableUsers", profile.getApplicableUsers());
        out.put("profileVersion", profile.getProfileVersion());
        out.put("semanticsId", sem.getId());
        out.put("semanticsCode", sem.getCode());
        out.put("semanticsVersion", sem.getVersion());
        out.put("attributionRevision", revision);
        out.put("purposeRef", referenceIdentity(profile.getPurpose(), sem, profile.getProfileVersion(), revision));
        return out;
    }

    public static String referenceIdentity(String purpose, RefundSemantics sem,
                                           Integer profileVersion, Integer revision) {
        return purpose + "/" + sem.refIdentity() + "/profile@v" + profileVersion + "/attrRev@" + revision;
    }

    public List<PurposeProfile> profiles() {
        return profileRepo.findAll().stream()
                .sorted(Comparator.comparing(PurposeProfile::getPurpose))
                .toList();
    }

    public List<RefundSemantics> semanticsVersions() {
        return semanticsRepo.findByCodeOrderByVersionDesc(DEFAULT_SEMANTICS_CODE);
    }

    @Transactional
    public RefundSemantics saveSemantics(RefundSemantics sem) {
        return semanticsRepo.save(sem);
    }

    public List<AttributionRevision> revisions(Long semanticsId) {
        return revisionRepo.findBySemanticsIdOrderByRevisionDesc(semanticsId);
    }

    /**
     * 修改用途绑定。带乐观并发：expectedProfileVersion 与当前不符时抛 409，
     * 并发修改用途不会被静默合并（运行、审批、报表引用的仍是修改前身份）。
     */
    @Transactional
    public PurposeProfile updatePurpose(String purpose, Long semanticsId, String label,
                                        String applicableUsers, Integer expectedProfileVersion,
                                        String updatedBy) {
        PurposeProfile profile = profileRepo.findByPurpose(purpose)
                .orElseThrow(() -> new IllegalArgumentException("未知用途: " + purpose));
        if (expectedProfileVersion != null && !expectedProfileVersion.equals(profile.getProfileVersion())) {
            throw new IllegalStateException("用途 '" + purpose + "' 已被并发修改：期望 profile v"
                    + expectedProfileVersion + "，当前 v" + profile.getProfileVersion() + "，请刷新后重试");
        }
        if (semanticsId != null) {
            RefundSemantics sem = semantics(semanticsId);
            if (!"published".equals(sem.getStatus())) {
                throw new IllegalStateException("只能绑定已发布的退款口径版本: " + sem.refIdentity());
            }
            profile.setSemanticsId(semanticsId);
        }
        if (label != null) profile.setLabel(label);
        if (applicableUsers != null) profile.setApplicableUsers(applicableUsers);
        profile.setProfileVersion(profile.getProfileVersion() + 1);
        profile.setUpdatedBy(updatedBy);
        profile.setUpdatedAt(java.time.OffsetDateTime.now());
        return profileRepo.save(profile);
    }

    // ------------------------------------------------------------------
    // 归属修订
    // ------------------------------------------------------------------

    /**
     * 依据当前退款事实重建某口径版本的归属，生成新修订（追加，不覆盖历史修订）。
     */
    @Transactional
    public AttributionRevision rebuild(Long semanticsId, String createdBy) {
        RefundSemantics sem = semantics(semanticsId);
        List<RefundFact> refunds = refundRepo.findAll();
        Map<String, OrderLine> lineByLineId = new LinkedHashMap<>();
        for (OrderLine line : orderLineRepo.findAll()) lineByLineId.put(line.getLineId(), line);
        Map<String, OrderFact> orderByOrderId = new LinkedHashMap<>();
        for (OrderFact order : orderRepo.findAll()) orderByOrderId.put(order.getOrderId(), order);

        String digest = digest(refunds, lineByLineId, orderByOrderId);
        Integer max = revisionRepo.findFirstBySemanticsIdOrderByRevisionDesc(semanticsId)
                .map(AttributionRevision::getRevision).orElse(0);
        AttributionRevision latest = revisionRepo.findBySemanticsIdAndRevision(semanticsId, max).orElse(null);
        if (latest != null && digest.equals(latest.getInputDigest())) {
            // 输入未变化：不制造空修订
            return latest;
        }
        int revision = max + 1;

        List<RefundAttribution> rows = new ArrayList<>();
        for (RefundFact refund : refunds) {
            Resolved resolved = resolve(refund, lineByLineId, orderByOrderId);
            for (String purpose : ATTRIBUTED_PURPOSES) {
                rows.add(toAttribution(sem, revision, purpose, refund, resolved));
            }
        }
        attributionRepo.saveAll(rows);

        AttributionRevision rev = new AttributionRevision();
        rev.setSemanticsId(semanticsId);
        rev.setRevision(revision);
        rev.setInputDigest(digest);
        rev.setSummaryJson(toJson(summary(sem, revision, rows)));
        rev.setCreatedBy(createdBy);
        return revisionRepo.save(rev);
    }

    public Integer latestRevision(Long semanticsId) {
        return revisionRepo.findFirstBySemanticsIdOrderByRevisionDesc(semanticsId)
                .map(AttributionRevision::getRevision).orElse(null);
    }

    private record Resolved(boolean linkable, OrderLine line, LocalDate originalDate, String reason) {}

    private Resolved resolve(RefundFact refund, Map<String, OrderLine> lines, Map<String, OrderFact> orders) {
        if ("unlinked".equals(refund.getLinkStatus())) {
            return new Resolved(false, null, null,
                    refund.getLinkNote() == null ? "来源标记为无法关联原订单行" : refund.getLinkNote());
        }
        OrderLine line = null;
        if (refund.getOrderLineId() != null && !refund.getOrderLineId().isBlank()) {
            line = lines.get(refund.getOrderLineId());
        }
        if (line == null) {
            return new Resolved(false, null, null,
                    "退款未提供可用的原订单行（不得按同名商品或当前价格猜测回填）");
        }
        // 原交易日以“原订单行”为准（订单行更正即修订订单批次）；仅当行缺日期时回退到退款登记值/订单日。
        LocalDate original = line.getBusinessDate();
        if (original == null) {
            original = refund.getOriginalBusinessDate() != null
                    ? refund.getOriginalBusinessDate()
                    : orders.getOrDefault(refund.getOrderId(), new OrderFact()).getBusinessDate();
        }
        if (original == null) {
            return new Resolved(false, null, null, "关联订单行缺少原交易日，无法追溯到订单批次");
        }
        return new Resolved(true, line, original, null);
    }

    private RefundAttribution toAttribution(RefundSemantics sem, int revision, String purpose,
                                            RefundFact refund, Resolved resolved) {
        int direction = refund.getDirection() == null ? 1 : refund.getDirection();
        BigDecimal signed = refund.getAmount().multiply(BigDecimal.valueOf(direction));
        RefundAttribution a = new RefundAttribution();
        a.setSemanticsId(sem.getId());
        a.setRevision(revision);
        a.setRefundId(refund.getRefundId());
        a.setOrderId(refund.getOrderId());
        a.setOrderLineId(resolved.line == null ? null : resolved.line.getLineId());
        a.setStoreId(refund.getStoreId());
        a.setPurpose(purpose);
        a.setPostingDate(refund.getBusinessDate());
        a.setOriginalBusinessDate(resolved.originalDate);
        a.setAmountSigned(signed);
        a.setQuantity(refund.getQuantity());
        a.setDirection(direction);
        a.setSku(refund.getSku() != null ? refund.getSku()
                : (resolved.line != null ? resolved.line.getSku() : null));
        a.setRefundSourceRevision(refund.getSourceRevision() == null ? 1 : refund.getSourceRevision());
        a.setLineSourceRevision(resolved.line == null ? null : resolved.line.getSourceRevision());
        if (!resolved.linkable) {
            a.setLinkStatus("unlinked");
            a.setAttributionStatus("pending");
            a.setAttributionDate(null);
            a.setReason(resolved.reason);
        } else {
            a.setLinkStatus("linked");
            a.setAttributionStatus("attributed");
            a.setAttributionDate(PURPOSE_FINANCIAL.equals(purpose) ? refund.getBusinessDate() : resolved.originalDate);
            a.setReason(PURPOSE_FINANCIAL.equals(purpose)
                    ? "按实际入账日 " + refund.getBusinessDate() + " 计入退款发生期间"
                    : "按原交易日 " + resolved.originalDate + " 追溯修订订单批次 " + resolved.line.getLineId());
        }
        return a;
    }

    private String digest(List<RefundFact> refunds, Map<String, OrderLine> lines, Map<String, OrderFact> orders) {
        StringBuilder sb = new StringBuilder();
        refunds.stream()
                .sorted(Comparator.comparing(RefundFact::getRefundId))
                .forEach(r -> sb.append(r.getRefundId()).append('|').append(r.getOrderId()).append('|')
                        .append(r.getOrderLineId()).append('|').append(r.getStoreId()).append('|')
                        .append(r.getBusinessDate()).append('|').append(r.getOriginalBusinessDate()).append('|')
                        .append(r.getAmount().stripTrailingZeros().toPlainString()).append('|')
                        .append(r.getDirection()).append('|').append(r.getQuantity()).append('|')
                        .append(r.getSku()).append('|').append(r.getLinkStatus()).append('|')
                        .append(r.getSourceRevision()).append('\n'));
        lines.values().stream()
                .sorted(Comparator.comparing(OrderLine::getLineId))
                .forEach(l -> sb.append('L').append(l.getLineId()).append('|').append(l.getOrderId()).append('|')
                        .append(l.getBusinessDate()).append('|').append(l.getSourceRevision()).append('\n'));
        orders.values().stream()
                .sorted(Comparator.comparing(OrderFact::getOrderId))
                .forEach(o -> sb.append('O').append(o.getOrderId()).append('|')
                        .append(o.getBusinessDate()).append('|').append(o.getSourceRevision()).append('\n'));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : h) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            return Integer.toHexString(sb.toString().hashCode());
        }
    }

    // ------------------------------------------------------------------
    // 查询 / 汇总
    // ------------------------------------------------------------------

    public List<RefundAttribution> rows(Long semanticsId, Integer revision) {
        return attributionRepo.findBySemanticsIdAndRevision(semanticsId, revision);
    }

    public List<RefundAttribution> rows(Long semanticsId, Integer revision, String purpose) {
        return attributionRepo.findBySemanticsIdAndRevisionAndPurpose(semanticsId, revision, purpose);
    }

    public List<RefundAttribution> pending(Long semanticsId, Integer revision, String purpose) {
        if (PURPOSE_RECONCILIATION.equals(purpose)) {
            // 对账口径的待归属：任一用途无法归属即列为待归属
            return rows(semanticsId, revision).stream()
                    .filter(a -> "pending".equals(a.getAttributionStatus()))
                    .filter(a -> PURPOSE_FINANCIAL.equals(a.getPurpose()))
                    .toList();
        }
        return attributionRepo.findBySemanticsIdAndRevisionAndPurposeAndAttributionStatus(
                semanticsId, revision, purpose, "pending");
    }

    /**
     * 期间内某用途的已归属行：用于求值。按两种用途各自的“归属日期”窗口过滤——
     * 财务发生看入账日，运营订单看原交易日（若窗口不同则取并集，引擎按 purpose 再筛）。
     */
    public List<RefundAttribution> periodRows(Long semanticsId, Integer revision, String purpose,
                                              LocalDate from, LocalDate to) {
        return attributionRepo
                .findBySemanticsIdAndRevisionAndPurposeAndAttributionStatusAndAttributionDateBetween(
                        semanticsId, revision, purpose, "attributed",
                        from.minusMonths(1), to.plusMonths(1));
    }

    /** 期间内参与某用途的归属行（只含 attributed，待归属绝不进入期间金额）。 */
    public List<RefundAttribution> inPeriod(Long semanticsId, Integer revision, String purpose,
                                            LocalDate from, LocalDate to) {
        return attributionRepo
                .findBySemanticsIdAndRevisionAndPurposeAndAttributionStatusAndAttributionDateBetween(
                        semanticsId, revision, purpose, "attributed", from, to);
    }

    public Map<String, Object> summary(RefundSemantics sem, Integer revision, List<RefundAttribution> rows) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("semanticsId", sem.getId());
        out.put("semanticsCode", sem.getCode());
        out.put("semanticsVersion", sem.getVersion());
        out.put("revision", revision);
        for (String purpose : ATTRIBUTED_PURPOSES) {
            List<RefundAttribution> purposeRows = rows.stream()
                    .filter(a -> purpose.equals(a.getPurpose())).toList();
            BigDecimal attributed = purposeRows.stream()
                    .filter(a -> "attributed".equals(a.getAttributionStatus()))
                    .map(RefundAttribution::getAmountSigned)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal pending = purposeRows.stream()
                    .filter(a -> "pending".equals(a.getAttributionStatus()))
                    .map(RefundAttribution::getAmountSigned)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            long pendingCount = purposeRows.stream()
                    .filter(a -> "pending".equals(a.getAttributionStatus())).count();
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("attributedAmount", attributed);
            p.put("pendingAmount", pending);
            p.put("pendingRefunds", pendingCount);
            p.put("totalAmount", attributed.add(pending));
            out.put(purpose, p);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 守恒校验：订单金额与退款事实
    // ------------------------------------------------------------------

    /**
     * 守恒校验（对某口径版本的归属修订）：
     * <ol>
     *   <li>订单金额 = 订单行金额合计（订单与订单行守恒）；</li>
     *   <li>退款金额 = 财务发生口径金额（含待归属）；</li>
     *   <li>财务发生口径金额 = 运营订单口径已归属金额 + 待归属金额（同源两用途不重不漏）。</li>
     * </ol>
     * 另列出“同商品不同价”的订单行，说明归属只看原订单行身份、不看同名商品现价。
     */
    public Map<String, Object> conservation(Long semanticsId, Integer revision) {
        RefundSemantics sem = semantics(semanticsId);
        List<RefundFact> refunds = refundRepo.findAll();
        List<OrderFact> orders = orderRepo.findAll();
        List<OrderLine> lines = orderLineRepo.findAll();
        List<RefundAttribution> all = rows(semanticsId, revision);

        BigDecimal refundFacts = refunds.stream()
                .map(r -> r.getAmount().multiply(BigDecimal.valueOf(r.getDirection() == null ? 1 : r.getDirection())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal financialAttributed = sumAttributed(all, PURPOSE_FINANCIAL);
        BigDecimal financialPending = sumPending(all, PURPOSE_FINANCIAL);
        BigDecimal financialTotal = financialAttributed.add(financialPending);
        BigDecimal orderBatchAttributed = sumAttributed(all, PURPOSE_ORDER_BATCH);
        BigDecimal orderBatchPending = sumPending(all, PURPOSE_ORDER_BATCH);
        BigDecimal pendingTotal = financialPending;

        // 订单金额 vs 订单行金额：仅对有订单行明细的订单比较
        Map<String, BigDecimal> lineSums = new LinkedHashMap<>();
        for (OrderLine l : lines) {
            lineSums.merge(l.getOrderId(), l.getAmount(), BigDecimal::add);
        }
        List<Map<String, Object>> orderMismatches = new ArrayList<>();
        for (OrderFact o : orders) {
            BigDecimal ls = lineSums.get(o.getOrderId());
            if (ls != null && ls.compareTo(o.getAmount()) != 0) {
                orderMismatches.add(Map.of("orderId", o.getOrderId(), "orderAmount", o.getAmount(),
                        "lineSum", ls, "diff", o.getAmount().subtract(ls)));
            }
        }

        // 同一 SKU 在不同订单行存在不同单价（用于证明归属不按当前价格/同名商品回填）
        Map<String, java.util.Set<BigDecimal>> priceBySku = new LinkedHashMap<>();
        for (OrderLine l : lines) {
            if (l.getSku() != null) {
                priceBySku.computeIfAbsent(l.getSku(), k -> new java.util.TreeSet<>()).add(l.getUnitPrice());
            }
        }
        List<Map<String, Object>> multiPriceSkus = new ArrayList<>();
        priceBySku.forEach((sku, prices) -> {
            if (prices.size() > 1) {
                multiPriceSkus.add(Map.of("sku", sku, "unitPrices", new ArrayList<>(prices)));
            }
        });

        boolean refundsBalanced = refundFacts.compareTo(financialTotal) == 0
                && refundFacts.compareTo(orderBatchAttributed.add(orderBatchPending)) == 0;
        boolean splitBalanced = financialTotal.compareTo(orderBatchAttributed.add(orderBatchPending)) == 0
                && financialPending.compareTo(orderBatchPending) == 0;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("semanticsId", sem.getId());
        out.put("semanticsCode", sem.getCode());
        out.put("semanticsVersion", sem.getVersion());
        out.put("revision", revision);
        out.put("refundFactsTotal", refundFacts);
        out.put("financialTotal", financialTotal);
        out.put("financialAttributed", financialAttributed);
        out.put("financialPending", financialPending);
        out.put("orderBatchAttributed", orderBatchAttributed);
        out.put("orderBatchPending", orderBatchPending);
        out.put("pendingTotal", pendingTotal);
        out.put("refundsBalanced", refundsBalanced);
        out.put("splitBalanced", splitBalanced);
        out.put("orderAmountMismatches", orderMismatches);
        out.put("sameSkuDifferentPrice", multiPriceSkus);
        out.put("allBalanced", refundsBalanced && splitBalanced && orderMismatches.isEmpty());
        return out;
    }

    /** 有未归属退款时，影响哪些用途与期间。 */
    public Map<String, Object> pendingByPurposeAndPeriod(Long semanticsId, Integer revision) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<RefundAttribution> all = rows(semanticsId, revision);
        for (String purpose : ATTRIBUTED_PURPOSES) {
            List<RefundAttribution> pending = all.stream()
                    .filter(a -> purpose.equals(a.getPurpose()))
                    .filter(a -> "pending".equals(a.getAttributionStatus()))
                    .toList();
            Map<String, BigDecimal> byPeriod = new LinkedHashMap<>();
            for (RefundAttribution a : pending) {
                String period = YearMonth.from(a.getPostingDate()).toString();
                byPeriod.merge(period, a.getAmountSigned(), BigDecimal::add);
            }
            out.put(purpose, Map.of(
                    "pendingAmount", pending.stream().map(RefundAttribution::getAmountSigned)
                            .reduce(BigDecimal.ZERO, BigDecimal::add),
                    "pendingRefunds", pending.stream().map(RefundAttribution::getRefundId).toList(),
                    "byPostingPeriod", byPeriod));
        }
        return out;
    }

    /**
     * 迟到更正：原订单行被更正后，比较最近两次归属修订，标出受影响的用途与期间。
     * 原交易日变更只影响运营订单口径的期间；财务发生口径按入账日不变。
     */
    public Map<String, Object> correctionsForLine(Long semanticsId, String orderLineId) {
        List<RefundAttribution> history = attributionRepo
                .findBySemanticsIdAndOrderLineId(semanticsId, orderLineId);
        List<Integer> revisions = history.stream().map(RefundAttribution::getRevision)
                .distinct().sorted(java.util.Comparator.reverseOrder()).toList();
        Integer latest = revisions.isEmpty() ? null : revisions.get(0);
        Integer previous = revisions.size() > 1 ? revisions.get(1) : null;
        List<Map<String, Object>> byPurpose = new ArrayList<>();
        for (String purpose : ATTRIBUTED_PURPOSES) {
            List<RefundAttribution> cur = history.stream()
                    .filter(a -> purpose.equals(a.getPurpose()) && a.getRevision().equals(latest)).toList();
            List<RefundAttribution> prev = previous == null ? List.of() : history.stream()
                    .filter(a -> purpose.equals(a.getPurpose()) && a.getRevision().equals(previous)).toList();
            java.util.function.Function<RefundAttribution, String> periodOf =
                    PURPOSE_ORDER_BATCH.equals(purpose)
                            ? a -> a.getOriginalBusinessDate() == null ? null : YearMonth.from(a.getOriginalBusinessDate()).toString()
                            : a -> a.getPostingDate() == null ? null : YearMonth.from(a.getPostingDate()).toString();
            Set<String> curPeriods = cur.stream().map(periodOf).filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            Set<String> prevPeriods = prev.stream().map(periodOf).filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            boolean changed = !curPeriods.equals(prevPeriods);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("purpose", purpose);
            p.put("affected", changed);
            p.put("periodsBefore", new ArrayList<>(prevPeriods));
            p.put("periodsAfter", new ArrayList<>(curPeriods));
            p.put("refunds", cur.stream().map(RefundAttribution::getRefundId).distinct().toList());
            p.put("reason", PURPOSE_ORDER_BATCH.equals(purpose)
                    ? "原交易日更正确认只修订订单批次期间" : "按实际入账日，订单行更正不改变财务发生期间");
            byPurpose.add(p);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderLineId", orderLineId);
        out.put("revisionBefore", previous);
        out.put("revisionAfter", latest);
        out.put("affectedPurposes", byPurpose);
        return out;
    }

    private BigDecimal sumPurpose(List<RefundAttribution> rows, String purpose) {
        return rows.stream().filter(a -> purpose.equals(a.getPurpose()))
                .map(RefundAttribution::getAmountSigned).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumAttributed(List<RefundAttribution> rows, String purpose) {
        return rows.stream().filter(a -> purpose.equals(a.getPurpose()))
                .filter(a -> "attributed".equals(a.getAttributionStatus()))
                .map(RefundAttribution::getAmountSigned).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumPending(List<RefundAttribution> rows, String purpose) {
        return rows.stream().filter(a -> purpose.equals(a.getPurpose()))
                .filter(a -> "pending".equals(a.getAttributionStatus()))
                .map(RefundAttribution::getAmountSigned).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    public JsonNode parseRules(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }
}
