package ffdd.opsconsole.janus.application;

import ffdd.opsconsole.janus.dto.JanusTakeoverProgressRequest;
import ffdd.opsconsole.janus.mapper.JanusTakeoverMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Verifies and claims non-replayable executor proof before any applied state is mutated. */
@Service
@RequiredArgsConstructor
public class JanusAppliedProofVerifier {
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9._:-]{3,128}$");
    private static final Pattern NONCE = Pattern.compile("^[A-Za-z0-9._:-]{32,128}$");
    private final JanusTakeoverMapper mapper;
    @Value("${nexion.janus.executor.mode:PRODUCTION}")
    private final String mode;
    @Value("${nexion.janus.executor.sandbox-targets:}")
    private final String sandboxTargets;
    @Value("${nexion.janus.executor.production-keys:}")
    private final String productionKeys;
    @Value("${nexion.janus.executor.max-skew-ms:120000}")
    private final long maxSkewMs;
    @Value("${nexion.janus.executor.production-applied-enabled:false}")
    private final boolean productionAppliedEnabled;
    private final Optional<JanusSandboxEnrollmentService> sandboxEnrollmentService;

    public Verification verify(long userId, String sid, Map<String,Object> row,
                               JanusTakeoverProgressRequest request) {
        return verify(userId, sid, row, request, true);
    }

    public Verification verify(long userId, String sid, Map<String,Object> row,
                               JanusTakeoverProgressRequest request, boolean allowNewClaim) {
        String proofMode = upper(request.proofMode());
        String executorId = trim(request.executorId());
        String nonce = trim(request.proofNonce());
        Long timestamp = request.proofTimestamp();
        String signature = trim(request.proofSignature());
        if (!ID.matcher(executorId).matches() || !NONCE.matcher(nonce).matches()
                || timestamp == null || signature.isEmpty() || !trim(request.leaseToken()).matches("^[a-f0-9]{64}$")
                || request.fencingToken() == null || request.fencingToken() <= 0
                || !ID.matcher(trim(request.actualAppliedCommandId())).matches()
                || request.commandVersion() == null || request.actualAppliedCommandVersion() == null
                || request.actualAppliedCommandVersion() <= 0 || request.actualFencingToken() == null
                || request.actualFencingToken() <= 0
                || (!StringUtils.hasText(request.reconciliationId())
                    && (!trim(request.commandId()).equals(trim(request.actualAppliedCommandId()))
                        || !request.commandVersion().equals(request.actualAppliedCommandVersion())
                        || !request.fencingToken().equals(request.actualFencingToken())))) {
            return rejected("JANUS_APPLIED_PROOF_UNTRUSTED");
        }
        String canonical = canonical(userId, sid, request);
        if ("SANDBOX".equals(proofMode)) {
            JanusSandboxEnrollmentService enrollment = sandboxEnrollmentService.orElse(null);
            boolean enrolled = enrollment != null && enrollment.verify(userId, request.deviceId(), signature);
            boolean targetAllowed = enrolled && enrollment.allowsTarget(target(request));
            if (!"SANDBOX".equals(upper(mode)) || !"sandbox".equals(executorId)
                    || !enrolled || !targetAllowed) {
                return rejected("JANUS_SANDBOX_PROOF_ISOLATION_MISMATCH");
            }
            if (!trim(request.handoffReceipt()).startsWith("sandbox:v1:")) {
                return rejected("JANUS_SANDBOX_RECEIPT_REQUIRED");
            }
        } else if ("PRODUCTION".equals(proofMode)) {
            if (!productionAppliedEnabled) {
                return rejected("JANUS_PRODUCTION_APPLIED_HOLD");
            }
            if (!"PRODUCTION".equals(upper(mode)) || trim(request.handoffReceipt()).startsWith("sandbox:")) {
                return rejected("JANUS_PRODUCTION_PROOF_REQUIRED");
            }
            ProductionExecutor executor = parseExecutors(productionKeys).get(executorId);
            if (executor == null || !executor.deviceId().equals(trim(request.deviceId()))
                    || !constantTime(hmac(executor.key(), canonical), signature.toLowerCase())) {
                return rejected("JANUS_APPLIED_PROOF_SIGNATURE_INVALID");
            }
        } else {
            return rejected("JANUS_APPLIED_PROOF_MODE_INVALID");
        }
        String proofHash = sha256(canonical + "\n" + signature);
        String prior = mapper.findProofHash(executorId, nonce);
        if (prior != null) return constantTime(prior, proofHash)
                ? new Verification(true, true, null, proofId(proofHash), proofHash, proofMode)
                : rejected("JANUS_APPLIED_PROOF_REPLAYED");
        if (Math.abs(System.currentTimeMillis() - timestamp) > Math.max(1_000L, maxSkewMs)) return rejected("JANUS_APPLIED_PROOF_EXPIRED");
        if (!allowNewClaim) return rejected("JANUS_APPLIED_PROOF_REPLAY_REQUIRED");
        String proofId = proofId(proofHash);
        int inserted = mapper.claimAppliedProof(proofId, proofMode, executorId, nonce, proofHash, userId,
                sid, trim(request.deviceId()), trim(request.commandId()), request.commandVersion(), target(request),
                request.actualTargetVersion(), request.actualTargetCatalogVersion(), trim(request.handoffReceipt()), timestamp);
        if (inserted == 1) {
            String readback=mapper.findProofHash(executorId,nonce);
            return proofHash.equals(readback)
                    ? new Verification(true, false, null, proofId, proofHash, proofMode)
                    : rejected("JANUS_APPLIED_PROOF_READBACK_FAILED");
        }
        prior = mapper.findProofHash(executorId, nonce);
        return prior != null && constantTime(prior, proofHash)
                ? new Verification(true, true, null, proofId, proofHash, proofMode)
                : rejected("JANUS_APPLIED_PROOF_REPLAYED");
    }

    static String canonical(long userId, String sid, JanusTakeoverProgressRequest request) {
        return String.join("\n", "JANUS_APPLIED_PROOF_V1", String.valueOf(userId), trim(sid), trim(request.deviceId()),
                trim(request.commandId()), String.valueOf(request.commandVersion()), upper(request.phase()),
                target(request), String.valueOf(request.actualTargetVersion()),
                String.valueOf(request.actualTargetCatalogVersion()), String.valueOf(request.deviceAppliedVersion()),
                trim(request.deviceAppVersion()), trim(request.handoffReceipt()), trim(request.reconciliationId()),
                trim(request.leaseToken()), String.valueOf(request.fencingToken()),
                trim(request.actualAppliedCommandId()), String.valueOf(request.actualAppliedCommandVersion()),
                String.valueOf(request.actualFencingToken()),
                upper(request.proofMode()), trim(request.executorId()), trim(request.proofNonce()),
                String.valueOf(request.proofTimestamp()));
    }

    private static String target(JanusTakeoverProgressRequest request) {
        return StringUtils.hasText(request.actualTargetId()) ? request.actualTargetId().trim() : "none";
    }
    private static String hmac(byte[] key, String value) {
        try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException("JANUS_PROOF_HMAC_FAILED", ex); }
    }
    private static boolean constantTime(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException("JANUS_PROOF_HASH_FAILED", ex); }
    }
    private static String proofId(String proofHash) { return "JAP-" + proofHash.substring(0, 24).toUpperCase(); }
    private static Map<String,ProductionExecutor> parseExecutors(String value) {
        Map<String,ProductionExecutor> out = new LinkedHashMap<>();
        for (String item : value.split(",")) { String[] bits = item.trim().split(":", 3); if (bits.length != 3) continue;
            try { byte[] key=Base64.getDecoder().decode(bits[2]);
                if(ID.matcher(bits[0]).matches()&&ID.matcher(bits[1]).matches()&&key.length>=32){out.put(bits[0],new ProductionExecutor(bits[1],key));}
            } catch (IllegalArgumentException ignored) { } }
        return Map.copyOf(out);
    }
    private static String trim(String value) { return value == null ? "" : value.trim(); }
    private static String upper(String value) { return trim(value).toUpperCase(java.util.Locale.ROOT); }
    private static Verification rejected(String error) { return new Verification(false, false, error, null, null, null); }
    private record ProductionExecutor(String deviceId, byte[] key) { }
    public record Verification(boolean accepted, boolean duplicate, String error, String proofId,
                               String proofHash,String proofMode) {
        public static Verification rejected(String error) { return JanusAppliedProofVerifier.rejected(error); }
    }
}
