package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.ConversationUserClear;
import ffdd.opsconsole.content.mapper.AppConversationClearMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@ApplicationService
@RequiredArgsConstructor
public class AppConversationManagementService {
    private final AppConversationClearMapper mapper;
    private final SupportOwnershipService ownership;
    private final ProductionSupportPathGuard guard;
    private final AdminIdempotencyService idempotency;
    private final ObjectMapper json;
    private final AuditLogService audit;
    private final ApplicationEventPublisher events;

    public record Management(boolean clearUserHistory, List<ConversationUserClear> clearedPeers) {}
    public record ClearRequest(String scope, String peerKey, List<String> conversationIds,
            Map<String, Long> expectedVersions) {}
    public record UserViewCleared(Long userId, List<String> conversationNos) {}

    @Transactional(readOnly = true)
    public ApiResult<Management> management(Long userId) {
        if (userId == null || userId <= 0) return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
        guard.requireAllowed(userId);
        var cleared = mapper.markers(userId).stream().map(marker -> new ConversationUserClear(marker.peerKey(),
                marker.throughMessageId(), mapper.clearedConversations(userId, marker.peerKey()))).toList();
        return ApiResult.ok(new Management(true, cleared));
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    @SuppressWarnings({"rawtypes", "unchecked"})
    public ApiResult<ConversationUserClear> clear(Long userId, String conversationNo, String key, ClearRequest request) {
        if (userId == null || userId <= 0) return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
        guard.requireAllowed(userId);
        if (key == null || key.isBlank() || key.trim().length() < 8 || key.trim().length() > 128)
            return ApiResult.fail(400, "IDEMPOTENCY_KEY_REQUIRED");
        if (!valid(conversationNo, request)) return ApiResult.fail(422, "CONVERSATION_USER_CLEAR_INVALID");
        ApiResult<?> result = idempotency.executeRetained("APP_CONVERSATION_USER_CLEAR:" + userId, key.trim(),
                hash(conversationNo, request), ApiResult.class, (Supplier) () -> apply(userId, conversationNo, request));
        if (result.getCode() != 0) return ApiResult.fail(result.getCode(), result.getMessage());
        // A replay must return this command's original boundary, never clear a later incoming message.
        return ApiResult.ok(json.convertValue(result.getData(), ConversationUserClear.class));
    }

    private ApiResult<ConversationUserClear> apply(Long userId, String no, ClearRequest request) {
        // Same mutex/order as staff sends, APP sends, assignment changes and attachment delivery.
        ownership.lockCustomer(userId);
        var members = mapper.lockPeer(userId, request.peerKey());
        var byNo = new HashMap<String, AppConversationClearMapper.PeerRow>();
        for (var row : members) byNo.put(row.conversationNo(), row);
        if (!byNo.containsKey(no) || !byNo.keySet().containsAll(request.conversationIds()))
            return ApiResult.fail(404, "CONVERSATION_NOT_FOUND");
        for (String cid : request.conversationIds()) {
            if (!Objects.equals(byNo.get(cid).version(), request.expectedVersions().get(cid)))
                return ApiResult.fail(409, "CONVERSATION_USER_CLEAR_CONFLICT");
        }
        // Hidden legacy CIDs are still covered. Newly visible members require a refreshed confirmation.
        if (members.stream().anyMatch(row -> !row.archived()
                && (!row.userScopeCleared() || row.lastMessageId() > row.throughMessageId())
                && !request.conversationIds().contains(row.conversationNo())))
            return ApiResult.fail(409, "CONVERSATION_USER_CLEAR_MEMBERS_CHANGED");
        long through = members.stream().mapToLong(row -> Math.max(row.lastMessageId(), row.throughMessageId())).max().orElseThrow();
        mapper.advancePeer(userId, request.peerKey(), through);
        for (var row : members) mapper.advanceMember(userId, request.peerKey(), row.conversationNo(), through);
        var receipt = new ConversationUserClear(request.peerKey(), through,
                mapper.clearedConversations(userId, request.peerKey()));
        audit.recordRequired(AuditLogWriteRequest.builder().action("APP_CONVERSATION_USER_CLEARED")
                .resourceType("CONVERSATION").resourceId(no).bizNo(no).userId(userId).actorId(userId)
                .actorType("USER").actorUsername("user:" + userId).result("SUCCESS").riskLevel("MEDIUM")
                .detail(Map.of("scope", "USER_VIEW", "peerKey", request.peerKey(), "throughMessageId", through,
                        "conversationCount", receipt.conversationNos().size())).build());
        // Delivered only after commit by the websocket listener; staff history/receipts are not mutated.
        events.publishEvent(new UserViewCleared(userId, receipt.conversationNos()));
        return ApiResult.ok(receipt);
    }

    private boolean valid(String no, ClearRequest request) {
        if (no == null || no.isBlank() || no.length() > 40 || request == null || !"USER_VIEW".equals(request.scope())
                || request.peerKey() == null || !request.peerKey().matches("(?:agent:[1-9][0-9]{0,18}|conversation:[A-Za-z0-9_-]{1,40})")
                || request.conversationIds() == null || request.conversationIds().isEmpty()
                || request.conversationIds().size() > 1000 || request.expectedVersions() == null) return false;
        var unique = new HashSet<String>();
        for (String cid : request.conversationIds()) {
            if (cid == null || cid.isBlank() || cid.length() > 40 || !unique.add(cid)) return false;
            Long version = request.expectedVersions().get(cid);
            if (version == null || version < 0) return false;
        }
        return unique.contains(no) && unique.equals(request.expectedVersions().keySet());
    }

    private String hash(String no, ClearRequest request) {
        try {
            var canonical = List.of(no, request.scope(), request.peerKey(), new TreeMap<>(request.expectedVersions()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Unable to fingerprint conversation clear", ex);
        }
    }
}
