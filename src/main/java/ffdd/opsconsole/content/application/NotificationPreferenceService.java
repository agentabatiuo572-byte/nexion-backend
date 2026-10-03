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

    @Transactional
    public ApiResult<NotificationPreferenceView> get(Long userId) {
        if (!validUser(userId)) return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        NotificationPreferenceView current = mapper.findByUserId(userId);
        if (current != null && !current.system()) {
            mapper.upsert(userId, null, null, null, null, null, true);
            current = mandatory(current);
        }
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
        mapper.upsert(userId, request.commission(), request.team(), request.staking(),
                request.market(), request.genesis(), true);
        NotificationPreferenceView canonical = mapper.findByUserId(userId);
        return ApiResult.ok(canonical == null
                ? NotificationPreferenceView.allEnabled(userId)
                : mandatory(canonical));
    }

    private NotificationPreferenceView mandatory(NotificationPreferenceView row) {
        return new NotificationPreferenceView(row.userId(), row.commission(), row.team(), row.staking(),
                row.market(), row.genesis(), true);
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
            Boolean system) {
        public boolean isEmpty() {
            return commission == null && team == null && staking == null
                    && market == null && genesis == null && system == null;
        }
    }
}
