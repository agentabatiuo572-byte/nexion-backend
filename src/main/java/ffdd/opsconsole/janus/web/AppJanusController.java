package ffdd.opsconsole.janus.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import ffdd.opsconsole.janus.application.OpsJanusService;
import ffdd.opsconsole.janus.application.JanusExecutorClaimVerifier;
import ffdd.opsconsole.janus.application.JanusCommandLeaseService;
import ffdd.opsconsole.janus.domain.JanusDeviceView;
import ffdd.opsconsole.janus.dto.JanusCommandAckRequest;
import ffdd.opsconsole.janus.dto.JanusDeviceReportRequest;
import ffdd.opsconsole.janus.dto.JanusTakeoverProgressRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/app/janus")
@RequiredArgsConstructor
public class AppJanusController {
    private static final ObjectMapper CLAIM_MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final OpsJanusService janusService;
    private final JanusExecutorClaimVerifier claimVerifier;
    private final JanusCommandLeaseService leaseService;

    @PostMapping("/reports")
    public ApiResult<JanusDeviceView> report(@RequestBody JanusDeviceReportRequest request,
                                             @RequestHeader("X-Janus-Executor-Id") String executorId,
                                             @RequestHeader("X-Janus-Device-Id") String claimedDeviceId,
                                             @RequestHeader("X-Janus-Claim-Nonce") String nonce,
                                             @RequestHeader("X-Janus-Claim-Timestamp") Long timestamp,
                                             @RequestHeader("X-Janus-Claim-Signature") String signature,
                                             @RequestHeader("X-Janus-Request-Body-SHA256") String claimedBodyDigest,
                                             Authentication authentication) {
        Long userId = authenticatedUserId(authentication);
        if (userId == null) return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        ApiResult<Map<String, Object>> denied = verifyClaim(userId, request == null ? null : request.deviceId(),
                claimedDeviceId, executorId, nonce, timestamp, signature, "POST", "/api/app/janus/reports",
                claimedBodyDigest, request);
        return denied != null ? ApiResult.fail(denied.getCode(), denied.getMessage())
                : janusService.reportDevice(userId, request);
    }

    @GetMapping("/commands/pending")
    public ApiResult<Map<String, Object>> pending(
            @RequestParam String deviceId,
            @RequestHeader("X-Janus-Executor-Id") String executorId,
            @RequestHeader("X-Janus-Device-Id") String claimedDeviceId,
            @RequestHeader("X-Janus-Claim-Nonce") String nonce,
            @RequestHeader("X-Janus-Claim-Timestamp") Long timestamp,
            @RequestHeader("X-Janus-Claim-Signature") String signature,
            @RequestHeader("X-Janus-Request-Body-SHA256") String claimedBodyDigest,
            @RequestHeader(value="X-Janus-Resume-Lease-Token", required=false) String resumeLeaseToken,
            Authentication authentication) {
        Long userId = authenticatedUserId(authentication);
        if (userId == null) return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        ApiResult<Map<String, Object>> denied = verifyClaim(userId, deviceId, claimedDeviceId, executorId,
                nonce, timestamp, signature, "GET", "/api/app/janus/commands/pending?deviceId=" + text(deviceId),
                claimedBodyDigest, null);
        if (denied != null) return denied;
        ApiResult<Map<String,Object>> pending = janusService.pendingCommand(userId, deviceId);
        if (pending.getCode() != 0 || pending.getData() == null
                || !Boolean.TRUE.equals(pending.getData().get("hasCommand"))) return pending;
        Map<String,Object> data = new LinkedHashMap<>(pending.getData());
        long version = number(data.get("commandVersion"), number(data.get("revision"), 0));
        String commandId = text(data.get("commandId"));
        if (commandId.isEmpty()) commandId = "device-status:" + version;
        JanusCommandLeaseService.Lease lease = leaseService.claim(deviceId.trim(), commandId, version,
                executorId.trim(), nonce.trim(), resumeLeaseToken);
        if (!lease.accepted()) return ApiResult.fail(409, lease.error());
        data.put("leaseToken", lease.leaseToken());
        data.put("fencingToken", lease.fencingToken());
        data.put("leaseExpiresAt", lease.leaseExpiresAt());
        String commandDigest=commandDigest(data, commandId, version, lease);
        data.put("commandDigest", commandDigest);
        data.put("commandAuthorization", claimVerifier.authorizeCommand(userId,executorId.trim(),deviceId.trim(),commandDigest));
        return ApiResult.ok(data);
    }

