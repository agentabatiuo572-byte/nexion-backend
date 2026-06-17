package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.market.domain.NexMarketRepository;
import ffdd.opsconsole.market.dto.NexMarketAdvanceRequest;
import ffdd.opsconsole.market.dto.NexMarketCurveFrame;
import ffdd.opsconsole.market.dto.NexMarketCurveUpdateRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsNexMarketServiceTest {
    private final FakePlatformConfigFacade configFacade = new FakePlatformConfigFacade();
    private final FakeTreasuryCoverageFacade coverageFacade = new FakeTreasuryCoverageFacade();
    private final FakeNexMarketRepository marketRepository = new FakeNexMarketRepository();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-17T00:00:00Z"), ZoneId.of("UTC"));
    private final OpsNexMarketService service = new OpsNexMarketService(
            configFacade,
            coverageFacade,
            marketRepository,
            auditLogService,
            new ObjectMapper(),
            clock);

    @Test
    void overviewExposesSevenFrameCurveAndSunsetExclusions() {
        ApiResult<Map<String, Object>> result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat((List<?>) result.getData().get("frames")).hasSize(7);
        assertThat(result.getData().get("sunsetExclusions"))
                .asList()
                .contains("Premium", "NEX v2", "Points");
    }

    @Test
    void raisingCurveBelowB1CoverageRedlineReturns422() {
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("80.00"), new BigDecimal("85.00"));

        ApiResult<Map<String, Object>> result = service.updateWeeklyCurve(
                "idem-g3",
                new NexMarketCurveUpdateRequest(frames("0.200"), "raise price", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
        assertThat(result.getMessage()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
    }

    @Test
    void validCurveWritesConfigPublishesActiveFrameAndAudits() {
        ApiResult<Map<String, Object>> result = service.updateWeeklyCurve(
                "idem-g3",
                new NexMarketCurveUpdateRequest(frames("0.160"), "weekly schedule", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsKey("wallet.nex_market.weekly_curve");
        assertThat(configFacade.values).containsEntry("wallet.exchange.nex_usdt_price", "0.16");
        assertThat(marketRepository.lastPrice).isEqualByComparingTo("0.16000000");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("G3_WEEKLY_CURVE_CHANGED");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("idempotencyKey", "idem-g3");
    }

    @Test
    void advanceRequiresIdempotencyKey() {
        ApiResult<Map<String, Object>> result = service.advanceCurrentFrame(
                null,
                new NexMarketAdvanceRequest("daily frame", "system"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
    }

    private static List<NexMarketCurveFrame> frames(String price) {
        return java.util.stream.IntStream.range(0, 7)
                .mapToObj(index -> new NexMarketCurveFrame(
                        index,
                        new BigDecimal(price),
                        new BigDecimal("0.08"),
                        new BigDecimal("3")))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailMap(Object detail) {
        return (Map<String, Object>) detail;
    }

    private static final class FakePlatformConfigFacade implements PlatformConfigFacade {
        private final Map<String, String> values = new LinkedHashMap<>(Map.of("wallet.exchange.nex_usdt_price", "0.171"));

        @Override
        public Optional<String> activeValue(String configKey) {
            return Optional.ofNullable(values.get(configKey));
        }

        @Override
        public void upsertAdminValue(String configKey, String configValue, String valueType, String configGroup, String remark) {
            values.put(configKey, configValue);
        }
    }

    private static final class FakeTreasuryCoverageFacade implements TreasuryCoverageFacade {
        private TreasuryCoverageSnapshot snapshot = new TreasuryCoverageSnapshot(new BigDecimal("110.00"), new BigDecimal("85.00"));

        @Override
        public TreasuryCoverageSnapshot snapshot() {
            return snapshot;
        }
    }

    private static final class FakeNexMarketRepository implements NexMarketRepository {
        private BigDecimal lastPrice;

        @Override
        public Optional<BigDecimal> latestNexUsdtPrice() {
            return Optional.of(new BigDecimal("0.171"));
        }

        @Override
        public void publishNexUsdtPrice(BigDecimal priceUsdt, BigDecimal deltaPercent, String sparklineJson, LocalDateTime sampledAt) {
            lastPrice = priceUsdt;
        }
    }
}
