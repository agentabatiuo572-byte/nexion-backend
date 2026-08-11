package ffdd.opsconsole.user.application;

import ffdd.opsconsole.finance.application.PaymentMethodProviderProperties;
import ffdd.opsconsole.finance.application.PaymentMethodSandboxProfileGuard;
import ffdd.opsconsole.user.mapper.UserPaymentMethodMapper;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentMethodRevocationScheduler {
    private final UserPaymentMethodMapper mapper;
    private final PaymentMethodProviderProperties properties;
    private final PaymentMethodRevocationFinalizer finalizer;
    private final PaymentMethodSandboxProfileGuard sandboxGuard;

    @Scheduled(fixedDelayString = "${nexion.finance.payment-method-provider.revoke-poll-ms:60000}")
    public void process() {
        String sourceEnvironment = sandboxGuard.sourceEnvironment();
        for (var command : mapper.claimableRevokeCommands(sourceEnvironment)) {
            // A single SQL CAS is the multi-instance ownership boundary. Expired PROCESSING
            // leases are deliberately claimable so a crashed worker cannot strand a command.
            if (mapper.claimRevokeCommand(command.commandNo(), sourceEnvironment) != 1) {
                continue;
            }
            if (properties.getMode() != PaymentMethodProviderProperties.Mode.PROVIDER) {
                failOrRetry(command, "PAYMENT_METHOD_PROVIDER_VERIFIER_UNAVAILABLE");
                continue;
            }
            // A real provider adapter is mandatory in PROVIDER mode. Until one is installed,
            // remain fail-closed and drive the durable command to an observable terminal failure.
            failOrRetry(command, "PAYMENT_METHOD_PROVIDER_REVOKE_ADAPTER_UNAVAILABLE");
        }
    }

    private void failOrRetry(UserPaymentMethodMapper.RevokeCommandRow command, String error) {
        boolean expired = command.deadlineAt() == null || !command.deadlineAt().isAfter(LocalDateTime.now());
        if (!expired) {
            mapper.releaseRevokeRetry(command.commandNo(), error, command.sourceEnvironment());
            return;
        }
        finalizer.fail(command, error);
    }
}
