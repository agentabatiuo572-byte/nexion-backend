package ffdd.wallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.wallet.chain.WithdrawalChainBroadcaster;
import ffdd.wallet.domain.WithdrawalOrder;
import ffdd.wallet.mapper.WithdrawalOrderMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class WithdrawalBroadcastService {
    private static final String STATUS_PENDING_CHAIN = "PENDING_CHAIN";
    private static final String STATUS_CHAIN_SUBMITTED = "CHAIN_SUBMITTED";
    private static final String STATUS_DEAD = "DEAD";

    private final WithdrawalOrderMapper withdrawalOrderMapper;
    private final WithdrawalChainBroadcaster broadcaster;
    private final int maxRetries;
    private final long initialBackoffSeconds;

    public WithdrawalBroadcastService(
            WithdrawalOrderMapper withdrawalOrderMapper,
            WithdrawalChainBroadcaster broadcaster,
            @Value("${nexion.wallet.withdrawal.broadcast.max-retries:5}") int maxRetries,
            @Value("${nexion.wallet.withdrawal.broadcast.initial-backoff-seconds:60}") long initialBackoffSeconds) {
        this.withdrawalOrderMapper = withdrawalOrderMapper;
        this.broadcaster = broadcaster;
        this.maxRetries = Math.max(1, maxRetries);
        this.initialBackoffSeconds = Math.max(1, initialBackoffSeconds);
    }

    public WithdrawalBroadcastResponse broadcastPending(int limit) {
        List<WithdrawalOrder> orders = listDuePending(limit);
        int submitted = 0;
        int failed = 0;
        int dead = 0;
        for (WithdrawalOrder order : orders) {
            try {
                String txHash = broadcaster.broadcast(order);
                if (!StringUtils.hasText(txHash)) {
                    throw new IllegalStateException("Withdrawal broadcaster returned empty tx hash");
                }
                markSubmitted(order, txHash);
                submitted++;
            } catch (RuntimeException ex) {
                if (markFailure(order, ex)) {
                    dead++;
                } else {
                    failed++;
                }
            }
        }
        return new WithdrawalBroadcastResponse(orders.size(), submitted, failed, dead);
    }

    public List<WithdrawalOrder> listPending(int limit) {
        return withdrawalOrderMapper.selectList(new LambdaQueryWrapper<WithdrawalOrder>()
                .eq(WithdrawalOrder::getStatus, STATUS_PENDING_CHAIN)
                .eq(WithdrawalOrder::getIsDeleted, 0)
                .orderByAsc(WithdrawalOrder::getCreatedAt)
                .last("LIMIT " + normalizeLimit(limit)));
    }

    public List<WithdrawalOrder> listDead(int limit) {
        return withdrawalOrderMapper.selectList(new LambdaQueryWrapper<WithdrawalOrder>()
                .eq(WithdrawalOrder::getStatus, STATUS_DEAD)
                .eq(WithdrawalOrder::getIsDeleted, 0)
                .orderByDesc(WithdrawalOrder::getBroadcastDeadAt)
                .last("LIMIT " + normalizeLimit(limit)));
    }

    public Map<String, Object> summary() {
        long pending = withdrawalOrderMapper.selectCount(new LambdaQueryWrapper<WithdrawalOrder>()
                .eq(WithdrawalOrder::getStatus, STATUS_PENDING_CHAIN)
                .eq(WithdrawalOrder::getIsDeleted, 0));
        long submitted = withdrawalOrderMapper.selectCount(new LambdaQueryWrapper<WithdrawalOrder>()
                .eq(WithdrawalOrder::getStatus, STATUS_CHAIN_SUBMITTED)
                .eq(WithdrawalOrder::getIsDeleted, 0));
        long dead = withdrawalOrderMapper.selectCount(new LambdaQueryWrapper<WithdrawalOrder>()
                .eq(WithdrawalOrder::getStatus, STATUS_DEAD)
                .eq(WithdrawalOrder::getIsDeleted, 0));
        return Map.of("pending", pending, "submitted", submitted, "dead", dead);
    }

    private List<WithdrawalOrder> listDuePending(int limit) {
        LocalDateTime now = LocalDateTime.now();
        return withdrawalOrderMapper.selectList(new LambdaQueryWrapper<WithdrawalOrder>()
                .eq(WithdrawalOrder::getStatus, STATUS_PENDING_CHAIN)
                .eq(WithdrawalOrder::getIsDeleted, 0)
                .and(wrapper -> wrapper
                        .isNull(WithdrawalOrder::getNextBroadcastAt)
                        .or()
                        .le(WithdrawalOrder::getNextBroadcastAt, now))
                .orderByAsc(WithdrawalOrder::getCreatedAt)
                .last("LIMIT " + normalizeLimit(limit)));
    }

    private void markSubmitted(WithdrawalOrder order, String txHash) {
        WithdrawalOrder patch = new WithdrawalOrder();
        patch.setId(order.getId());
        patch.setStatus(STATUS_CHAIN_SUBMITTED);
        patch.setChainTxHash(txHash);
        patch.setChainSubmittedAt(LocalDateTime.now());
        patch.setChainBroadcastAttempts(attempts(order) + 1);
        withdrawalOrderMapper.updateById(patch);
    }

    private boolean markFailure(WithdrawalOrder order, RuntimeException ex) {
        int attempts = attempts(order) + 1;
        boolean dead = attempts >= maxRetries;
        WithdrawalOrder patch = new WithdrawalOrder();
        patch.setId(order.getId());
        patch.setChainBroadcastAttempts(attempts);
        patch.setLastBroadcastError(truncate(ex.getMessage(), 512));
        if (dead) {
            patch.setStatus(STATUS_DEAD);
            patch.setBroadcastDeadAt(LocalDateTime.now());
        } else {
            patch.setNextBroadcastAt(LocalDateTime.now().plusSeconds(backoffSeconds(attempts)));
        }
        withdrawalOrderMapper.updateById(patch);
        return dead;
    }

    private int attempts(WithdrawalOrder order) {
        return order.getChainBroadcastAttempts() == null ? 0 : order.getChainBroadcastAttempts();
    }

    private long backoffSeconds(int attempts) {
        int exponent = Math.min(Math.max(0, attempts - 1), 10);
        return initialBackoffSeconds * (1L << exponent);
    }

    private int normalizeLimit(int limit) {
        if (limit < 1) {
            return 20;
        }
        return Math.min(limit, 100);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "unknown error";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
