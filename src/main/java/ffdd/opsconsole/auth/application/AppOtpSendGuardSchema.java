package ffdd.opsconsole.auth.application;

import ffdd.opsconsole.auth.mapper.UserLoginGuardMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Performs the OTP send-rate schema upgrade once, before any sender uses the guard. */
@Component
@RequiredArgsConstructor
class AppOtpSendGuardSchema {
    private final UserLoginGuardMapper mapper;

    @PostConstruct
    void ensureSchema() {
        mapper.createOtpSendGuardTable();
        if (mapper.countOtpSendGuardLegacyWindowColumn() == 0) {
            mapper.addOtpSendGuardLegacyWindowColumn();
        }
        if (mapper.countOtpSendGuardDateWindowColumn() > 0) {
            mapper.preserveLegacyOtpSendGuardQuota();
            mapper.migrateOtpSendGuardWindowToTimestamp();
        }
        mapper.createOtpSendEventTable();
    }
}
