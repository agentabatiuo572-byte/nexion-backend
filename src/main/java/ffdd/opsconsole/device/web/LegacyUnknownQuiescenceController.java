package ffdd.opsconsole.device.web;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import ffdd.opsconsole.device.application.LegacyUnknownZeroCreditService;
import ffdd.opsconsole.device.application.TestCloudExecutorService;
import ffdd.opsconsole.device.dto.LegacyUnknownZeroCreditProof;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Dedicated worker bearer plus root HMAC; available while Compute V2 remains disabled. */
@RestController
@RequestMapping("/api/test/cloud-executors/v2/legacy-unknown-quiescence")
@RequiredArgsConstructor
public class LegacyUnknownQuiescenceController {
    private final LegacyUnknownZeroCreditService service;
    private final TestCloudExecutorService cloud;
    private final JsonMapper json=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    @GetMapping("/info")
    public Object info(Authentication authentication) { requireFleet(authentication);return ApiResult.ok(service.info()); }
    @PostMapping
    public Object register(Authentication authentication,HttpServletRequest request) {
        requireFleet(authentication);
        try {
            byte[] bytes=request.getInputStream().readNBytes(140001);
            if (bytes.length>140000) throw new BizException(413,"LEGACY_UNKNOWN_PROOF_TOO_LARGE");
            return ApiResult.ok(service.register(json.readValue(bytes,LegacyUnknownZeroCreditProof.class)));
        } catch (java.io.IOException invalid) { throw new BizException(400,"LEGACY_UNKNOWN_PROOF_INVALID"); }
    }
    private void requireFleet(Authentication auth) {
        if (auth==null || !(auth.getPrincipal() instanceof TestCloudExecutorService.Identity i)
                || !"fleet".equals(i.executorId()) || i.ownerId()!=0 || i.deviceId()!=0)
            throw new BizException(403,"TEST_CLOUD_FLEET_REQUIRED");
        cloud.requireCurrent(i);
    }
}
