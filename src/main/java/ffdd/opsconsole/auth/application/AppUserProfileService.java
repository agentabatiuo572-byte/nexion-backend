package ffdd.opsconsole.auth.application;

import ffdd.opsconsole.auth.mapper.AppUserProfileMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class AppUserProfileService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final List<String> ADJECTIVES = List.of(
            "Nova", "Swift", "Quantum", "Nebula", "Cosmic", "Prime", "Turbo", "Photon",
            "Zenith", "Atlas", "Vector", "Ember", "Lunar", "Solar", "Hyper", "Astro");
    private static final List<String> NOUNS = List.of(
            "Rover", "Pilot", "Falcon", "Orbit", "Beacon", "Circuit", "Vertex", "Pulse",
            "Ranger", "Comet", "Relay", "Spark", "Drift", "Core", "Harbor", "Summit");
    private static final Set<String> ALLOWED_ADJECTIVES = Set.copyOf(ADJECTIVES);
    private static final Set<String> ALLOWED_NOUNS = Set.copyOf(NOUNS);
    private static final Set<String> ALLOWED_LANGUAGES = Set.of(
            "en", "vi", "zh");

    private final AppUserProfileMapper mapper;
    private final AdminIdempotencyService idempotency;
    private final AuditLogService audit;
    private final ObjectStorageService objectStorage;

    @Transactional(readOnly = true)
    public Map<String, Object> profile(Long userId) {
        Map<String, Object> row = mapper.profile(userId);
        if (row == null) throw new BizException(404, "USER_NOT_FOUND_OR_INACTIVE");
        String objectKey = text(row.get("avatarObjectKey"));
        return Map.of(
                "nickname", text(row.get("nickname")),
                "avatarUrl", StringUtils.hasText(objectKey)
                        ? avatarUrl(objectKey) : "",
                "avatarRevision", StringUtils.hasText(objectKey) ? hash(objectKey) : "",
                "language", normalizedLanguage(row.get("language")));
    }

    @Transactional(readOnly = true)
    public List<String> nicknameCandidates(Long userId) {
        requireActiveUser(userId);
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        while (candidates.size() < 6) {
            candidates.add(ADJECTIVES.get(RANDOM.nextInt(ADJECTIVES.size())) + " "
                    + NOUNS.get(RANDOM.nextInt(NOUNS.size())) + " " + (10 + RANDOM.nextInt(90)));
        }
        return List.copyOf(candidates);
    }

    public Map<String, Object> updateNickname(
            Long userId, String idempotencyKey, UpdateNicknameRequest request) {
        requireActiveUser(userId);
        if (request == null || !StringUtils.hasText(request.expectedNickname())
                || !isCurated(request.nickname())) {
            throw new BizException(422, "USER_NICKNAME_NOT_CURATED");
        }
        String expected = request.expectedNickname().trim();
        String nickname = request.nickname().trim();
        return idempotency.execute("APP:PROFILE:NICKNAME:USER:" + userId, idempotencyKey,
                hash(expected + "|" + nickname), Map.class,
                () -> updateNicknameClaimed(userId, expected, nickname));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateLanguage(Long userId, String language) {
        requireActiveUser(userId);
        String normalized = language == null ? "" : language.trim().toLowerCase(java.util.Locale.ROOT);
        if (!ALLOWED_LANGUAGES.contains(normalized)) {
            throw new BizException(422, "USER_LANGUAGE_INVALID");
        }
        if (mapper.updateLanguage(userId, normalized) != 1) {
            throw new BizException(404, "USER_NOT_FOUND_OR_INACTIVE");
        }
        audit.recordRequired(AuditLogWriteRequest.builder()
                .action("USER_LANGUAGE_UPDATED")
                .resourceType("USER_PROFILE")
                .resourceId(String.valueOf(userId))
                .userId(userId)
                .actorId(userId)
                .actorType("USER")
                .actorUsername("user:" + userId)
                .result("SUCCESS")
                .riskLevel("LOW")
                .detail(Map.of("language", normalized))
                .build());
        return Map.of("language", normalized, "status", "UPDATED");
    }

    @Transactional(rollbackFor = Exception.class)
    Map<String, Object> updateNicknameClaimed(Long userId, String expected, String nickname) {
        String current = mapper.currentNicknameForUpdate(userId);
        if (current == null) throw new BizException(404, "USER_NOT_FOUND_OR_INACTIVE");
        if (nickname.equals(current)) {
            return Map.of("nickname", current, "status", "UNCHANGED");
        }
        if (!current.equals(expected)) throw new BizException(409, "USER_PROFILE_VERSION_CONFLICT");
        if (mapper.updateNickname(userId, nickname, expected) != 1) {
            throw new BizException(409, "USER_PROFILE_VERSION_CONFLICT");
        }
        audit.recordRequired(AuditLogWriteRequest.builder()
                .action("USER_NICKNAME_UPDATED")
                .resourceType("USER_PROFILE")
                .resourceId(String.valueOf(userId))
                .userId(userId)
                .actorId(userId)
                .actorType("USER")
                .actorUsername("user:" + userId)
                .result("SUCCESS")
                .riskLevel("LOW")
                .detail(Map.of("before", expected, "after", nickname))
                .build());
        return Map.of("nickname", nickname, "status", "UPDATED");
    }

    public Map<String, Object> uploadAvatar(
            Long userId, String idempotencyKey, MultipartFile file) {
        requireActiveUser(userId);
        AvatarFile avatar = validateAvatar(file);
        return idempotency.execute("APP:PROFILE:AVATAR:USER:" + userId, idempotencyKey,
                hash(avatar.contentType() + "|" + hash(avatar.bytes())), Map.class,
                () -> uploadAvatarClaimed(userId, avatar));
    }

    @Transactional(rollbackFor = Exception.class)
    Map<String, Object> uploadAvatarClaimed(Long userId, AvatarFile avatar) {
        String previous = mapper.currentAvatarForUpdate(userId);
        String objectKey = "users/" + userId + "/avatar/" + UUID.randomUUID().toString().replace("-", "")
                + avatar.extension();
        try {
            objectStorage.put(objectKey, avatar.contentType(),
                    new ByteArrayInputStream(avatar.bytes()), avatar.bytes().length);
            removeAfterRollback(objectKey);
            if (mapper.updateAvatarObjectKey(userId, objectKey, previous) != 1) {
                throw new BizException(409, "USER_PROFILE_VERSION_CONFLICT");
            }
            audit.recordRequired(AuditLogWriteRequest.builder()
                    .action("USER_AVATAR_UPDATED")
                    .resourceType("USER_PROFILE")
                    .resourceId(String.valueOf(userId))
                    .userId(userId)
                    .actorId(userId)
                    .actorType("USER")
                    .actorUsername("user:" + userId)
                    .result("SUCCESS")
                    .riskLevel("LOW")
                    .detail(Map.of("contentType", avatar.contentType(), "sizeBytes", avatar.bytes().length))
                    .build());
            removeAfterCommit(previous);
            return Map.of(
                    "avatarUrl", objectStorage.presignGet(objectKey, Duration.ofHours(24)),
                    "avatarRevision", hash(objectKey),
                    "status", "UPDATED");
        } catch (RuntimeException exception) {
            objectStorage.removeQuietly(objectKey);
            throw exception;
        }
    }

    private void requireActiveUser(Long userId) {
        if (userId == null || userId <= 0 || mapper.activeUser(userId) == null) {
            throw new BizException(404, "USER_NOT_FOUND_OR_INACTIVE");
        }
    }

    private String normalizedLanguage(Object value) {
        String language = text(value).toLowerCase(java.util.Locale.ROOT);
        if (!language.matches("[a-z]{2,3}(?:-[a-z0-9]{2,8})*")) return "en";
        String base = language.split("-", 2)[0];
        return ALLOWED_LANGUAGES.contains(base) ? base : "en";
    }

    private boolean isCurated(String value) {
        if (!StringUtils.hasText(value) || value.length() > 48) return false;
        String[] parts = value.trim().split(" ");
        if (parts.length != 3 || !ALLOWED_ADJECTIVES.contains(parts[0]) || !ALLOWED_NOUNS.contains(parts[1])) {
            return false;
        }
        return parts[2].matches("[1-9][0-9]");
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA256_UNAVAILABLE", ex);
        }
    }

    private String hash(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA256_UNAVAILABLE", ex);
        }
    }

    private AvatarFile validateAvatar(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() <= 0 || file.getSize() > 2 * 1024 * 1024) {
            throw new BizException(422, "USER_AVATAR_SIZE_INVALID");
        }
        try {
            byte[] bytes = file.getBytes();
            if (isPng(bytes)) return new AvatarFile(bytes, "image/png", ".png");
            if (isJpeg(bytes)) return new AvatarFile(bytes, "image/jpeg", ".jpg");
            if (isWebp(bytes)) return new AvatarFile(bytes, "image/webp", ".webp");
            throw new BizException(422, "USER_AVATAR_TYPE_INVALID");
        } catch (IOException exception) {
            throw new BizException(422, "USER_AVATAR_READ_FAILED");
        }
    }

    private boolean isPng(byte[] bytes) {
        byte[] magic = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        return startsWith(bytes, magic);
    }

    private boolean isJpeg(byte[] bytes) {
        return bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8
                && (bytes[2] & 0xff) == 0xff;
    }

    private boolean isWebp(byte[] bytes) {
        return bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }

    private boolean startsWith(byte[] bytes, byte[] magic) {
        if (bytes.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) if (bytes[i] != magic[i]) return false;
        return true;
    }

    private void removeAfterCommit(String objectKey) {
        if (!managedAvatarKey(objectKey)) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            objectStorage.removeQuietly(objectKey);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                objectStorage.removeQuietly(objectKey);
            }
        });
    }

    private void removeAfterRollback(String objectKey) {
        if (!managedAvatarKey(objectKey) || !TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    objectStorage.removeQuietly(objectKey);
                }
            }
        });
    }

    private boolean managedAvatarKey(String objectKey) {
        return StringUtils.hasText(objectKey) && objectKey.startsWith("users/") && objectKey.contains("/avatar/");
    }

    private String avatarUrl(String storedValue) {
        if (storedValue.startsWith("https://") || storedValue.startsWith("http://")) return storedValue;
        return objectStorage.presignGet(storedValue, Duration.ofHours(24));
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public record UpdateNicknameRequest(String expectedNickname, String nickname) { }
    private record AvatarFile(byte[] bytes, String contentType, String extension) { }
}
