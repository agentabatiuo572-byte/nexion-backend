package ffdd.device.service;

import ffdd.device.domain.ComputeDevice;
import ffdd.device.dto.DeviceCreateRequest;
import java.util.List;

public interface ComputeDeviceService {
    List<ComputeDevice> listMine();

    ComputeDevice create(DeviceCreateRequest request);
}

