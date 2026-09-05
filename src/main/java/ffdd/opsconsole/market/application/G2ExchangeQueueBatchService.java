package ffdd.opsconsole.market.application;

import ffdd.opsconsole.emergency.domain.KillSwitchState;
import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import ffdd.opsconsole.market.mapper.AppExchangeMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.core.env.Environment;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class G2ExchangeQueueBatchService {
    private static final String EXCHANGE_KILL = "killswitch.exchange";
    private static final String EXCHANGE_KILL_LEGACY = "emergency.killswitch.exchange";
    private static final String EXCHANGE_EXECUTION_MUTEX = "G2_EXCHANGE_EXECUTION";
    private final AppExchangeMapper mapper;
    private final PlatformConfigFacade config;
    private final EventOutboxService outbox;
    private final G2ExchangeFeeAllocationService feeAllocationService;
    private final Environment environment;

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Map<String,Object> process(int requestedLimit) {
        requireProductionRuntime();
        int limit = Math.max(1,Math.min(requestedLimit,100));
        if (!EXCHANGE_EXECUTION_MUTEX.equals(mapper.lockExchangeExecutionMutex())) {
            throw new BizException(503,"G2_EXECUTION_MUTEX_UNAVAILABLE");
        }
        if (!swapEnabled()) {
            throw new BizException(409,"EXCHANGE_SWAP_PAUSED");
        }
        BigDecimal price = mapper.currentPrice();
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) throw new BizException(503,"G3_PRICE_UNAVAILABLE");
        BigDecimal platformUsed = nz(mapper.platformTodayUsdt());
        List<Map<String,Object>> completed = new ArrayList<>();
        List<Map<String,Object>> skipped = new ArrayList<>();
        List<Map<String,Object>> failed = new ArrayList<>();
        List<AppExchangeMapper.QueuedRow> rows = mapper.lockQueuedBatch(limit);
        for (AppExchangeMapper.QueuedRow row : rows) {
            if (!swapEnabled()) {
                skipped.add(item(row.exchangeNo(),"SKIPPED","QUEUED","EXCHANGE_SWAP_PAUSED","兑换已熔断，本单仍在队列"));
                continue;
            }
            if (mapper.lockActiveUserNo(row.userId()) == null) {
                skipped.add(item(row.exchangeNo(),"SKIPPED","QUEUED","USER_INACTIVE","用户已停用，本单仍在队列"));
                continue;
            }
            BigDecimal gross = money("USDT".equals(row.fromAsset()) ? row.fromAmount() : row.fromAmount().multiply(price));
            AppExchangeMapper.WalletGateRow wallet = mapper.lockWalletGate(row.userId());
            if (wallet == null) { skipped.add(item(row.exchangeNo(),"SKIPPED","QUEUED","WALLET_UNAVAILABLE","用户钱包不可用，本单仍在队列")); continue; }
            if (platformUsed.add(gross).compareTo(number("wallet.exchange.platform_daily_cap_usdt","20000")) > 0) {
                skipped.add(item(row.exchangeNo(),"SKIPPED","QUEUED","PLATFORM_CAP_EXCEEDED","平台当日额度已用尽，本单仍在队列")); continue;
            }
            if (nz(mapper.userTodayUsdt(row.userId())).add(gross)
                    .compareTo(number("wallet.exchange.user_daily_cap_usdt","50")) > 0) {
                skipped.add(item(row.exchangeNo(),"SKIPPED","QUEUED","USER_CAP_EXCEEDED","用户当日额度已用尽，本单仍在队列")); continue;
            }
            if (mapper.geoBlocked(wallet.countryCode()) > 0) {
                if (mapper.cancelQueuedBySystem(row.exchangeNo()) != 1) throw new BizException(409,"EXCHANGE_QUEUE_STATE_CONFLICT");
                refundReservation(row, wallet);
                outbox.publish("EXCHANGE_ORDER",row.exchangeNo(),"exchange.queue_cancelled",
                        linked("exchangeNo",row.exchangeNo(),"status","CANCELLED","cancelledBy","SYSTEM_G2_BATCH","reasonCode","GEO_BLOCKED"));
                skipped.add(item(row.exchangeNo(),"SKIPPED","CANCELLED","GEO_BLOCKED","所在地域已封锁，本单已取消")); continue;
            }
            BigDecimal feeRate = number("wallet.exchange.fee_pct","0");
            BigDecimal fee = feeRate.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO : money(gross.multiply(feeRate)
                    .divide(BigDecimal.valueOf(100),12,RoundingMode.HALF_UP)).max(money(number("wallet.exchange.fee_min_usdt","0.50")));
            if (fee.compareTo(gross) >= 0) {
                skipped.add(item(row.exchangeNo(),"SKIPPED","QUEUED","FEE_EXCEEDS_AMOUNT","手续费不低于兑换金额，本单仍在队列")); continue;
            }
            BigDecimal net = money(gross.subtract(fee));
            String toAsset = "USDT".equals(row.fromAsset()) ? "NEX" : "USDT";
            BigDecimal toAmount = "NEX".equals(toAsset) ? net.divide(price,6,RoundingMode.DOWN) : net;
            boolean sourceReserved = mapper.sourceReservationExists(row.exchangeNo()) > 0;
            BigDecimal usdtDelta = "USDT".equals(row.fromAsset())
                    ? (sourceReserved ? BigDecimal.ZERO : row.fromAmount().negate()) : toAmount;
            BigDecimal nexDelta = "NEX".equals(row.fromAsset())
                    ? (sourceReserved ? BigDecimal.ZERO : row.fromAmount().negate()) : toAmount;
            if (mapper.applyWalletDelta(row.userId(),usdtDelta,nexDelta) != 1) {
                if (mapper.failQueuedInsufficient(row.exchangeNo()) != 1) throw new BizException(409,"EXCHANGE_QUEUE_STATE_CONFLICT");
                failed.add(item(row.exchangeNo(),"FAILED","FAILED","WALLET_BALANCE_CONFLICT","钱包余额不足或状态已变化，本单已终止")); continue;
            }
            if (mapper.completeQueued(row.exchangeNo(),toAmount,price) != 1) throw new BizException(409,"EXCHANGE_QUEUE_STATE_CONFLICT");
            BigDecimal fromAfter = "USDT".equals(row.fromAsset())
                    ? (sourceReserved ? wallet.usdtAvailable() : wallet.usdtAvailable().subtract(row.fromAmount()))
                    : (sourceReserved ? wallet.nexAvailable() : wallet.nexAvailable().subtract(row.fromAmount()));
            BigDecimal toAfter = "USDT".equals(toAsset) ? wallet.usdtAvailable().add(toAmount) : wallet.nexAvailable().add(toAmount);
            if ((!sourceReserved && mapper.insertLedger(new AppExchangeMapper.LedgerWrite(row.userId(),row.exchangeNo()+"-OUT",row.fromAsset(),"OUT",row.fromAmount(),money(fromAfter),"G2 queued swap debit")) != 1)
                    || mapper.insertLedger(new AppExchangeMapper.LedgerWrite(row.userId(),row.exchangeNo()+"-IN",toAsset,"IN",toAmount,money(toAfter),"G2 queued swap credit")) != 1)
                throw new BizException(409,"EXCHANGE_LEDGER_CONFLICT");
            feeAllocationService.allocate(row.exchangeNo(),fee,price);
            AppExchangeMapper.UserAttribution a = mapper.userAttribution(row.userId());
            if (a == null) throw new BizException(409,"USER_EVENT_ATTRIBUTION_UNAVAILABLE");
            outbox.publishUserEvent("EXCHANGE_ORDER",row.exchangeNo(),"exchange.swapped",row.userId(),phase(a.phase()),
                    a.accountAgeMonths(),a.cohort(),linked("exchangeNo",row.exchangeNo(),"fromAsset",row.fromAsset(),
                            "toAsset",toAsset,"fromAmount",row.fromAmount(),"toAmount",toAmount,"rate",price,
                            "grossUsdt",gross,"feeUsdt",fee,"status","COMPLETED"));
            platformUsed = platformUsed.add(gross);
            completed.add(item(row.exchangeNo(),"COMPLETED","COMPLETED","",""));
        }
        int remainingQueuedCount = mapper.countQueued();
        String outcome = completed.isEmpty() && skipped.isEmpty() && failed.isEmpty()
                ? (remainingQueuedCount > 0 ? "BUSY" : "EMPTY")
                : !completed.isEmpty() && (!skipped.isEmpty() || !failed.isEmpty()) ? "PARTIAL"
                : !failed.isEmpty() ? "FAILED" : !skipped.isEmpty() ? "SKIPPED" : "COMPLETED";
        return linked("requestedLimit",limit,"selectedCount",rows.size(),"completedCount",completed.size(),"completed",completed,
                "skippedCount",skipped.size(),"skipped",skipped,"failedCount",failed.size(),"failed",failed,
                "remainingQueuedCount",remainingQueuedCount,"outcome",outcome);
    }

    private BigDecimal number(String key,String fallback) { try { return new BigDecimal(config.activeValue(key).orElse(fallback)); }
        catch(RuntimeException ex){ throw new BizException(503,"EXCHANGE_CONFIG_INVALID:"+key); } }
    private BigDecimal nz(BigDecimal v){ return v==null?BigDecimal.ZERO:v; }
    private BigDecimal money(BigDecimal v){ return nz(v).setScale(6,RoundingMode.HALF_UP); }
    private String phase(String v){ String p=v==null?"P1":v.trim().toUpperCase(Locale.ROOT); return p.matches("P[1-6]")?p:"P1"; }
    private boolean swapEnabled() {
        return KillSwitchState.enabled(java.util.Optional.ofNullable(mapper.emergencyValue(EXCHANGE_KILL)),
                java.util.Optional.ofNullable(mapper.emergencyValue(EXCHANGE_KILL_LEGACY)));
    }

    private void refundReservation(AppExchangeMapper.QueuedRow row, AppExchangeMapper.WalletGateRow wallet) {
        if (mapper.sourceReservationExists(row.exchangeNo()) <= 0) return;
        BigDecimal usdtRefund = "USDT".equals(row.fromAsset()) ? row.fromAmount() : BigDecimal.ZERO;
        BigDecimal nexRefund = "NEX".equals(row.fromAsset()) ? row.fromAmount() : BigDecimal.ZERO;
        if (mapper.applyWalletDelta(row.userId(), usdtRefund, nexRefund) != 1) {
            throw new BizException(409,"EXCHANGE_QUEUE_REFUND_CONFLICT");
        }
        BigDecimal after = "USDT".equals(row.fromAsset())
                ? wallet.usdtAvailable().add(row.fromAmount()) : wallet.nexAvailable().add(row.fromAmount());
        if (mapper.insertLedger(new AppExchangeMapper.LedgerWrite(row.userId(),row.exchangeNo()+"-REFUND",
                row.fromAsset(),"IN",row.fromAmount(),money(after),"G2 queued swap source reservation refund")) != 1) {
            throw new BizException(409,"EXCHANGE_LEDGER_CONFLICT");
        }
    }
    private void requireProductionRuntime() {
        String[] profiles = environment == null ? new String[0] : environment.getActiveProfiles();
        String[] normalized = profiles == null ? new String[0] : java.util.Arrays.stream(profiles)
                .map(value -> value == null ? "" : value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank()).toArray(String[]::new);
        if (FundsSandboxProfileGuard.isStrictIsolatedProfile(normalized)) {
            throw new BizException(503,"EXCHANGE_SANDBOX_ISOLATED_TABLE_UNAVAILABLE");
        }
        if (normalized.length != 0 && !(normalized.length == 1
                && java.util.Set.of("dev", "prod").contains(normalized[0]))) {
            throw new BizException(503,"EXCHANGE_RUNTIME_PROFILE_UNSUPPORTED");
        }
    }
    private Map<String,Object> item(String exchangeNo,String status,String orderStatus,String reasonCode,String reason) {
        return linked("exchangeNo",exchangeNo,"status",status,"orderStatus",orderStatus,"reasonCode",reasonCode,"reason",reason);
    }
    private Map<String,Object> linked(Object... values){ Map<String,Object> map=new LinkedHashMap<>(); for(int i=0;i<values.length;i+=2)map.put(String.valueOf(values[i]),values[i+1]); return map; }
}
