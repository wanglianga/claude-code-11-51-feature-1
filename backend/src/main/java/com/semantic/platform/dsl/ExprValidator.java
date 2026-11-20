package com.semantic.platform.dsl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 指标定义校验器。
 * 顺序：1) 引用环路检测  2) 字段存在性  3) 类型/币种/粒度规则。
 * 所有错误以中文、带定位信息返回。
 */
public class ExprValidator {

    public record Issue(String path, String message) {
        @Override public String toString() { return path + ": " + message; }
    }

    public static class Result {
        public final List<Issue> errors = new ArrayList<>();
        public MetricType inferredType;

        public boolean ok() { return errors.isEmpty(); }

        public void error(String path, String msg) { errors.add(new Issue(path, msg)); }
    }

    /** 已发布指标的类型表（metric_key -> type），用于校验 ref。 */
    private final Map<String, MetricType> publishedTypes;
    /** 已发布指标的依赖图（metric_key -> 引用的 metric_key 集合），用于环路检测。 */
    private final Map<String, Set<String>> publishedRefs;
    /** 当前指标的声明粒度（day/month），用于粒度一致性校验。 */
    private final MetricType.TimeGrain currentGrain;
    /**
     * 当前编译上下文的退款用途。null = 未绑定用途（定义模板/仅保存草稿），
     * 此时不允许引用“退款时间口径”来源，必须先固定 financial / order_batch。
     */
    private final String purpose;
    /** 口径版本一致性：用途 -> 口径版本（来自已发布指标），编译期拒绝跨版本的混用。 */
    private final Map<String, String> publishedSemantics;
    /** 指标 -> 是否退款用途绑定（防止未选择用途就引用无用途模板名）。 */
    private final Set<String> purposeBoundMetrics;
    /** 对账指标：只有对账运行允许引用两种用途做独立复算。 */
    private final Set<String> reconciliationMetrics;

    public static final String PURPOSE_FINANCIAL = "financial";
    public static final String PURPOSE_ORDER_BATCH = "order_batch";
    public static final String PURPOSE_RECONCILIATION = "reconciliation";
    public static final String SOURCE_REFUND_ATTRIBUTIONS = "refund_attributions";
    /** 用途模板的编译哨兵：允许两种用途共存，但每个退款引用仍必须写明用途。 */
    public static final String PURPOSE_TEMPLATE = "__template__";

    public ExprValidator(Map<String, MetricType> publishedTypes,
                         Map<String, Set<String>> publishedRefs) {
        this(publishedTypes, publishedRefs, MetricType.TimeGrain.DAY);
    }

    public ExprValidator(Map<String, MetricType> publishedTypes,
                         Map<String, Set<String>> publishedRefs,
                         MetricType.TimeGrain currentGrain) {
        this(publishedTypes, publishedRefs, currentGrain, null, Map.of(), Set.of(), Set.of());
    }

    public ExprValidator(Map<String, MetricType> publishedTypes,
                         Map<String, Set<String>> publishedRefs,
                         MetricType.TimeGrain currentGrain,
                         String purpose,
                         Map<String, String> publishedSemantics,
                         Set<String> purposeBoundMetrics,
                         Set<String> reconciliationMetrics) {
        this.publishedTypes = publishedTypes;
        this.publishedRefs = publishedRefs;
        this.currentGrain = currentGrain;
        this.purpose = purpose;
        this.publishedSemantics = publishedSemantics;
        this.purposeBoundMetrics = purposeBoundMetrics;
        this.reconciliationMetrics = reconciliationMetrics;
    }

    /** 收集表达式中直接引用的指标 key。 */
    public static Set<String> collectRefs(Expr e) {
        Set<String> refs = new HashSet<>();
        collectRefs(e, refs);
        return refs;
    }

    private static void collectRefs(Expr e, Set<String> refs) {
        if (e instanceof Expr.Ref r) {
            refs.add(r.metric());
        } else if (e instanceof Expr.Agg a) {
            collectRefs(a.expr(), refs);
        } else if (e instanceof Expr.Arith a) {
            collectRefs(a.left(), refs);
            collectRefs(a.right(), refs);
        } else if (e instanceof Expr.Convert c) {
            collectRefs(c.expr(), refs);
        } else if (e instanceof Expr.Cohort c) {
            collectRefs(c.base(), refs);
        } else if (e instanceof Expr.If i) {
            collectRefs(i.cond(), refs);
            collectRefs(i.then(), refs);
            collectRefs(i.otherwise(), refs);
        }
    }

