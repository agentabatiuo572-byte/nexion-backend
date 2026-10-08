package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.domain.SupportGroupFacts.*;
import ffdd.opsconsole.content.domain.SupportRandom.*;
import ffdd.opsconsole.content.dto.SupportRandomRequest;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.content.mapper.SupportRandomMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class SupportRandomScopeTest {
    private final SupportBindingMapper bindings=mock(SupportBindingMapper.class);
    private final SupportRandomMapper mapper=mock(SupportRandomMapper.class);
    private final SupportOwnershipService ownership=mock(SupportOwnershipService.class);
    private final SupportBindingService bindingService=mock(SupportBindingService.class);
    private final AdminIdempotencyService idempotency=mock(AdminIdempotencyService.class);
    private final PlatformTransactionManager transactions=mock(PlatformTransactionManager.class);
    private final SupportBindingRandomService service=new SupportBindingRandomService(bindings,mapper,ownership,bindingService,
            idempotency,mock(AuditLogService.class),new ObjectMapper().findAndRegisterModules(),transactions,mock(ProductionSupportPathGuard.class));
    private final SupportRandomRequest.Confirm request=new SupportRandomRequest.Confirm(UUID.randomUUID().toString(),1L,"Owned group random assignment proof");
    private void actor() {when(ownership.actorId()).thenReturn(11L);when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());}

    @SuppressWarnings({"rawtypes","unchecked"})
    @Test void legacyPreviewCannotStartOperationOrAllocateWithoutScopeFacts() {
        actor();when(bindingService.managementScope(null,null)).thenReturn(new ReadScope(11L,ReadMode.MANAGED,null,null));
        when(idempotency.recoveryResult(anyString(),anyString(),anyString(),eq(ApiResult.class)))
                .thenReturn(new AdminIdempotencyService.RecoveryResult(AdminIdempotencyService.RecoveryStatus.NOT_FOUND,null));
        when(idempotency.executeRetained(anyString(),anyString(),anyString(),eq(ApiResult.class),any()))
                .thenAnswer(call->((java.util.function.Supplier<?>)call.getArgument(4)).get());
        when(mapper.loadPreview(request.previewId(),11L)).thenReturn(Map.of("rulesVersion",1L,"valid",true,
                "customers","[{\"id\":20,\"poolVersion\":1}]"));
        assertThatThrownBy(()->service.confirm("legacy-preview-key",request)).hasMessage("SUPPORT_RANDOM_PREVIEW_SCOPE_REQUIRED");
        verify(mapper,never()).insertOperation(any(),any(),any(),any());
        verify(bindings,never()).insertAssignment(any(),any(),any(),any(),any(),any(),anyInt(),any(),any(),any());
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    @Test void retainedSuccessReadsCurrentCustomerWithoutDrawingOrRewriting() {
        actor();ApiResult<Result> receipt=ApiResult.ok(new Result("retained-key",request.previewId(),List.of(new Recipient(20L,"ASSIGNED",3L,12L,"ASSIGNED"))));
        when(idempotency.recoveryResult(anyString(),anyString(),anyString(),eq(ApiResult.class)))
                .thenReturn(new AdminIdempotencyService.RecoveryResult(AdminIdempotencyService.RecoveryStatus.SUCCEEDED,receipt));
        when(ownership.customerQueryScope(20L)).thenReturn(new ReadScope(11L,ReadMode.PERSONAL,null,null));
        assertThat(service.confirm("retained-key",request)).isSameAs(receipt);
        verify(ownership).customerQueryScope(20L);verifyNoInteractions(bindings,mapper,bindingService);
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    @Test void retainedSuccessAfterTransferCannotReturnRevokedCustomerOrRepeatAllocation() {
        actor();ApiResult<Result> receipt=ApiResult.ok(new Result("retained-key",request.previewId(),List.of(new Recipient(20L,"ASSIGNED",3L,12L,"ASSIGNED"))));
        when(idempotency.recoveryResult(anyString(),anyString(),anyString(),eq(ApiResult.class)))
                .thenReturn(new AdminIdempotencyService.RecoveryResult(AdminIdempotencyService.RecoveryStatus.SUCCEEDED,receipt));
        when(ownership.customerQueryScope(20L)).thenThrow(new BizException(404,"SUPPORT_CUSTOMER_NOT_FOUND"));
        assertThatThrownBy(()->service.confirm("retained-key",request)).hasMessage("SUPPORT_CUSTOMER_NOT_FOUND");
        verifyNoInteractions(bindings,mapper,bindingService);
    }
}
