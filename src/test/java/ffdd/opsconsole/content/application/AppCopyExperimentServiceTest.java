package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.domain.AppCopyDeliveryView;
import ffdd.opsconsole.content.domain.CopyAudiencePhaseProvider;
import ffdd.opsconsole.content.domain.ContentExperimentRuntimeRepository;
import ffdd.opsconsole.content.domain.ContentExperimentRuntimeRepository.Assignment;
import ffdd.opsconsole.content.domain.ContentExperimentRuntimeRepository.CopyBody;
import ffdd.opsconsole.content.domain.ContentExperimentRuntimeRepository.RunningExperiment;
import ffdd.opsconsole.content.domain.ContentExperimentRuntimeRepository.UserAudienceProfile;
import ffdd.opsconsole.content.domain.ContentExperimentRuntimeRepository.Variant;
import ffdd.opsconsole.shared.api.ApiResult;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AppCopyExperimentServiceTest {
    private static final long USER_ID = 42L;
    private static final String COPY_KEY = "home.hero";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 12, 3, 0);

    @Mock
    private ContentExperimentRuntimeRepository repository;
    @Mock
    private CopyAudiencePhaseProvider phaseProvider;

    private AppCopyExperimentService service;

    @BeforeEach
    void setUp() {
        service = new AppCopyExperimentService(
                repository,
                phaseProvider,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(Instant.parse("2026-07-12T03:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void returnsPublishedCopyWhenNoExperimentIsRunning() {
        stubPublishedCopy();
        when(repository.findRunningExperimentForUpdate(COPY_KEY)).thenReturn(Optional.empty());

        ApiResult<AppCopyDeliveryView> result = service.deliver(USER_ID, COPY_KEY);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().version()).isEqualTo("v7");
        assertThat(result.getData().experimentId()).isNull();
        verify(repository, never()).insertAssignmentIfAbsent(any());
    }

    @Test
    void ineligibleUserFallsBackWithoutCreatingAssignment() {
        stubPublishedCopy();
        when(repository.findRunningExperimentForUpdate(COPY_KEY)).thenReturn(Optional.of(experiment()));
        when(repository.findUserAudienceProfile(USER_ID)).thenReturn(Optional.of(
                new UserAudienceProfile(USER_ID, "ACTIVE", "en-US", NOW.minusDays(60))));
        when(phaseProvider.currentPhase()).thenReturn("P2");

        ApiResult<AppCopyDeliveryView> result = service.deliver(USER_ID, COPY_KEY);

        assertThat(result.getData().version()).isEqualTo("v7");
        assertThat(result.getData().experimentId()).isNull();
        verify(repository, never()).insertAssignmentIfAbsent(any());
    }

    @Test
    void eligibleUserGetsStablePersistedVariantAndExposureIsCountedOnce() {
        stubPublishedCopy();
        RunningExperiment experiment = experiment();
        List<Variant> variants = List.of(
                new Variant("A", "v6", 50, 0),
                new Variant("B", "v7", 50, 1));
        when(repository.findRunningExperimentForUpdate(COPY_KEY)).thenReturn(Optional.of(experiment));
        when(repository.findUserAudienceProfile(USER_ID)).thenReturn(Optional.of(
                new UserAudienceProfile(USER_ID, "ACTIVE", "vi-VN", NOW.minusDays(60))));
        when(phaseProvider.currentPhase()).thenReturn("P2");
        when(repository.listVariants("EXP-1")).thenReturn(variants);
        when(repository.findAssignment("EXP-1", USER_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new Assignment("EXP-1", USER_ID, "B", "v7", 7500, null)));
        when(repository.findCopyVersion(COPY_KEY, "v7")).thenReturn(Optional.of(copy("v7", "experiment zh")));
        when(repository.markExposedIfFirst("EXP-1", USER_ID, NOW)).thenReturn(true);

        ApiResult<AppCopyDeliveryView> result = service.deliver(USER_ID, COPY_KEY);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().experimentId()).isEqualTo("EXP-1");
        assertThat(result.getData().variant()).isEqualTo("B");
        assertThat(result.getData().zh()).isEqualTo("experiment zh");
        verify(repository).insertAssignmentIfAbsent(any(Assignment.class));
        verify(repository).markExposedIfFirst("EXP-1", USER_ID, NOW);
    }

    @Test
    void existingAssignmentStaysStickyEvenWhenCurrentSplitWouldChooseAnotherVariant() {
        stubPublishedCopy();
        Assignment sticky = new Assignment("EXP-1", USER_ID, "A", "v6", 9900, NOW.minusMinutes(5));
        when(repository.findRunningExperimentForUpdate(COPY_KEY)).thenReturn(Optional.of(experiment()));
        when(repository.findAssignment("EXP-1", USER_ID)).thenReturn(Optional.of(sticky));
        when(repository.findCopyVersion(COPY_KEY, "v6")).thenReturn(Optional.of(copy("v6", "sticky zh")));

        ApiResult<AppCopyDeliveryView> result = service.deliver(USER_ID, COPY_KEY);

        assertThat(result.getData().variant()).isEqualTo("A");
        assertThat(result.getData().version()).isEqualTo("v6");
        verify(repository, never()).insertAssignmentIfAbsent(any());
        verify(repository, never()).markExposedIfFirst(anyString(), anyLong(), any());
    }

    @Test
    void conversionRequiresRunningExperimentAndCountsUserOnlyOnceAcrossDifferentKeys() {
        Assignment assignment = new Assignment("EXP-1", USER_ID, "B", "v7", 7500, NOW);
        when(repository.isRunningExperiment("EXP-1")).thenReturn(true);
        when(repository.isEligibleConversionOrder(USER_ID, "ORD-1")).thenReturn(true);
        when(repository.isEligibleConversionOrder(USER_ID, "ORD-2")).thenReturn(true);
        when(repository.findAssignment("EXP-1", USER_ID)).thenReturn(Optional.of(assignment));
        when(repository.insertConversionIfAbsent("EXP-1", USER_ID, "ORD-1", "B", NOW))
                .thenReturn(true);
        when(repository.insertConversionIfAbsent("EXP-1", USER_ID, "ORD-2", "B", NOW))
                .thenReturn(false);

        var first = service.convert(USER_ID, "EXP-1", "ORD-1");
        var replay = service.convert(USER_ID, "EXP-1", "ORD-2");

        assertThat(first.getCode()).isZero();
        assertThat(first.getData().counted()).isTrue();
        assertThat(replay.getData().counted()).isFalse();
    }

    @Test
    void conversionIsRejectedAfterExperimentStops() {
        when(repository.isEligibleConversionOrder(USER_ID, "ORD-1")).thenReturn(true);
        when(repository.isRunningExperiment("EXP-1")).thenReturn(false);

        var result = service.convert(USER_ID, "EXP-1", "ORD-1");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("CONTENT_EXPERIMENT_NOT_RUNNING");
        verify(repository, never()).findAssignment(anyString(), anyLong());
        verify(repository, never()).insertConversionIfAbsent(anyString(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    void conversionRejectsClientSuppliedKeyThatIsNotUsersPaidOrCompletedOrder() {
        when(repository.isEligibleConversionOrder(USER_ID, "ORD-OTHER-USER")).thenReturn(false);

        var result = service.convert(USER_ID, "EXP-1", "ORD-OTHER-USER");

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("CONTENT_EXPERIMENT_CONVERSION_ORDER_INVALID");
        verify(repository, never()).isRunningExperiment(anyString());
        verify(repository, never()).insertConversionIfAbsent(anyString(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    void conversionRejectsOrderThatStopsBeingEligibleBeforeAtomicInsert() {
        Assignment assignment = new Assignment("EXP-1", USER_ID, "B", "v7", 7500, NOW);
        when(repository.isEligibleConversionOrder(USER_ID, "ORD-REFUNDED")).thenReturn(true, false);
        when(repository.isRunningExperiment("EXP-1")).thenReturn(true);
        when(repository.findAssignment("EXP-1", USER_ID)).thenReturn(Optional.of(assignment));
        when(repository.insertConversionIfAbsent("EXP-1", USER_ID, "ORD-REFUNDED", "B", NOW))
                .thenReturn(false);

        var result = service.convert(USER_ID, "EXP-1", "ORD-REFUNDED");

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("CONTENT_EXPERIMENT_CONVERSION_ORDER_INVALID");
    }

    @Test
    void emptyTierListMeansAllPhasesInsteadOfNoAudience() {
        stubPublishedCopy();
        RunningExperiment allPhases = new RunningExperiment(
                "EXP-1", COPY_KEY,
                "{\"mode\":\"structured\",\"locales\":[\"vi\"],\"tiers\":[],\"registrationDaysMin\":30}");
        when(repository.findRunningExperimentForUpdate(COPY_KEY)).thenReturn(Optional.of(allPhases));
        when(repository.findUserAudienceProfile(USER_ID)).thenReturn(Optional.of(
                new UserAudienceProfile(USER_ID, "ACTIVE", "vi-VN", NOW.minusDays(60))));
        when(repository.listVariants("EXP-1")).thenReturn(List.of(
                new Variant("A", "v6", 50, 0),
                new Variant("B", "v7", 50, 1)));
        when(repository.findAssignment("EXP-1", USER_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new Assignment("EXP-1", USER_ID, "A", "v6", 100, null)));
        when(repository.findCopyVersion(COPY_KEY, "v6")).thenReturn(Optional.of(copy("v6", "all phase zh")));

        var result = service.deliver(USER_ID, COPY_KEY);

        assertThat(result.getData().experimentId()).isEqualTo("EXP-1");
        assertThat(result.getData().zh()).isEqualTo("all phase zh");
        verify(repository).insertAssignmentIfAbsent(any(Assignment.class));
        verify(phaseProvider, never()).currentPhase();
    }

    @Test
    void stableBucketIsDeterministicAndBounded() {
        int first = AppCopyExperimentService.stableBucket("EXP-1", USER_ID);
        int second = AppCopyExperimentService.stableBucket("EXP-1", USER_ID);

        assertThat(first).isEqualTo(second);
        assertThat(first).isBetween(0, 9_999);
    }

    private static RunningExperiment experiment() {
        return new RunningExperiment(
                "EXP-1",
                COPY_KEY,
                "{\"mode\":\"structured\",\"locales\":[\"vi\"],\"tiers\":[\"P2\",\"P3\"],\"registrationDaysMin\":30}");
    }

    private static CopyBody copy(String version, String zh) {
        return new CopyBody(COPY_KEY, version, zh, "english", "Tiếng Việt");
    }

    private void stubPublishedCopy() {
        when(repository.findPublishedCopy(COPY_KEY)).thenReturn(Optional.of(copy("v7", "published zh")));
    }
}
