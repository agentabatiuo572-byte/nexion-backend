package ffdd.device.controller;

import ffdd.common.api.ApiResult;
import ffdd.device.domain.ComputeDevice;
import ffdd.device.dto.DeviceCreateRequest;
import ffdd.device.service.ComputeDeviceService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/devices")
@RequiredArgsConstructor
public class ComputeDeviceController {
    private final ComputeDeviceService computeDeviceService;

    @GetMapping("/mine")
    public ApiResult<List<ComputeDevice>> listMine() {
        return ApiResult.ok(computeDeviceService.listMine());
    }

    @PostMapping
    public ApiResult<ComputeDevice> create(@Valid @RequestBody DeviceCreateRequest request) {
        return ApiResult.ok(computeDeviceService.create(request));
    }
}

