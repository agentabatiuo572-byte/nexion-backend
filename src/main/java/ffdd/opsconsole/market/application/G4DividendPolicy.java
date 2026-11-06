package ffdd.opsconsole.market.application;

import ffdd.opsconsole.market.mapper.AppGenesisMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The existing G4 payout formulas and the exact snapshot authorized by one A2 replay. */
@Component
@RequiredArgsConstructor
public class G4DividendPolicy {
    private final AppGenesisMapper mapper;
    private final TreasuryCoverageFacade coverage;
    private final ThreadLocal<Authorization> authorized = new ThreadLocal<>();

    public record State(String revision, AppGenesisMapper.SeriesRow series,
                        AppGenesisMapper.EmissionBatchRow batch, BigDecimal amount, int count) {}
    public record Change(String before, String after) {}
    private record Authorization(String action, String revision) {}

    @Transactional
    public State state(String batchNo) {
        var series = mapper.lockActiveSeries();
        if (series == null) throw new BizException(409, "G4_GENESIS_SERIES_NOT_FOUND");
        if (mapper.activeSeriesCount() != 1) throw new BizException(409, "GENESIS_ACTIVE_SERIES_AMBIGUOUS");
        if (batchNo == null) {
            return new State(hash(series.id()+"|"+series.seriesCode()+"|"+series.status()+"|"+number(series.dailyEmissionRatePct())),
                    series, null, BigDecimal.ZERO, 0);
        }
        if (!batchNo.matches("[A-Za-z0-9-]{3,32}")) throw new BizException(422, "G4_GENESIS_BATCH_NO_INVALID");
        var batch = mapper.lockEmissionBatch(batchNo);
        BigDecimal amount = BigDecimal.ZERO;
        int count = 0;
        StringBuilder detail = new StringBuilder(series.id()+"|"+series.seriesCode()+"|"+series.status()+"|"+batchNo+"|");
        if (batch == null) {
            detail.append(number(series.priceUsdt())).append('|').append(number(series.dailyEmissionRatePct()));
            // Preserve the existing selection/formula; cross-series scope is tracked separately as BUG-317.
            for (var holding : mapper.lockEmissionHoldings()) {
                detail.append('|').append(holding.id()).append(':').append(holding.holdingNo()).append(':')
                        .append(holding.userId()).append(':').append(holding.seriesCode());
                BigDecimal single = series.priceUsdt().multiply(series.dailyEmissionRatePct())
                        .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
                if (single.signum() > 0) { amount = amount.add(single); count++; }
            }
        } else {
            detail.append(mapper.emissionBatchSnapshot(batchNo));
            for (var item : mapper.lockPendingEmissionItems(batchNo)) {
                detail.append('|').append(item.id()).append(':').append(item.holdingNo()).append(':')
                        .append(item.userId()).append(':').append(number(item.amountUsdt())).append(':').append(item.status());
                amount = amount.add(item.amountUsdt()); count++;
            }
            if (!"COMPLETED".equals(batch.status()) && amount.signum() > 0
                    && (batch.dailyRatePct() == null || batch.dailyRatePct().signum() <= 0)) {
                throw new BizException(409, "G4_EMISSION_RATE_SNAPSHOT_INVALID");
            }
        }
        return new State(hash(detail.toString()), series, batch, amount, count);
    }

    public Change updateDividend(BigDecimal value) {
        State state = state(null);
        BigDecimal before = state.series().dailyEmissionRatePct();
        boolean amplifies = before.signum() == 0 || value.compareTo(before) > 0;
        if (amplifies || authorized.get() != null) {
            requireAuthorized("dividend", state.revision(), before.signum() == 0
                    ? "G4_FIRST_CONFIG_REQUIRES_APPROVAL" : "G4_DIVIDEND_APPROVAL_REQUIRED");
        }
        if (amplifies) {
            requireCoverage(mapper.genesisLiabilityAtRate(state.series().seriesCode(), value)
                    .subtract(mapper.genesisLiabilityAtRate(state.series().seriesCode(), before)));
        }
        if (mapper.updateDividend(state.series().id(), before, value) != 1)
            throw new BizException(409, "G4_DIVIDEND_STALE");
        return new Change(number(before), number(value));
    }

    public State requireEmission(String batchNo) {
        State state = state(batchNo);
        if (state.batch() != null && "COMPLETED".equals(state.batch().status())) return state;
        boolean hasPayout = state.amount().signum() > 0;
        if (hasPayout || authorized.get() != null) {
            requireAuthorized("emission:"+batchNo, state.revision(), "G4_EMISSION_APPROVAL_REQUIRED");
        }
        if (hasPayout) requireCoverage(state.amount());
        return state;
    }

    public void previewDividend(State state, BigDecimal value) {
        BigDecimal before = state.series().dailyEmissionRatePct();
        if (value.compareTo(before) <= 0) throw new BizException(422, "G4_DIVIDEND_AMPLIFICATION_REQUIRED");
        requireCoverage(mapper.genesisLiabilityAtRate(state.series().seriesCode(), value)
                .subtract(mapper.genesisLiabilityAtRate(state.series().seriesCode(), before)));
    }

    public void requireCoverage(BigDecimal additionalLiability) {
        var snapshot = coverage.snapshot();
        if (snapshot == null || !snapshot.reliable() || snapshot.redlinePct() == null
                || snapshot.redlinePct().signum() <= 0 || snapshot.ratioReserveUsd() == null
                || snapshot.ratioLiabilitiesUsd() == null)
            throw new BizException(422, "B1_COVERAGE_UNRELIABLE");
        BigDecimal liabilities = snapshot.ratioLiabilitiesUsd().add(additionalLiability);
        boolean below = liabilities.signum() <= 0 ? snapshot.redlineBreached()
                : snapshot.ratioReserveUsd().multiply(BigDecimal.valueOf(100))
                    .compareTo(liabilities.multiply(snapshot.redlinePct())) < 0;
        if (below) throw new BizException(422, "COVERAGE_BELOW_REDLINE");
    }

    <T> T approved(String action, String revision, Supplier<T> execute) {
        authorized.set(new Authorization(action, revision));
        try { return execute.get(); } finally { authorized.remove(); }
    }

    private void requireAuthorized(String action, String revision, String code) {
        Authorization value = authorized.get();
        if (value == null || !value.action().equals(action)) throw new BizException(code.contains("FIRST") ? 422 : 409, code);
        if (!value.revision().equals(revision)) throw new BizException(409, "G4_DIVIDEND_STALE");
    }

    static String number(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
    static void requireDecision(String value) {
        if (value == null || value.trim().length() < 3 || value.trim().length() > 128)
            throw new BizException(422,"G4_DECISION_REF_REQUIRED");
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
