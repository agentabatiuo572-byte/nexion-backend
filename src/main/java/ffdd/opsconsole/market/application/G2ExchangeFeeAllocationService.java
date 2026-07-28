package ffdd.opsconsole.market.application;

import ffdd.opsconsole.market.mapper.AppExchangeMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class G2ExchangeFeeAllocationService {
    private static final BigDecimal BURN_RATIO = new BigDecimal("0.30");
    private final AppExchangeMapper mapper;

    @Transactional(rollbackFor = Exception.class)
    public Allocation allocate(String exchangeNo, BigDecimal feeUsdt, BigDecimal priceUsdt) {
        BigDecimal total = money(feeUsdt);
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            return new Allocation(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        if (priceUsdt == null || priceUsdt.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(503, "G3_PRICE_UNAVAILABLE");
        }
        BigDecimal burnPool = total.multiply(BURN_RATIO).setScale(6, RoundingMode.DOWN);
        BigDecimal feeBuffer = total.subtract(burnPool).setScale(6, RoundingMode.UNNECESSARY);
        BigDecimal nexEquivalent = burnPool.divide(priceUsdt, 6, RoundingMode.DOWN);

        AppExchangeMapper.FeeAllocationRow existing = mapper.feeAllocation(exchangeNo);
        if (existing != null) {
            if (money(existing.totalFeeUsdt()).compareTo(total) != 0
                    || money(existing.burnPoolUsdt()).compareTo(burnPool) != 0
                    || money(existing.feeBufferUsdt()).compareTo(feeBuffer) != 0
                    || existing.priceUsdt() == null || existing.priceUsdt().compareTo(priceUsdt) != 0) {
                throw new BizException(409, "EXCHANGE_FEE_ALLOCATION_CONFLICT");
            }
            return new Allocation(total, burnPool, feeBuffer);
        }

        AppExchangeMapper.BalanceVersion burnAccount = mapper.lockBuybackBurnPool();
        AppExchangeMapper.BalanceVersion bufferAccount = mapper.lockFeeBuffer();
        if (burnAccount == null || bufferAccount == null) {
            throw new BizException(503, "EXCHANGE_FEE_ACCOUNT_UNAVAILABLE");
        }
        BigDecimal burnAfter = money(burnAccount.balanceUsdt()).add(burnPool);
        BigDecimal bufferAfter = money(bufferAccount.balanceUsdt()).add(feeBuffer);
        if (mapper.updateBuybackBurnPool(burnAfter, burnAccount.version()) != 1
                || mapper.updateFeeBuffer(bufferAfter, bufferAccount.version()) != 1) {
            throw new BizException(409, "EXCHANGE_FEE_ACCOUNT_CONFLICT");
        }
        if (mapper.insertBuybackBurnPoolLedger(new AppExchangeMapper.BuybackBurnLedgerWrite(
                exchangeNo + "-BURN30", exchangeNo, burnPool, burnAfter, nexEquivalent,
                priceUsdt, exchangeNo + ":burn30")) != 1
                || mapper.insertExchangeFeeBufferLedger(new AppExchangeMapper.FeeBufferLedgerWrite(
                        exchangeNo + "-BUFFER70", exchangeNo, feeBuffer, bufferAfter,
                        exchangeNo + ":buffer70")) != 1
                || mapper.insertFeeAllocation(new AppExchangeMapper.FeeAllocationWrite(
                        exchangeNo, total, burnPool, feeBuffer, priceUsdt, nexEquivalent)) != 1) {
            throw new BizException(409, "EXCHANGE_FEE_ALLOCATION_CONFLICT");
        }
        return new Allocation(total, burnPool, feeBuffer);
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(6, RoundingMode.HALF_UP);
    }

    public record Allocation(BigDecimal totalFeeUsdt, BigDecimal burnPoolUsdt, BigDecimal feeBufferUsdt) {}
}
