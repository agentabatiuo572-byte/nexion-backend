package ffdd.store.controller;

import ffdd.common.api.ApiResult;
import ffdd.store.domain.StoreDevice;
import ffdd.store.service.StoreDeviceService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/store/devices")
@RequiredArgsConstructor
public class StoreDeviceController {
    private final StoreDeviceService storeDeviceService;

    @GetMapping
    public ApiResult<List<StoreDevice>> listOnSale() {
        return ApiResult.ok(storeDeviceService.listOnSale());
    }
}

