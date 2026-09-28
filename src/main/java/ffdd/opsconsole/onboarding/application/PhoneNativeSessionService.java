package ffdd.opsconsole.onboarding.application;

import ffdd.opsconsole.onboarding.mapper.PhoneNativeSessionMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PhoneNativeSessionService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final PhoneNativeSessionMapper mapper;
    private final AndroidPhoneAttestationVerifier verifier;
    private final Clock clock;
    public record Proof(String deviceId,List<String> certificates,String signature) { }

    @Transactional
    public Map<String,Object> challenge(Authentication auth,String deviceId) {
        long userId = userId(auth);
        String session = sessionId(auth);
        validateDeviceId(deviceId);
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String nonce = Base64.getEncoder().encodeToString(bytes);
        String payload = Base64.getEncoder().encodeToString(
                ("phone-installation-v1\n" + userId + "\n" + session + "\n" + deviceId + "\n" + nonce).getBytes(StandardCharsets.UTF_8));
        long expiresAt = clock.millis() + 120000;
        mapper.challenge(userId,session,deviceId,nonce,payload,expiresAt);
        return Map.of("nonce",nonce,"payload",payload,"expiresAt",expiresAt,
                "keyAlias","phone-v1:" + userId + ":" + deviceId,
                "keyRegistered",mapper.knownKey(userId,deviceId) != null);
    }
    @Transactional
    public Map<String,Object> verify(Authentication auth,Proof proof) {
        long userId = userId(auth);
        String sessionId = sessionId(auth);
        if (proof == null) throw new BizException(422,"PHONE_NATIVE_PROOF_INVALID");
        validateDeviceId(proof.deviceId());
        var pending = mapper.lock(userId,sessionId);
        if (pending == null || !pending.installationId().equals(proof.deviceId())
                || pending.nonce().isBlank() || pending.challengeExpiresAt() <= clock.millis()) {
            throw new BizException(409,"PHONE_NATIVE_CHALLENGE_EXPIRED");
        }
        String key = verifier.verify(proof.certificates(),pending.nonce(),pending.payload(),proof.signature(),
                mapper.knownKey(userId,proof.deviceId()));
        mapper.saveKey(userId,proof.deviceId(),key);
        if (!key.equals(mapper.knownKey(userId,proof.deviceId()))) throw new BizException(409,"PHONE_NATIVE_KEY_CHANGED");
        long until = clock.millis() + 900000;
        if (mapper.verify(userId,sessionId,pending.nonce(),until) != 1) throw new BizException(409,"PHONE_NATIVE_CHALLENGE_EXPIRED");
        return Map.of("deviceId",proof.deviceId(),"verifiedUntil",until);
    }
    public void require(Authentication auth,String deviceId) {
        var session = mapper.read(userId(auth),sessionId(auth));
        if (session == null || session.verifiedUntil() <= clock.millis()
                || deviceId == null || !deviceId.equals(session.installationId())) {
            throw new BizException(403,"PHONE_NATIVE_SESSION_REQUIRED");
        }
    }
    public void requireForDevice(Authentication auth,Long deviceId) {
        long userId = userId(auth);
        if (deviceId == null || deviceId <= 0) throw new BizException(422,"TASK_ASSIGNMENT_DEVICE_REQUIRED");
        var binding = mapper.deviceBinding(userId,deviceId);
        if (binding == null) throw new BizException(404,"TASK_ASSIGNMENT_DEVICE_NOT_FOUND");
        if ("PHONE".equalsIgnoreCase(binding.deviceType()) || "MOBILE".equalsIgnoreCase(binding.deviceType())) {
            requireRuntime(auth,binding.installationId());
        }
    }
    public void requireRuntime(Authentication auth,String deviceId) {
        require(auth,deviceId);
        mapper.lockUser(userId(auth));
        if (!deviceId.equals(mapper.executionInstallation(userId(auth)))) {
            throw new BizException(409,"TASK_ASSIGNMENT_PHONE_BINDING_INVALID");
        }
    }
    public void requireForTask(Authentication auth,String taskNo) {
        long userId = userId(auth);
        Long deviceId = mapper.taskDevice(userId,taskNo);
        if (deviceId == null) throw new BizException(404,"TASK_ASSIGNMENT_NOT_FOUND");
        requireForDevice(auth,deviceId);
    }
    private static long userId(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || !(auth.getDetails() instanceof Map<?,?> details)
                || !"USER".equals(details.get("subjectType"))) throw new BizException(403,"USER_AUTH_REQUIRED");
        try {
            long id = Long.parseLong(String.valueOf(auth.getPrincipal()));
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException invalid) { throw new BizException(403,"USER_AUTH_REQUIRED"); }
    }
    private static String sessionId(Authentication auth) {
        if (!(auth.getDetails() instanceof Map<?,?> details) || !(details.get("sessionId") instanceof String session)
                || session.isBlank() || session.length()>128) throw new BizException(403,"PHONE_NATIVE_SESSION_REQUIRED");
        return session;
    }
    private static void validateDeviceId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._:-]{1,128}")) throw new BizException(422,"ONBOARDING_DEVICE_INVALID");
    }
}
