package ffdd.opsconsole.janus.web;

import ffdd.opsconsole.device.application.AppComputeShareEnrollmentService;
import ffdd.opsconsole.janus.application.JanusExecutorClaimVerifier;
import ffdd.opsconsole.shared.api.ApiResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/app/janus/compute-share/enrollments")
@RequiredArgsConstructor
public class AppComputeShareJanusController {
    private static final String CLAIM_PATH = "/api/app/janus/compute-share/enrollments/claim";

    private final AppComputeShareEnrollmentService service;
    private final JanusExecutorClaimVerifier claimVerifier;

    @PostMapping("/claim")
    public ApiResult<Map<String, Object>> claim(
            @RequestBody ClaimRequest request,
            @RequestHeader("X-Janus-Executor-Id") String executorId,
            @RequestHeader("X-Janus-Device-Id") String claimedDeviceId,
            @RequestHeader("X-Janus-Claim-Nonce") String nonce,
            @RequestHeader("X-Janus-Claim-Timestamp") Long timestamp,
            @RequestHeader("X-Janus-Claim-Signature") String signature,
            @RequestHeader("X-Janus-Request-Body-SHA256") String claimedBodyDigest,
            Authentication authentication) {
        Long userId = authenticatedUserId(authentication);
        if (userId == null) return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        if (request == null || !text(request.deviceInstanceNo()).equals(text(claimedDeviceId))) {
            return ApiResult.fail(403, "JANUS_EXECUTOR_DEVICE_BINDING_MISMATCH");
        }
        String actualDigest = AppJanusController.bodyDigest(request);
        if (!MessageDigest.isEqual(actualDigest.getBytes(StandardCharsets.US_ASCII),
                text(claimedBodyDigest).toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII))) {
            return ApiResult.fail(403, "JANUS_REQUEST_BODY_DIGEST_MISMATCH");
        }
        var verified = claimVerifier.verify(userId, new JanusExecutorClaimVerifier.Claim(
                claimedDeviceId, executorId, nonce, timestamp, signature, "POST", CLAIM_PATH, actualDigest));
        if (!verified.accepted()) return ApiResult.fail(403, verified.error());
        return service.claim(userId, request.enrollmentNo(), request.pairingCode(), request.deviceInstanceNo(),
                request.gpuModel(), request.vramTotalGb(), request.basePowerW());
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Long authenticatedUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal() == null
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) return null;
        try {
            long value = Long.parseLong(String.valueOf(authentication.getPrincipal()));
            return value > 0 ? value : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    public record ClaimRequest(String enrollmentNo, String pairingCode, String deviceInstanceNo,
                               String gpuModel, Integer vramTotalGb, Integer basePowerW) { }
}
