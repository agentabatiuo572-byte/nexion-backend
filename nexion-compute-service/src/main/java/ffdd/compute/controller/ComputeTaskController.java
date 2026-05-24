package ffdd.compute.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import ffdd.compute.domain.ComputeReceipt;
import ffdd.compute.domain.ComputeTask;
import ffdd.compute.dto.TaskAckRequest;
import ffdd.compute.dto.TaskCompleteRequest;
import ffdd.compute.dto.TaskDispatchRequest;
import ffdd.compute.dto.TaskFailRequest;
import ffdd.compute.dto.TaskMaintenanceResult;
import ffdd.compute.dto.TaskQueryRequest;
import ffdd.compute.dto.WorkerTaskLeaseRequest;
import ffdd.compute.dto.WorkerTaskLeaseResponse;
import ffdd.compute.service.ComputeService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/compute/tasks")
public class ComputeTaskController {
    private final ComputeService computeService;

    public ComputeTaskController(ComputeService computeService) {
        this.computeService = computeService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_COMPUTE_READ')")
    public ApiResult<PageResult<ComputeTask>> page(TaskQueryRequest request) {
        return ApiResult.ok(computeService.pageTasks(request));
    }

    @PostMapping("/dispatch")
    @PreAuthorize("hasAuthority('PERM_COMPUTE_WRITE')")
    public ApiResult<ComputeTask> dispatch(@Valid @RequestBody TaskDispatchRequest request) {
        return ApiResult.ok(computeService.dispatchTask(request));
    }

    @PostMapping("/worker/lease")
    @PreAuthorize("hasAuthority('PERM_COMPUTE_WRITE')")
    public ApiResult<WorkerTaskLeaseResponse> leaseWorkerTask(@Valid @RequestBody WorkerTaskLeaseRequest request) {
        return ApiResult.ok(computeService.leaseNextWorkerTask(request));
    }

    @PostMapping("/{taskNo}/ack")
    @PreAuthorize("hasAuthority('PERM_COMPUTE_WRITE')")
    public ApiResult<ComputeTask> ack(
            @PathVariable String taskNo,
            @Valid @RequestBody TaskAckRequest request) {
        return ApiResult.ok(computeService.ackTask(taskNo, request));
    }

    @PostMapping("/{taskNo}/complete")
    @PreAuthorize("hasAuthority('PERM_COMPUTE_WRITE')")
    public ApiResult<ComputeReceipt> complete(
            @PathVariable String taskNo,
            @Valid @RequestBody TaskCompleteRequest request) {
        return ApiResult.ok(computeService.completeTask(taskNo, request));
    }

    @PostMapping("/{taskNo}/fail")
    @PreAuthorize("hasAuthority('PERM_COMPUTE_WRITE')")
    public ApiResult<ComputeTask> fail(
            @PathVariable String taskNo,
            @Valid @RequestBody TaskFailRequest request) {
        return ApiResult.ok(computeService.failTask(taskNo, request));
    }

    @PostMapping("/maintenance/timeouts")
    @PreAuthorize("hasAuthority('PERM_COMPUTE_WRITE')")
    public ApiResult<TaskMaintenanceResult> processTimeouts(@RequestParam(required = false) Integer limit) {
        return ApiResult.ok(computeService.processTaskTimeouts(limit == null ? 20 : limit));
    }

    @PostMapping("/maintenance/retries")
    @PreAuthorize("hasAuthority('PERM_COMPUTE_WRITE')")
    public ApiResult<TaskMaintenanceResult> retryDue(@RequestParam(required = false) Integer limit) {
        return ApiResult.ok(computeService.retryDueTasks(limit == null ? 20 : limit));
    }
}