    @PostMapping("/commands/ack")
    public ApiResult<Map<String, Object>> acknowledge(
                                                       @RequestBody JanusCommandAckRequest request,
                                                       @RequestHeader("X-Janus-Executor-Id") String executorId,
                                                       @RequestHeader("X-Janus-Device-Id") String claimedDeviceId,
                                                       @RequestHeader("X-Janus-Claim-Nonce") String nonce,
                                                       @RequestHeader("X-Janus-Claim-Timestamp") Long timestamp,
                                                       @RequestHeader("X-Janus-Claim-Signature") String signature,
                                                       @RequestHeader("X-Janus-Request-Body-SHA256") String claimedBodyDigest,
                                                       @RequestHeader("X-Janus-Lease-Token") String leaseToken,
                                                       @RequestHeader("X-Janus-Fencing-Token") Long fencingToken,
                                                       Authentication authentication) {
        Long userId = authenticatedUserId(authentication);
        if (userId == null) return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        ApiResult<Map<String, Object>> denied = verifyClaim(userId, request == null ? null : request.deviceId(),
                claimedDeviceId, executorId, nonce, timestamp, signature, "POST", "/api/app/janus/commands/ack",
                claimedBodyDigest, request);
        if (denied != null) return denied;
        ApiResult<Map<String,Object>> leaseDenied = verifyLease(request == null ? null : request.deviceId(),
                request == null ? null : request.commandId(), request == null ? null : request.revision(),
                executorId, leaseToken, fencingToken,
                request == null ? null : request.leaseToken(), request == null ? null : request.fencingToken());
        return leaseDenied != null ? leaseDenied : janusService.acknowledgeCommand(userId, request);
    }

    @GetMapping("/commands/terminal")
    public ApiResult<Map<String,Object>> terminal(
            @RequestParam String deviceId,@RequestParam String commandId,@RequestParam Long commandVersion,
            @RequestParam(required=false) String desiredStatus,
            @RequestHeader("X-Janus-Executor-Id") String executorId,
            @RequestHeader("X-Janus-Device-Id") String claimedDeviceId,
            @RequestHeader("X-Janus-Claim-Nonce") String nonce,
            @RequestHeader("X-Janus-Claim-Timestamp") Long timestamp,
            @RequestHeader("X-Janus-Claim-Signature") String signature,
            @RequestHeader("X-Janus-Request-Body-SHA256") String claimedBodyDigest,
            @RequestHeader("X-Janus-Resume-Lease-Token") String resumeLeaseToken,
            Authentication authentication) {
        Long userId=authenticatedUserId(authentication);
        if(userId==null)return ApiResult.fail(403,"USER_AUTH_REQUIRED");
        ApiResult<Map<String,Object>> denied=verifyClaim(userId,deviceId,claimedDeviceId,executorId,nonce,timestamp,
                signature,"GET",terminalClaimPath(deviceId,commandId,commandVersion,desiredStatus),claimedBodyDigest,null);
        if(denied!=null)return denied;
        JanusCommandLeaseService.Lease lease=leaseService.claim(deviceId.trim(),commandId.trim(),commandVersion,
                executorId.trim(),nonce.trim(),resumeLeaseToken);
        if(!lease.accepted())return ApiResult.fail(409,lease.error());
        ApiResult<Map<String,Object>> terminal=janusService.commandTerminal(userId,deviceId,commandId,commandVersion,desiredStatus);
        if(terminal.getCode()!=0||terminal.getData()==null)return terminal;
        Map<String,Object> data=new LinkedHashMap<>(terminal.getData());
        data.put("leaseToken",lease.leaseToken());data.put("fencingToken",lease.fencingToken());
        data.put("leaseExpiresAt",lease.leaseExpiresAt());
        return ApiResult.ok(data);
    }

    @PostMapping("/takeover/progress")
    public ApiResult<Map<String,Object>> takeoverProgress(
            @RequestBody JanusTakeoverProgressRequest request,
            @RequestHeader("X-Janus-Executor-Id") String executorId,
            @RequestHeader("X-Janus-Device-Id") String claimedDeviceId,
            @RequestHeader("X-Janus-Claim-Nonce") String nonce,
            @RequestHeader("X-Janus-Claim-Timestamp") Long timestamp,
            @RequestHeader("X-Janus-Claim-Signature") String signature,
            @RequestHeader("X-Janus-Request-Body-SHA256") String claimedBodyDigest,
            @RequestHeader("X-Janus-Lease-Token") String leaseToken,
            @RequestHeader("X-Janus-Fencing-Token") Long fencingToken,
            Authentication authentication) {
        Long userId=authenticatedUserId(authentication);
        if (userId == null) return ApiResult.fail(403,"USER_AUTH_REQUIRED");
        ApiResult<Map<String, Object>> denied = verifyClaim(userId, request == null ? null : request.deviceId(),
                claimedDeviceId, executorId, nonce, timestamp, signature, "POST", "/api/app/janus/takeover/progress",
                claimedBodyDigest, request);
        if (denied != null) return denied;
        ApiResult<Map<String,Object>> leaseDenied = verifyLease(request == null ? null : request.deviceId(),
                request == null ? null : request.commandId(), request == null ? null : request.commandVersion(),
                executorId, leaseToken, fencingToken,
                request == null ? null : request.leaseToken(), request == null ? null : request.fencingToken());
        return leaseDenied != null ? leaseDenied : janusService.reportTakeoverProgress(userId,request);
    }

