package ffdd.opsconsole.shared.web;

import ffdd.opsconsole.shared.api.ApiResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Compatibility response for old clients. No device, account or financial writes. */
@RestController
public class RetiredDeviceIntegrationController {
    @RequestMapping({"/api/app/janus", "/api/app/janus/**",
            "/api/admin/janus", "/api/admin/janus/**",
            "/api/app/compute-share/enrollments", "/api/app/compute-share/enrollments/**"})
    public ResponseEntity<ApiResult<Void>> retired() {
        return ResponseEntity.status(410).body(ApiResult.fail(410, "此功能已停止使用，请返回设备或账户页面。"));
    }
}
