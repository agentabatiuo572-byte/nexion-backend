package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.mapper.WithdrawalPayoutMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Completes simulated withdrawals for development-only accounts without contacting a payout provider.
 * The provider executor selects only non-development users, so the two queues are disjoint.
 */
@Component
@Profile("dev & !prod")
@RequiredArgsConstructor
public class DevelopmentWithdrawalSettlementExecutor {
    private static final String SOURCE = "dev-simulator";

    private final WithdrawalPayoutMapper mapper;
    private final WithdrawalPayoutFinalizer finalizer;

    @Scheduled(fixedDelayString = "${nexion.finance.development-withdrawal-simulator-poll-ms:5000}")
    public void process() {
        LocalDateTime now = LocalDateTime.now();
        for (var candidate : mapper.claimableDevelopment(now, 50)) {
            if (mapper.claim(candidate.withdrawalNo(), now, now.plusMinutes(2)) != 1) continue;
            WithdrawalPayoutMapper.PayoutRow claimed = mapper.payout(candidate.withdrawalNo());
            if (claimed == null) continue;
            long providerCid = deterministicCid(claimed.withdrawalNo());
            if (finalizer.submitted(claimed, providerCid, SOURCE)) {
                finalizer.completeDevelopmentSimulation(claimed, providerCid);
            }
        }
    }

    private long deterministicCid(String withdrawalNo) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("development-withdrawal:" + withdrawalNo).getBytes(StandardCharsets.UTF_8));
            String firstFifteenHexDigits = HexFormat.of().formatHex(digest).substring(0, 15);
            return Long.parseLong(firstFifteenHexDigits, 16);
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA256_UNAVAILABLE", impossible);
        }
    }
}
