package ffdd.opsconsole.market.application;

import ffdd.opsconsole.emergency.domain.KillSwitchState;
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
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class G2ExchangeQueueBatchService {
    private static final String EXCHANGE_KILL = "killswitch.exchange";
    private static final String EXCHANGE_KILL_LEGACY = "emergency.killswitch.exchange";
    private final AppExchangeMapper mapper;
    private final PlatformConfigFacade config;
    private final EventOutboxService outbox;

    @Transactional(rollbackFor = Exception.class)
    public Map<String,Object> process(int requestedLimit) {
        int limit = Math.max(1,Math.min(requestedLimit,100));
        if (!KillSwitchState.enabled(java.util.Optional.ofNullable(mapper.emergencyValue(EXCHANGE_KILL)),
                java.util.Optional.ofNullable(mapper.emergencyValue(EXCHANGE_KILL_LEGACY)))) {
            throw new BizException(409,"EXCHANGE_SWAP_PAUSED");
        }
        BigDecimal price = mapper.currentPrice();
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) throw new BizException(503,"G3_PRICE_UNAVAILABLE");
        BigDecimal platformCap = number("wallet.exchange.platform_daily_cap_usdt","20000");
        BigDecimal platformUsed = nz(mapper.platformTodayUsdt());
        List<String> completed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (AppExchangeMapper.QueuedRow row : mapper.lockQueuedBatch(limit)) {
            BigDecimal gross = money("USDT".equals(row.fromAsset()) ? row.fromAmount() : row.fromAmount().multiply(price));
            AppExchangeMapper.WalletGateRow wallet = mapper.lockWalletGate(row.userId());
            if (wallet == null || platformUsed.add(gross).compareTo(platformCap) > 0
                    || nz(mapper.userTodayUsdt(row.userId())).add(gross).compareTo(number("wallet.exchange.user_daily_cap_usdt","50")) > 0
                    || (!List.of("VERIFIED","APPROVED","PASSED").contains(wallet.kycStatus())
                        && nz(mapper.userLifetimeUsdt(row.userId())).add(gross)
                            .compareTo(number("wallet.exchange.kyc_threshold_usdt","100")) >= 0)
                    || mapper.geoBlocked(wallet.countryCode()) > 0) {
                skipped.add(row.exchangeNo()); continue;
            }
            BigDecimal feeRate = number("wallet.exchange.fee_pct","0");
            BigDecimal fee = feeRate.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO : money(gross.multiply(feeRate)
                    .divide(BigDecimal.valueOf(100),12,RoundingMode.HALF_UP)).max(money(number("wallet.exchange.fee_min_usdt","0.50")));
            if (fee.compareTo(gross) >= 0) { skipped.add(row.exchangeNo()); continue; }
            BigDecimal net = money(gross.subtract(fee));
            String toAsset = "USDT".equals(row.fromAsset()) ? "NEX" : "USDT";
            BigDecimal toAmount = "NEX".equals(toAsset) ? net.divide(price,6,RoundingMode.DOWN) : net;
            BigDecimal usdtDelta = "USDT".equals(row.fromAsset()) ? row.fromAmount().negate() : toAmount;
            BigDecimal nexDelta = "NEX".equals(row.fromAsset()) ? row.fromAmount().negate() : toAmount;
            if (mapper.applyWalletDelta(row.userId(),usdtDelta,nexDelta) != 1) { skipped.add(row.exchangeNo()); continue; }
            if (mapper.completeQueued(row.exchangeNo(),toAmount,price) != 1) throw new BizException(409,"EXCHANGE_QUEUE_STATE_CONFLICT");
            BigDecimal fromAfter = "USDT".equals(row.fromAsset()) ? wallet.usdtAvailable().subtract(row.fromAmount()) : wallet.nexAvailable().subtract(row.fromAmount());
            BigDecimal toAfter = "USDT".equals(toAsset) ? wallet.usdtAvailable().add(toAmount) : wallet.nexAvailable().add(toAmount);
            if (mapper.insertLedger(new AppExchangeMapper.LedgerWrite(row.userId(),row.exchangeNo()+"-OUT",row.fromAsset(),"OUT",row.fromAmount(),money(fromAfter),"G2 queued swap debit")) != 1
                    || mapper.insertLedger(new AppExchangeMapper.LedgerWrite(row.userId(),row.exchangeNo()+"-IN",toAsset,"IN",toAmount,money(toAfter),"G2 queued swap credit")) != 1)
                throw new BizException(409,"EXCHANGE_LEDGER_CONFLICT");
            AppExchangeMapper.UserAttribution a = mapper.userAttribution(row.userId());
            if (a == null) throw new BizException(409,"USER_EVENT_ATTRIBUTION_UNAVAILABLE");
            outbox.publishUserEvent("EXCHANGE_ORDER",row.exchangeNo(),"exchange.swapped",row.userId(),phase(a.phase()),
                    a.accountAgeMonths(),a.cohort(),linked("exchangeNo",row.exchangeNo(),"fromAsset",row.fromAsset(),
                            "toAsset",toAsset,"fromAmount",row.fromAmount(),"toAmount",toAmount,"rate",price,
                            "grossUsdt",gross,"feeUsdt",fee,"status","COMPLETED"));
            platformUsed = platformUsed.add(gross);
            completed.add(row.exchangeNo());
        }
        return linked("completedCount",completed.size(),"completed",completed,"skippedCount",skipped.size(),"skipped",skipped);
    }

    private BigDecimal number(String key,String fallback) { try { return new BigDecimal(config.activeValue(key).orElse(fallback)); }
        catch(RuntimeException ex){ throw new BizException(503,"EXCHANGE_CONFIG_INVALID:"+key); } }
    private BigDecimal nz(BigDecimal v){ return v==null?BigDecimal.ZERO:v; }
    private BigDecimal money(BigDecimal v){ return nz(v).setScale(6,RoundingMode.HALF_UP); }
    private String phase(String v){ String p=v==null?"P1":v.trim().toUpperCase(Locale.ROOT); return p.matches("P[1-6]")?p:"P1"; }
    private Map<String,Object> linked(Object... values){ Map<String,Object> map=new LinkedHashMap<>(); for(int i=0;i<values.length;i+=2)map.put(String.valueOf(values[i]),values[i+1]); return map; }
}