    /**
     * 环路检测：把“当前指标 -> 其引用”加入已发布图，DFS 判环。
     * 返回构成环的路径（若有）。
     */
    public List<String> detectCycle(String selfKey, Expr expr) {
        Map<String, Set<String>> graph = new HashMap<>(publishedRefs);
        graph.put(selfKey, collectRefs(expr));

        // DFS 三色标记
        Map<String, Integer> state = new HashMap<>(); // 0=未访问 1=访问中 2=完成
        Deque<String> stack = new ArrayDeque<>();
        List<String> cycle = new ArrayList<>();
        for (String node : graph.keySet()) {
            if (dfsCycle(node, graph, state, stack, cycle)) break;
        }
        return cycle;
    }

    private boolean dfsCycle(String node, Map<String, Set<String>> graph,
                             Map<String, Integer> state, Deque<String> stack, List<String> cycle) {
        Integer s = state.getOrDefault(node, 0);
        if (s == 1) {
            // 找到环：栈底到栈顶是 DFS 路径，环 = 从 node 到栈顶再回到 node
            List<String> pathFromBottom = new ArrayList<>();
            java.util.Iterator<String> it = stack.descendingIterator(); // 从底到顶
            while (it.hasNext()) pathFromBottom.add(it.next());
            int idx = pathFromBottom.indexOf(node);
            if (idx >= 0) {
                cycle.addAll(pathFromBottom.subList(idx, pathFromBottom.size()));
            } else {
                cycle.addAll(pathFromBottom);
            }
            cycle.add(node);
            return true;
        }
        if (s == 2) return false;
        state.put(node, 1);
        stack.push(node);
        for (String next : graph.getOrDefault(node, Set.of())) {
            if (dfsCycle(next, graph, state, stack, cycle)) return true;
        }
        stack.pop();
        state.put(node, 2);
        return false;
    }

    /** 类型推断 + 校验。path 用于错误定位。 */
    public MetricType infer(Expr e, String path, Result result) {
        if (e instanceof Expr.Field f) {
            return inferField(f, path, result);
        } else if (e instanceof Expr.Const c) {
            // 常量视为无币种、无维度、粒度 NONE 的数量
            return MetricType.quantity(MetricType.TimeGrain.NONE, Set.of());
        } else if (e instanceof Expr.Agg a) {
            return inferAgg(a, path, result);
        } else if (e instanceof Expr.Arith a) {
            return inferArith(a, path, result);
        } else if (e instanceof Expr.Convert c) {
            return inferConvert(c, path, result);
        } else if (e instanceof Expr.Ref r) {
            return inferRef(r, path, result);
        } else if (e instanceof Expr.Cohort c) {
            return inferCohort(c, path, result);
        } else if (e instanceof Expr.If i) {
            return inferIf(i, path, result);
        }
        result.error(path, "未知表达式节点");
        return MetricType.bool();
    }

    private MetricType inferField(Expr.Field f, String path, Result r) {
        if (!SourceRegistry.hasSource(f.source())) {
            r.error(path, "未知来源 '" + f.source() + "'，可用：" + SourceRegistry.sourceNames());
            return MetricType.bool();
        }
        SourceRegistry.FieldDef def = SourceRegistry.field(f.source(), f.field());
        if (def == null) {
            r.error(path, "来源 '" + f.source() + "' 不存在字段 '" + f.field() + "'");
            return MetricType.bool();
        }
        // 原始退款事实没有“用途”：必须先经退款时间口径归属，禁止直接按原始入账日聚合。
        if ("refunds".equals(f.source())) {
            r.error(path, "原始退款事实没有时间口径，禁止直接聚合。请使用来源 '" + SOURCE_REFUND_ATTRIBUTIONS
                    + "' 并显式绑定用途（financial 按入账日 / order_batch 按原交易日）");
        }
        // 归属来源必须固定用途；未选择用途时拒绝编译。
        if (SOURCE_REFUND_ATTRIBUTIONS.equals(f.source()) && (purpose == null || purpose.isBlank())) {
            r.error(path, "退款时间口径指标必须固定用途参数 financial 或 order_batch，不能只引用无用途的模板名");
        }
        // 字段的时间粒度继承当前指标的声明粒度
        return new MetricType(def.kind(), def.currency(), currentGrain, Set.of());
    }

