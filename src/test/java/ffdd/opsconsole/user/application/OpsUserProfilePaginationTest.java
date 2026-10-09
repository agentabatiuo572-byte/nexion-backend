package ffdd.opsconsole.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.application.SupportOwnershipService;
import ffdd.opsconsole.content.domain.SupportGroupFacts;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import ffdd.opsconsole.user.domain.UserAccountView;
import ffdd.opsconsole.user.domain.UserOpsRepository;
import ffdd.opsconsole.user.dto.UserQueryRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class OpsUserProfilePaginationTest {
    private final UserOpsRepository repository = mock(UserOpsRepository.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final AdminOperatorRoleResolver roles = mock(AdminOperatorRoleResolver.class);
    private final SupportOwnershipService ownership = mock(SupportOwnershipService.class);
    private final OpsUserService service = new OpsUserService(repository, null, null, null, null,
            audit, null, roles, null, null, null, null, null, ownership);
    private final SupportGroupFacts.ReadScope scope = new SupportGroupFacts.ReadScope(
            7L, SupportGroupFacts.ReadMode.ALL, null, null);

    @ParameterizedTest
    @CsvSource(value = {"42949674,NULL", "107374184,1", "85899347,NULL"}, nullValues = "NULL")
    void rejectsUnscopedEffectiveOffsetBeforeReadOrAudit(int page, Integer size) {
        var result = service.profilePage(UserQueryRequest.basic(null, null, null, page, size, null));

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("C1_PAGE_NUM_INVALID");
        verifyNoInteractions(repository, audit);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "phone,42949674,NULL", "forced,42949674,NULL", "reader,42949674,NULL",
            "phone,107374184,1", "forced,107374184,1", "reader,107374184,1"
    }, nullValues = "NULL")
    void keepsOriginalLegalScopedNormalization(String route, int page, Integer size) {
        var query = UserQueryRequest.basic(null, null, null, page, size, null);
        when(ownership.currentSupportReader()).thenReturn("reader".equals(route));
        when(ownership.defaultQueryScope(null, null)).thenReturn(scope);
        when(repository.pageSupportProfiles(query, scope))
                .thenReturn(new PageResult<>(0, page, size == null ? 20 : size, List.of()));

        assertThat(read(route, query).getCode()).isZero();
        verify(repository).pageSupportProfiles(query, scope);
        verify(repository, never()).pageProfiles(any());
        verify(audit).recordRequired(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"phone", "forced", "reader"})
    void keepsOriginalExplicitSizeUpperOffsetRejection(String route) {
        when(ownership.currentSupportReader()).thenReturn("reader".equals(route));

        var result = read(route, UserQueryRequest.basic(null, null, null, 42949674, 50, null));

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("C1_PAGE_NUM_INVALID");
        verifyNoInteractions(repository, audit);
    }

    @ParameterizedTest
    @CsvSource(value = {"42949673,NULL", "107374183,1", "1,NULL"}, nullValues = "NULL")
    void acceptsRepresentableUnscopedBoundaries(int page, Integer size) {
        var query = UserQueryRequest.basic(null, null, null, page, size, null);
        when(repository.pageProfiles(query)).thenReturn(new PageResult<>(0, page, 50, List.of()));

        assertThat(service.profilePage(query).getCode()).isZero();
        verify(repository).pageProfiles(eq(query));
    }

    @Test
    void scopeQualificationFailureNeverFallsBackToUnscopedRead() {
        var failure = new BizException(403, "SUPPORT_SCOPE_UNAVAILABLE");
        when(ownership.currentSupportReader()).thenThrow(failure);

        assertThatThrownBy(() -> service.profilePage(UserQueryRequest.basic(null, null, null, 1, null, null)))
                .isSameAs(failure);
        verifyNoInteractions(repository, audit);
    }

    private ApiResult<PageResult<UserAccountView>> read(String route, UserQueryRequest query) {
        return switch (route) {
            case "phone" -> service.supportProfilePage(query);
            case "forced" -> service.supportWorkbenchProfilePage(query);
            default -> service.profilePage(query);
        };
    }
}
