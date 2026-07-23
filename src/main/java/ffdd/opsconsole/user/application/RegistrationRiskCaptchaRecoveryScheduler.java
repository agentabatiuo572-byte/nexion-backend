package ffdd.opsconsole.user.application;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.time.Clock;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RegistrationRiskCaptchaRecoveryScheduler {
    private final PlatformConfigFacade configFacade;
    private final AuditLogService auditLogService;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${nexion.user.captcha-recovery-delay-ms:15000}")
    @Transactional(rollbackFor = Exception.class)
    public void restoreExpiredWindow() {
        String observed = configFacade.activeValue(RegistrationRiskCaptchaWindow.CONFIG_KEY).orElse("");
        if (!RegistrationRiskCaptchaWindow.state(observed, clock).requiresPersistentRestore()) return;

        long currentVersion = parseVersion(configFacade
                .activeValueForUpdate(RegistrationRiskCaptchaWindow.VERSION_KEY)
                .orElse("0"));
        String raw = configFacade.activeValueForUpdate(RegistrationRiskCaptchaWindow.CONFIG_KEY).orElse("");
        RegistrationRiskCaptchaWindow.State state = RegistrationRiskCaptchaWindow.state(raw, clock);
        if (!state.requiresPersistentRestore()) return;
        long nextVersion = Math.addExact(currentVersion, 1L);
        configFacade.upsertAdminValue(
                RegistrationRiskCaptchaWindow.CONFIG_KEY,
                "",
                "STRING",
                "auth",
                "C6 automatic CAPTCHA recovery");
        configFacade.upsertAdminValue(
                RegistrationRiskCaptchaWindow.VERSION_KEY,
                String.valueOf(nextVersion),
                "NUMBER",
                "auth",
                "C6 automatic CAPTCHA recovery");
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("C6_CAPTCHA_AUTOMATICALLY_RESTORED")
                .resourceType("AUTH_CONFIG")
                .resourceId("captchaOff")
                .bizNo("captchaOff")
                .actorType("SYSTEM")
                .actorUsername("system")
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(Map.of(
                        "configKey", RegistrationRiskCaptchaWindow.CONFIG_KEY,
                        "previousDeadline", raw,
                        "restoredAt", clock.instant().toString(),
                        "beforeVersion", currentVersion,
                        "afterVersion", nextVersion,
                        "reason", state.malformed() ? "MALFORMED_DEADLINE_FAIL_CLOSED" : "DEADLINE_EXPIRED"))
                .build());
    }

    private long parseVersion(String raw) {
        try {
            return Long.parseLong(raw == null ? "" : raw.trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
