package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.content.mapper.SupportTicketCreationMapper;
import ffdd.opsconsole.content.mapper.SupportTicketCreationMapper.CreationTicket;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SupportTicketCreationPolicyServiceTest {
    private final SupportOwnershipService ownership = mock(SupportOwnershipService.class);
    private final SupportTicketCreationMapper mapper = mock(SupportTicketCreationMapper.class);
    private final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 3, 12, 0);
    private final Clock clock = Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    private final SupportTicketCreationPolicyService service = new SupportTicketCreationPolicyService(ownership, mapper, config, clock);

    @BeforeEach void defaults() {
        when(config.activeValue(anyString())).thenReturn(Optional.empty());
        when(mapper.currentRecent(anyLong(), any())).thenReturn(List.of());
        when(mapper.currentActive(anyLong())).thenReturn(List.of());
    }

    @Test void defaultsRemainFiniteWhenConfigurationIsMissingInvalidZeroOrExcessive() {
        for (String value : List.of("", "invalid", "0", "-1", "999999999999999999")) {
            when(config.activeValue(anyString())).thenReturn(Optional.of(value));
            var policy = service.policy(42L);
            assertThat(policy.allowed()).isTrue();
            assertThat(policy.reasonCode()).isNull();
            assertThat(policy.cooldownSeconds()).isEqualTo(60);
            assertThat(policy.maxCreatedInWindow()).isEqualTo(10);
            assertThat(policy.maxActiveTickets()).isEqualTo(3);
            assertThat(policy.windowHours()).isEqualTo(24);
        }
    }

    @Test void locksCustomerBeforeReadingCurrentRowsAndReadsTimeAfterWaiting() {
        var order = inOrder(ownership, mapper);
        service.policy(42L);
        order.verify(ownership).lockCustomer(42L);
        order.verify(mapper).currentRecent(42L, now.minusHours(24));
        order.verify(mapper).currentActive(42L);
    }

    @Test void cooldownHasAUsefulRetryAndExpiresAtTheExactBoundary() {
        when(mapper.currentRecent(42L, now.minusHours(24))).thenReturn(List.of(ticket(1, now.minusSeconds(59))));
        var policy = service.policy(42L);
        assertThat(policy.reasonCode()).isEqualTo("SUPPORT_TICKET_CREATE_COOLDOWN");
        assertThat(policy.retryAfterSeconds()).isEqualTo(1);
        assertThat(policy.retryAt()).isEqualTo(now.plusSeconds(1));
        when(mapper.currentRecent(42L, now.minusHours(24))).thenReturn(List.of(ticket(1, now.minusSeconds(60))));
        assertThat(service.policy(42L).allowed()).isTrue();
    }

    @Test void rollingLimitIncludesAllRecentRecordsAndReturnsTimeEnoughRowsExpire() {
        when(config.activeValue(SupportTicketCreationPolicyService.PREFIX + "max_per_24h")).thenReturn(Optional.of("2"));
        when(mapper.currentRecent(42L, now.minusHours(24))).thenReturn(List.of(
                ticket(3, now.minusHours(1)), ticket(2, now.minusHours(2)), ticket(1, now.minusHours(3))));
        var policy = service.policy(42L);
        assertThat(policy.reasonCode()).isEqualTo("SUPPORT_TICKET_CREATE_DAILY_LIMIT");
        assertThat(policy.createdInWindow()).isEqualTo(3);
        assertThat(policy.retryAt()).isEqualTo(now.plusHours(22));
    }

    @Test void activeLimitPointsAtNewestExistingTicketAndDoesNotInventAnExpiry() {
        when(mapper.currentActive(42L)).thenReturn(List.of("TK-3", "TK-2", "TK-1"));
        var policy = service.policy(42L);
        assertThat(policy.reasonCode()).isEqualTo("SUPPORT_TICKET_CREATE_ACTIVE_LIMIT");
        assertThat(policy.existingTicketNo()).isEqualTo("TK-3");
        assertThat(policy.retryAt()).isNull();
        assertThat(policy.retryAfterSeconds()).isZero();
    }

    @Test void sameCategoryAndNormalizedOriginalContentAcrossKeysIsAnExactDuplicate() {
        when(mapper.currentRecent(42L, now.minusHours(24))).thenReturn(List.of(
                new CreationTicket(1L, "TK-ORIGINAL", "TECHNICAL", " Cafe\u0301\u00a0question ", now.minusMinutes(3))));
        when(mapper.initialBody(1L)).thenReturn("first\u2003line\nsecond  line");
        assertThatThrownBy(() -> service.requireAllowed(42L, "technical", "Café question", "first line second line"))
                .isInstanceOfSatisfying(SupportTicketCreationRejectedException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(409);
                    assertThat(exception.policy().existingTicketNo()).isEqualTo("TK-ORIGINAL");
                    assertThat(exception.policy().reasonCode()).isEqualTo("SUPPORT_TICKET_CREATE_DUPLICATE");
                });
        assertThat(service.requireAllowed(42L, "technical", "Café question", "First line second line")).isEqualTo(now);
        assertThat(service.requireAllowed(42L, "other", "Café question", "first line second line")).isEqualTo(now);
        assertThat(service.requireAllowed(42L, "technical", "Café other question", "first line second line")).isEqualTo(now);
    }

    @Test void policyLookupDoesNotExposeOrCompareMessageBodies() {
        when(mapper.currentRecent(42L, now.minusHours(24))).thenReturn(List.of(ticket(1, now.minusMinutes(2))));
        service.policy(42L);
        verify(mapper, never()).initialBody(anyLong());
    }

    private CreationTicket ticket(long id, LocalDateTime created) {
        return new CreationTicket(id, "TK-" + id, "technical", "A question", created);
    }
}
