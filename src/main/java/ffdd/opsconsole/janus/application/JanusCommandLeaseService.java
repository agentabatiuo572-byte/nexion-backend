package ffdd.opsconsole.janus.application;

import ffdd.opsconsole.janus.mapper.JanusCommandLeaseMapper;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Provides a database fencing lease so only one claimant may execute a command tuple. */
@Component
@RequiredArgsConstructor
public class JanusCommandLeaseService {
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9._:-]{3,128}$");
    private static final Pattern TOKEN = Pattern.compile("^[a-f0-9]{64}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JanusCommandLeaseMapper mapper;
    @Value("${nexion.janus.executor.lease-ms:90000}")
    private final long leaseMs;

    public Lease claim(String deviceId, String commandId, long commandVersion, String executorId,
                       String claimNonce, String resumeLeaseToken) {
        if (!id(deviceId) || !id(commandId) || commandVersion <= 0 || !id(executorId) || !id(claimNonce)) {
            return Lease.rejected("JANUS_COMMAND_LEASE_INVALID");
        }
        long now = System.currentTimeMillis();
        long until = now + Math.max(5_000L, leaseMs);
        String token = token();
        if (mapper.insert(deviceId, commandId, commandVersion, executorId, claimNonce, token, until) == 1) {
            return readback(deviceId, commandId, commandVersion, token, 1L);
        }
        Map<String,Object> row = mapper.find(deviceId, commandId, commandVersion);
        if (row == null) return Lease.rejected("JANUS_COMMAND_LEASE_READBACK_FAILED");
        String currentToken = text(row, "leaseToken");
        long fence = number(row, "fencingToken");
        // The controller has already verified a fresh device-bound native claim.  A lost pending
        // response must not strand that same executor for a full lease window; only a different
        // attested executor remains blocked until expiry or it presents the current resume token.
        boolean sameAttestedExecutor = executorId.equals(text(row, "executorId"));
        if (sameAttestedExecutor && (TOKEN.matcher(trim(resumeLeaseToken)).matches()
                ? trim(resumeLeaseToken).equals(currentToken) : true)) {
            if (mapper.renew(deviceId, commandId, commandVersion, executorId, currentToken, fence,
                    claimNonce, until) != 1) return Lease.rejected("JANUS_COMMAND_LEASE_CONFLICT");
            return readback(deviceId, commandId, commandVersion, currentToken, fence);
        }
        if (number(row, "leaseExpiresAt") >= now) return Lease.rejected("JANUS_COMMAND_LEASE_HELD");
        if (mapper.takeExpired(deviceId, commandId, commandVersion, executorId, claimNonce, token,
                currentToken, fence, until, now) != 1) return Lease.rejected("JANUS_COMMAND_LEASE_HELD");
        return readback(deviceId, commandId, commandVersion, token, fence + 1);
    }

    public Verification verify(String deviceId, String commandId, long commandVersion, String executorId,
                               String leaseToken, long fencingToken) {
        if (!TOKEN.matcher(trim(leaseToken)).matches() || fencingToken <= 0) {
            return new Verification(false, "JANUS_COMMAND_FENCE_INVALID");
        }
        Map<String,Object> row = mapper.find(deviceId, commandId, commandVersion);
        if (row == null || !executorId.equals(text(row, "executorId"))
                || !leaseToken.equals(text(row, "leaseToken")) || fencingToken != number(row, "fencingToken")) {
            return new Verification(false, "JANUS_COMMAND_FENCE_STALE");
        }
        if (number(row, "leaseExpiresAt") < System.currentTimeMillis()) {
            return new Verification(false, "JANUS_COMMAND_LEASE_EXPIRED");
        }
        return new Verification(true, null);
    }

    private Lease readback(String deviceId, String commandId, long commandVersion, String token, long fence) {
        Map<String,Object> row = mapper.find(deviceId, commandId, commandVersion);
        if (row == null || !token.equals(text(row, "leaseToken")) || fence != number(row, "fencingToken")) {
            return Lease.rejected("JANUS_COMMAND_LEASE_READBACK_FAILED");
        }
        return new Lease(true, null, token, fence, number(row, "leaseExpiresAt"));
    }

    private static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
    private static boolean id(String value) { return ID.matcher(trim(value)).matches(); }
    private static String trim(String value) { return value == null ? "" : value.trim(); }
    private static String text(Map<String,Object> row, String key) { return trim(String.valueOf(row.get(key))); }
    private static long number(Map<String,Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    public record Lease(boolean accepted, String error, String leaseToken, long fencingToken,
                        long leaseExpiresAt) {
        static Lease rejected(String error) { return new Lease(false, error, null, 0, 0); }
    }
    public record Verification(boolean accepted, String error) { }
}