    private MetricType inferAgg(Expr.Agg a, String path, Result r) {
        MetricType inner = infer(a.expr(), path + ".expr", r);
        // 校验过滤条件
        String currencyFilter = null;
        if (a.filter() != null) {
            for (int i = 0; i < a.filter().size(); i++) {
                Filter fl = a.filter().get(i);
                if (!SourceRegistry.hasSource(fl.source())) {
                    r.error(path + ".filter[" + i + "]", "未知来源 '" + fl.source() + "'");
                } else if (!SourceRegistry.hasField(fl.source(), fl.field())) {
                    r.error(path + ".filter[" + i + "]", "来源 '" + fl.source() + "' 不存在字段 '" + fl.field() + "'");
                }
                if (!fl.isValidOperator()) {
                    r.error(path + ".filter[" + i + "]", "非法过滤操作符 '" + fl.operator() + "'");
                }
                // 提取币种过滤值
                if ("currency".equals(fl.field()) && "eq".equals(fl.operator())) {
                    currencyFilter = String.valueOf(fl.value());
                }
            }
        }
        Set<String> dims = a.by() == null ? Set.of() : new HashSet<>(a.by());
        String measure = a.measure();
        // 金额聚合的币种：过滤单一币种则为该币种；若来源含币种字段（每记录币种）未过滤则混合（告警）
        String resultCurrency = inner.currency();
        if (inner.isAmount()) {
            boolean hasCurrencyField = a.expr() instanceof Expr.Field f && SourceRegistry.hasField(f.source(), "currency");
            if (currencyFilter != null) {
                resultCurrency = currencyFilter;
            } else if (hasCurrencyField) {
                r.error(path, "金额字段含每记录币种，未过滤时混合多币种无法直接聚合；请加 currency 过滤或 convert 换算");
                resultCurrency = "MIXED";
            }
            // 否则按字段声明的默认币种（如 CNY）
        }
        switch (measure) {
            case "sum", "min", "max" -> {
                if (!inner.isAmount() && !inner.isQuantity()) {
                    r.error(path, "聚合 " + measure + " 仅支持金额或数量，得到 " + inner.describe());
                }
                return new MetricType(inner.kind(), resultCurrency, currentGrain, dims);
            }
            case "avg" -> {
                if (!inner.isAmount() && !inner.isQuantity()) {
                    r.error(path, "avg 仅支持金额或数量，得到 " + inner.describe());
                }
                return new MetricType(inner.kind(), resultCurrency, currentGrain, dims);
            }
            case "count", "count_distinct" -> {
                return MetricType.quantity(currentGrain, dims);
            }
            default -> {
                r.error(path, "未知微聚合方式 '" + measure + "'");
                return MetricType.bool();
            }
        }
    }

    private MetricType inferArith(Expr.Arith a, String path, Result r) {
        // 常量广播：一侧是 const 时，结果类型取另一侧
        if (a.left() instanceof Expr.Const) {
            MetricType rt = infer(a.right(), path + ".right", r);
            return rt;
        }
        if (a.right() instanceof Expr.Const) {
            return infer(a.left(), path + ".left", r);
        }
        MetricType l = infer(a.left(), path + ".left", r);
        MetricType rt = infer(a.right(), path + ".right", r);
        String op = a.operator();
        switch (op) {
            case "add", "sub" -> {
                if (!l.addableWith(rt)) {
                    if (l.isAmount() && rt.isAmount()
                            && l.currency() != null && rt.currency() != null
                            && !l.currency().equals(rt.currency())) {
                        r.error(path, "不同币种不能直接相加/相减：" + l.currency() + " 与 " + rt.currency()
                                + "，请先用 convert 显式换算");
                    } else if (l.timeGrain() != rt.timeGrain()
                            && l.timeGrain() != MetricType.TimeGrain.NONE
                            && rt.timeGrain() != MetricType.TimeGrain.NONE) {
                        r.error(path, "时间粒度不一致不能逐行运算：" + l.timeGrain() + " 与 " + rt.timeGrain());
                    } else if (!l.dims().equals(rt.dims())) {
                        r.error(path, "分组维度不一致不能逐行运算：" + l.dims() + " 与 " + rt.dims());
                    } else {
                        r.error(path, "类型不一致不能相加/相减：" + l.describe() + " 与 " + rt.describe());
                    }
                }
                return l;
            }
            case "mul" -> {
                // 数量*金额=金额；数量*数量=数量；金额*金额无意义；粒度必须一致
                if (l.timeGrain() != rt.timeGrain()
                        && l.timeGrain() != MetricType.TimeGrain.NONE
                        && rt.timeGrain() != MetricType.TimeGrain.NONE) {
                    r.error(path, "时间粒度不一致不能逐行相乘：" + l.timeGrain() + " 与 " + rt.timeGrain()
                            + "（如门店日指标不能与门店月指标逐行相乘）");
                }
                if (l.isAmount() && rt.isAmount()) {
                    r.error(path, "金额与金额不能相乘（量纲错误）");
                }
                if (l.isAmount()) return l;
                if (rt.isAmount()) return rt;
                return MetricType.quantity(l.timeGrain(), l.dims().isEmpty() ? rt.dims() : l.dims());
            }
            case "div" -> {
                // 金额/数量=单价（金额）；金额/金额=比率；数量/数量=比率
                if (l.timeGrain() != rt.timeGrain()
                        && l.timeGrain() != MetricType.TimeGrain.NONE
                        && rt.timeGrain() != MetricType.TimeGrain.NONE) {
                    r.error(path, "时间粒度不一致不能逐行相除：" + l.timeGrain() + " 与 " + rt.timeGrain());
                }
                if (l.isAmount() && rt.isQuantity()) {
                    return MetricType.amount(l.currency(), l.timeGrain(), l.dims());
                }
                if ((l.isAmount() && rt.isAmount()) || (l.isQuantity() && rt.isQuantity())) {
                    return MetricType.ratio(l.timeGrain(), l.dims());
                }
                if (l.isQuantity() && rt.isAmount()) {
                    r.error(path, "数量除以金额无业务含义");
                }
                return MetricType.ratio(l.timeGrain(), l.dims());
            }
            default -> {
                r.error(path, "未知运算符 '" + op + "'");
                return MetricType.bool();
            }
        }
    }

