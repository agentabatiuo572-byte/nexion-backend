package ffdd.opsconsole.device.web;

import ffdd.opsconsole.device.application.TaskPoolService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/devices")
@RequiredArgsConstructor
public class TaskPoolController {
    private final TaskPoolService pools;
    @GetMapping("/task-pools")
    @PreAuthorize("hasAuthority('device_e2_read')")
    public ApiResult<List<Map<String,Object>>> list(@RequestParam(defaultValue="DEVICE")String kind) {return ApiResult.ok(pools.list(kind));}
    @GetMapping("/task-pools/{code}/tasks")
    @PreAuthorize("hasAuthority('device_e2_read')")
    public ApiResult<PageResult<Map<String,Object>>> tasks(@PathVariable String code,@RequestParam(required=false)String taskClass,
            @RequestParam(required=false)Boolean enabled,@RequestParam(defaultValue="1")int pageNum,@RequestParam(defaultValue="50")int pageSize) {
        return ApiResult.ok(pools.tasks(code,taskClass,enabled,pageNum,pageSize));
    }
    @PostMapping("/task-pools/{code}/random-tasks")
    @PreAuthorize("hasAuthority('device_e2_write')")
    public ApiResult<Map<String,Object>> random(@PathVariable String code,@RequestBody TaskPoolService.RandomAdd body) {return pools.randomAdd(code,body);}
    @PostMapping("/task-pools/{code}/tasks/status")
    @PreAuthorize("hasAuthority('device_e2_write')")
    public ApiResult<Map<String,Object>> status(@PathVariable String code,@RequestBody TaskPoolService.Status body) {return pools.status(code,body);}
    @GetMapping("/task-pools/{code}/disable-impact")
    @PreAuthorize("hasAuthority('device_e2_read')")
    public ApiResult<Map<String,Object>> impact(@PathVariable String code,@RequestParam List<String> taskTypeIds) {return ApiResult.ok(pools.disableImpact(code,taskTypeIds));}
    @GetMapping("/sku-datacenter-labels")
    @PreAuthorize("hasAuthority('device_e1_read')")
    public ApiResult<List<String>> labels() {return ApiResult.ok(pools.datacenterLabels());}
}
