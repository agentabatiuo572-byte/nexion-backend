package ffdd.device.service.impl;

import ffdd.device.domain.ComputeDevice;
import ffdd.device.dto.DeviceCreateRequest;
import ffdd.device.service.ComputeDeviceService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ComputeDeviceServiceImpl implements ComputeDeviceService {
    @Override
    public List<ComputeDevice> listMine() {
        ComputeDevice phone = new ComputeDevice();
        phone.setId(1L);
        phone.setUserId(10001L);
        phone.setDeviceId(1L);
        phone.setInstanceNo("UD-10001-PHONE");
        phone.setName("Mobile Compute");
        phone.setStatus("ONLINE");
        phone.setDailyUsdt(new BigDecimal("0.06"));
        phone.setDailyNex(new BigDecimal("12.00"));
        phone.setLastSeenAt(LocalDateTime.now());
        phone.setActivatedAt(LocalDateTime.now());
        return List.of(phone);
    }

    @Override
    public ComputeDevice create(DeviceCreateRequest request) {
        return listMine().get(0);
    }
}
