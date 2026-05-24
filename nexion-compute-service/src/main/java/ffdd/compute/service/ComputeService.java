package ffdd.compute.service;

import ffdd.common.api.PageResult;
import ffdd.compute.domain.ComputeReceipt;
import ffdd.compute.domain.ComputeTask;
import ffdd.compute.domain.UserDevice;
import ffdd.compute.dto.DeviceActivateRequest;
import ffdd.compute.dto.DeviceQueryRequest;
import ffdd.compute.dto.ReceiptCreateRequest;
import ffdd.compute.dto.ReceiptQueryRequest;
import ffdd.compute.dto.TaskAckRequest;
import ffdd.compute.dto.TaskCompleteRequest;
import ffdd.compute.dto.TaskDispatchRequest;
import ffdd.compute.dto.TaskFailRequest;
import ffdd.compute.dto.TaskMaintenanceResult;
import ffdd.compute.dto.TaskQueryRequest;
import ffdd.compute.dto.WorkerTaskLeaseRequest;
import ffdd.compute.dto.WorkerTaskLeaseResponse;
import java.util.List;

public interface ComputeService {
    PageResult<UserDevice> pageDevices(DeviceQueryRequest request);

    UserDevice getDevice(Long id);

    List<UserDevice> activateDevices(DeviceActivateRequest request);

    PageResult<ComputeTask> pageTasks(TaskQueryRequest request);

    ComputeTask dispatchTask(TaskDispatchRequest request);

    WorkerTaskLeaseResponse leaseNextWorkerTask(WorkerTaskLeaseRequest request);

    ComputeTask ackTask(String taskNo, TaskAckRequest request);

    ComputeReceipt completeTask(String taskNo, TaskCompleteRequest request);

    ComputeTask failTask(String taskNo, TaskFailRequest request);

    TaskMaintenanceResult processTaskTimeouts(int limit);

    TaskMaintenanceResult retryDueTasks(int limit);

    ComputeReceipt createReceipt(ReceiptCreateRequest request);

    ComputeReceipt settleReceiptEarnings(Long receiptId);

    PageResult<ComputeReceipt> pageReceipts(ReceiptQueryRequest request);
}
