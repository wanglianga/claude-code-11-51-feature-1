package com.semantic.platform.api;

import com.semantic.platform.run.Run;
import com.semantic.platform.run.RunCell;
import com.semantic.platform.run.RunCellLineage;
import com.semantic.platform.run.RunService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final RunService runService;

    public RunController(RunService runService) {
        this.runService = runService;
    }

    /**
     * 执行运行。purpose 必须是请求显式给出的 financial / order_batch（或仅校验用的 reconciliation）；
     * 未选择用途的请求会被拒绝，不默认为最近使用分支。
     */
    @PostMapping
    public Run execute(@RequestBody Map<String, Object> body) {
        String metricKey = (String) body.get("metricKey");
        Long versionId = Long.valueOf(body.get("metricVersionId").toString());
        LocalDate from = LocalDate.parse((String) body.get("from"));
        LocalDate to = LocalDate.parse((String) body.get("to"));
        String grain = (String) body.getOrDefault("grain", "day");
        String scope = body.get("scope") == null ? null : body.get("scope").toString();
        String purpose = body.get("purpose") == null ? null : body.get("purpose").toString();
        Long semanticsId = body.get("semanticsId") == null ? null : Long.valueOf(body.get("semanticsId").toString());
        Integer revision = body.get("attributionRevision") == null ? null
                : Integer.valueOf(body.get("attributionRevision").toString());
        return runService.execute(metricKey, versionId, from, to, grain, scope, purpose, semanticsId, revision);
    }

    @GetMapping
    public List<Run> list(@RequestParam(required = false) String metricKey) {
        return runService.listRuns(metricKey);
    }

    @GetMapping("/{id}")
    public Run get(@PathVariable Long id) {
        return runService.getRun(id);
    }

    @GetMapping("/{id}/cells")
    public List<RunCell> cells(@PathVariable Long id) {
        return runService.cells(id);
    }

    /** 按原输入复演（用途/口径版本/归属修订固定）。 */
    @PostMapping("/{id}/replay")
    public Map<String, Object> replay(@PathVariable Long id) {
        return runService.replay(id);
    }

    @GetMapping("/{id}/lineage")
    public List<RunCellLineage> lineage(@PathVariable Long id, @RequestParam String cellKey) {
        return runService.lineage(id, cellKey);
    }
}