    private ApiResult<Map<String,Object>> verifyLease(String deviceId, String commandId, Long commandVersion,
                                                       String executorId, String headerLeaseToken, Long headerFence,
                                                       String bodyLeaseToken, Long bodyFence) {
        if (deviceId == null || commandId == null || commandVersion == null || headerFence == null
                || bodyFence == null || !text(headerLeaseToken).equals(text(bodyLeaseToken))
                || !headerFence.equals(bodyFence)) return ApiResult.fail(403, "JANUS_COMMAND_FENCE_MISMATCH");
        JanusCommandLeaseService.Verification verified = leaseService.verify(deviceId.trim(), commandId.trim(),
                commandVersion, text(executorId), text(headerLeaseToken), headerFence);
        return verified.accepted() ? null : ApiResult.fail(403, verified.error());
    }

    static String commandDigest(Map<String,Object> row, String commandId, long version,
                                JanusCommandLeaseService.Lease lease) {
        String commandType=text(row.get("commandType"));
        boolean legacy=commandType.isEmpty();
        if(legacy){
            String desired=text(row.get("desiredStatus")).toUpperCase(java.util.Locale.ROOT);
            commandType=Set.of("HIT","ACTIVATED","MANUAL_FORCED").contains(desired)?"ACTIVATE":"REVOKE";
        }
        String target=first(row,"expectedTargetId","remoteUrlKey");
        String targetVersion=first(row,"expectedTargetVersion","remoteTargetVersion");
        String catalogVersion=first(row,"expectedTargetCatalogVersion","remoteTargetCatalogVersion");
        if(legacy&&"REVOKE".equals(commandType)){target="none";targetVersion="0";catalogVersion="0";}
        String canonical = String.join("\n",text(row.get("sid")),commandId,String.valueOf(version),commandType,
                target,targetVersion,catalogVersion,text(row.get("remoteTargetUrl")),
                text(row.get("reconciliationId")),lease.leaseToken(),String.valueOf(lease.fencingToken()),
                String.valueOf(lease.leaseExpiresAt()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("JANUS_COMMAND_DIGEST_FAILED", exception);
        }
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private static String first(Map<String,Object> row, String primary, String fallback) {
        String value = text(row.get(primary));
        return value.isEmpty() ? text(row.get(fallback)) : value;
    }
    private static long number(Object value, long fallback) {
        if (value == null) return fallback;
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private static String terminalClaimPath(String deviceId, String commandId, Long commandVersion,
                                            String desiredStatus) {
        return "/api/app/janus/commands/terminal?commandId=" + text(commandId)
                + "&commandVersion=" + String.valueOf(commandVersion)
                + (text(desiredStatus).isEmpty() ? "" : "&desiredStatus=" + text(desiredStatus))
                + "&deviceId=" + text(deviceId);
    }

    private ApiResult<Map<String, Object>> verifyClaim(Long userId, String requestDeviceId, String claimedDeviceId,
                                                        String executorId, String nonce, Long timestamp,
                                                        String signature, String method, String path,
                                                        String claimedBodyDigest, Object body) {
        if (requestDeviceId == null || !requestDeviceId.trim().equals(claimedDeviceId == null ? "" : claimedDeviceId.trim())) {
            return ApiResult.fail(403, "JANUS_EXECUTOR_DEVICE_BINDING_MISMATCH");
        }
        String actualBodyDigest = bodyDigest(body);
        if (!MessageDigest.isEqual(actualBodyDigest.getBytes(StandardCharsets.US_ASCII),
                text(claimedBodyDigest).toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII))) {
            return ApiResult.fail(403, "JANUS_REQUEST_BODY_DIGEST_MISMATCH");
        }
        JanusExecutorClaimVerifier.Verification verification = claimVerifier.verify(userId,
                new JanusExecutorClaimVerifier.Claim(claimedDeviceId, executorId, nonce, timestamp, signature,
                        method, path, actualBodyDigest));
        return verification.accepted() ? null : ApiResult.fail(403, verification.error());
    }

    static String bodyDigest(Object body) {
        try {
            byte[] canonical = body == null ? new byte[0]
                    : canonicalJson(CLAIM_MAPPER.valueToTree(body)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (Exception exception) {
            throw new IllegalStateException("JANUS_REQUEST_BODY_DIGEST_FAILED", exception);
        }
    }

    private static String canonicalJson(JsonNode node) throws Exception {
        if (node.isObject()) {
            ArrayList<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            StringBuilder value = new StringBuilder("{");
            for (int index = 0; index < names.size(); index++) {
                if (index > 0) value.append(',');
                String name = names.get(index);
                value.append(CLAIM_MAPPER.writeValueAsString(name)).append(':')
                        .append(canonicalJson(node.get(name)));
            }
            return value.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder value = new StringBuilder("[");
            for (int index = 0; index < node.size(); index++) {
                if (index > 0) value.append(',');
                value.append(canonicalJson(node.get(index)));
            }
            return value.append(']').toString();
        }
        return CLAIM_MAPPER.writeValueAsString(node);
    }

    private Long authenticatedUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal() == null
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) return null;
        try {
            long value = Long.parseLong(String.valueOf(authentication.getPrincipal()));
            return value > 0 ? value : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