    private MetricType inferConvert(Expr.Convert c, String path, Result r) {
        MetricType inner = infer(c.expr(), path + ".expr", r);
        if (!inner.isAmount()) {
            r.error(path, "convert 仅用于金额，得到 " + inner.describe());
        }
        if (c.fromCurrency() == null || c.toCurrency() == null) {
            r.error(path, "convert 必须指定 fromCurrency 与 toCurrency");
        }
        if (c.rate() <= 0) {
            r.error(path, "convert 汇率必须为正数");
        }
        return MetricType.amount(c.toCurrency(), inner.timeGrain(), inner.dims());
    }

    private MetricType inferRef(Expr.Ref ref, String path, Result r) {
        MetricType t = publishedTypes.get(ref.metric());
        if (t == null) {
            r.error(path, "引用的指标 '" + ref.metric() + "' 不存在或未发布");
            return MetricType.bool();
        }
        boolean targetBound = purposeBoundMetrics.contains(ref.metric());
        String refPurpose = ref.purpose();
        if (refPurpose != null && !refPurpose.isBlank()) {
            if (!PURPOSE_FINANCIAL.equals(refPurpose) && !PURPOSE_ORDER_BATCH.equals(refPurpose)) {
                r.error(path, "引用用途只能是 financial 或 order_batch，得到 '" + refPurpose + "'");
                return t;
            }
            if (!targetBound) {
                r.error(path, "指标 '" + ref.metric() + "' 不是退款用途绑定指标，不能指定用途参数");
            }
            if (knownPurposeMismatch(refPurpose)) {
                r.error(path, "同一表达式/消费端不能混用不同退款用途"
                        + (purpose == null ? "" : "（当前用途 " + purpose + "，引用用途 " + refPurpose + "）")
                        + "：两种口径不可比，请拆成两个指标");
            }
        }
        // 未写用途的引用继承运行用途（用途参数模板）；运行期若未选择用途会在 RunService 被拒绝，
        // 报表发布也要求每项写明用途与口径引用身份，因此下游无法只按无用途模板名消费。
        return t;
    }

    /** 当前用途与引用用途是否冲突；对账运行允许同时引用两种用途。 */
    private boolean knownPurposeMismatch(String refPurpose) {
        if (purpose == null || purpose.isBlank()) return false;
        if (PURPOSE_RECONCILIATION.equals(purpose) || PURPOSE_TEMPLATE.equals(purpose)) return false;
        return !purpose.equals(refPurpose);
    }

    private MetricType inferCohort(Expr.Cohort c, String path, Result r) {
        MetricType base = infer(c.base(), path + ".base", r);
        if (!base.isAmount() && !base.isQuantity()) {
            r.error(path, "同店增长的基指标必须是金额或数量，得到 " + base.describe());
        }
        if (!"prev_year".equals(c.compare()) && !"prev_period".equals(c.compare())) {
            r.error(path, "cohort.compare 仅支持 prev_year | prev_period");
        }
        // 同店增长结果是比率
        return MetricType.ratio(base.timeGrain(), base.dims());
    }

    private MetricType inferIf(Expr.If i, String path, Result r) {
        MetricType cond = infer(i.cond(), path + ".cond", r);
        MetricType then = infer(i.then(), path + ".then", r);
        MetricType other = infer(i.otherwise(), path + ".otherwise", r);
        if (!then.addableWith(other)) {
            r.error(path, "if 两个分支类型不一致：" + then.describe() + " 与 " + other.describe());
        }
        return then;
    }
}
