package ffdd.compute.service;

import ffdd.common.api.PageResult;
import ffdd.compute.domain.ComputeReceipt;
import ffdd.compute.domain.UserDevice;
import ffdd.compute.dto.DeviceActivateRequest;
import ffdd.compute.dto.DeviceQueryRequest;
import ffdd.compute.dto.ReceiptCreateRequest;
import ffdd.compute.dto.ReceiptQueryRequest;
import java.util.List;

public interface ComputeService {
    PageResult<UserDevice> pageDevices(DeviceQueryRequest request);

    UserDevice getDevice(Long id);

    List<UserDevice> activateDevices(DeviceActivateRequest request);

    ComputeReceipt createReceipt(ReceiptCreateRequest request);

    ComputeReceipt settleReceiptEarnings(Long receiptId);

    PageResult<ComputeReceipt> pageReceipts(ReceiptQueryRequest request);
}
