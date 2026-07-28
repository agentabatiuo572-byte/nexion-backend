package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.market.domain.NexMarketRepository;
import ffdd.opsconsole.shared.audit.AuditLogService;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarketExchangeKycReviewFacadeAdapterTest {
    @Test
    void passedK5ReviewOnlyRequeuesAnOrderStillHeldByKyc() {
        NexMarketRepository repository = mock(NexMarketRepository.class);
        AuditLogService audit = mock(AuditLogService.class);
        when(repository.updateExchangeStatusIfCurrent(
                "EX-K5-1", "QUEUED", List.of("KYC_REQUIRED"))).thenReturn(true);
        MarketExchangeKycReviewFacadeAdapter facade =
                new MarketExchangeKycReviewFacadeAdapter(repository, audit);

        assertThat(facade.releaseExchangeReview(
                "EX-K5-1", "K5 review passed", "risk-admin")).isTrue();

        verify(repository).updateExchangeStatusIfCurrent(
                "EX-K5-1", "QUEUED", List.of("KYC_REQUIRED"));
        verify(repository, never()).updateExchangeStatus("EX-K5-1", "QUEUED");
    }

    @Test
    void rejectedK5ReviewCannotOverwriteAConcurrentlyCompletedOrder() {
        NexMarketRepository repository = mock(NexMarketRepository.class);
        AuditLogService audit = mock(AuditLogService.class);
        when(repository.updateExchangeStatusIfCurrent(
                "EX-K5-DONE", "CANCELLED", List.of("KYC_REQUIRED"))).thenReturn(false);
        MarketExchangeKycReviewFacadeAdapter facade =
                new MarketExchangeKycReviewFacadeAdapter(repository, audit);

        assertThat(facade.rejectExchangeReview(
                "EX-K5-DONE", "K5 review rejected after concurrent completion", "risk-admin")).isFalse();

        verify(repository).updateExchangeStatusIfCurrent(
                "EX-K5-DONE", "CANCELLED", List.of("KYC_REQUIRED"));
        verify(repository, never()).updateExchangeStatus("EX-K5-DONE", "CANCELLED");
    }

    @Test
    void mapperCompareAndSetIncludesTheCurrentStatusGuard() throws Exception {
        var update = ffdd.opsconsole.market.mapper.ExchangeOrderMapper.class.getMethod(
                        "updateStatusIfCurrent", String.class, String.class, List.class)
                .getAnnotation(org.apache.ibatis.annotations.Update.class);

        assertThat(String.join(" ", update.value()))
                .contains("UPPER(status) IN", "currentStatuses", "is_deleted = 0");
    }
}
