package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.NotificationPreferenceView;
import ffdd.opsconsole.content.mapper.NotificationPreferenceMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationPreferenceService {
    private final NotificationPreferenceMapper mapper;

    @Transactional(readOnly = true)
    public ApiResult<NotificationPreferenceView> get(Long userId) {
        if (!validUser(userId)) return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        NotificationPreferenceView current = mapper.findByUserId(userId);
        return ApiResult.ok(current == null ? NotificationPreferenceView.allEnabled(userId) : current);
    }

    @Transactional
    public ApiResult<NotificationPreferenceView> patch(Long userId, PatchRequest request) {
        if (!validUser(userId)) return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        if (request == null || request.isEmpty()) {
            return ApiResult.fail(422, "NOTIFICATION_PREFERENCES_PATCH_EMPTY");
        }
        // Nullable fields are intentional: the mapper performs one SQL statement
        // and leaves omitted columns untouched in the duplicate-key branch.
        if (request.earningPush() == null) {
            mapper.upsert(userId, request.commission(), request.team(), request.staking(),
                    request.market(), request.genesis(), request.system());
        } else {
            mapper.upsert(userId, request.commission(), request.team(), request.staking(),
                    request.market(), request.genesis(), request.system(), request.earningPush().name());
        }
        NotificationPreferenceView canonical = mapper.findByUserId(userId);
        return ApiResult.ok(canonical == null
                ? NotificationPreferenceView.allEnabled(userId)
                : canonical);
    }

    private boolean validUser(Long userId) {
        return userId != null && userId > 0;
    }

    public record PatchRequest(
            Boolean commission,
            Boolean team,
            Boolean staking,
            Boolean market,
            Boolean genesis,
            Boolean system,
            NotificationPreferenceView.EarningPush earningPush) {
        public PatchRequest(Boolean commission, Boolean team, Boolean staking, Boolean market,
                Boolean genesis, Boolean system) {
            this(commission, team, staking, market, genesis, system, null);
        }
        public boolean isEmpty() {
            return commission == null && team == null && staking == null
                    && market == null && genesis == null && system == null
                    && earningPush == null;
        }
    }
}
