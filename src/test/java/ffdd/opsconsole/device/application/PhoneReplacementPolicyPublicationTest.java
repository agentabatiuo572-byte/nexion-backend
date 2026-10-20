package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.common.boundary.PhoneReplacementContract;
import ffdd.opsconsole.common.boundary.PhoneReplacementContract.*;
import ffdd.opsconsole.device.dto.*;
import ffdd.opsconsole.device.mapper.PhoneReplacementPolicyOperationMapper;
import ffdd.opsconsole.device.mapper.PhoneReplacementPolicyOperationMapper.Row;
import ffdd.opsconsole.onboarding.application.PhoneReplacementPolicy;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.PhoneReplacementException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.security.PhoneReplacementAdminAccess;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class PhoneReplacementPolicyPublicationTest {
    private final PhoneReplacementContract domain=mock(PhoneReplacementContract.class);
    private final PlatformConfigFacade config=mock(PlatformConfigFacade.class);
    private final PhoneReplacementPolicyOperationMapper commands=mock(PhoneReplacementPolicyOperationMapper.class);
    private final PhoneReplacementAdminAccess access=mock(PhoneReplacementAdminAccess.class);
    private final AuditLogService audit=mock(AuditLogService.class);
    private final EventOutboxService events=mock(EventOutboxService.class);
    private final Clock clock=Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"),ZoneOffset.UTC);
    private final Map<String,String> values=new LinkedHashMap<>();
    private final Map<String,Row> journal=new HashMap<>();
    private Policy policy=new Policy(true,false,0L,30,2,7L);
    private PhoneReplacementPolicyPublicationService service;

    @BeforeEach void setup() {
        service=new PhoneReplacementPolicyPublicationService(domain,config,commands,access,audit,events,clock);
        values.putAll(PhoneReplacementPolicyPublicationService.values(policy));
        when(access.requirePolicyApproval()).thenReturn(41L);
        when(domain.lockCurrentPolicy()).thenAnswer(call->policy);
        when(domain.currentPolicy()).thenAnswer(call->policy);
        when(domain.validatePolicy(any(),anyLong())).thenAnswer(call->{
            Policy p=call.getArgument(0);
            return new PhoneReplacementPolicy(p.allowReplacement(),p.minReplacementIntervalDays(),p.replacementWindowDays(),p.maxReplacementsInWindow(),p.policyVersion())
                    .fieldErrors(call.getArgument(1));
        });
        when(commands.lockByKey(anyLong(),anyString(),anyString())).thenAnswer(call->journal.get(call.getArgument(1)+":"+call.getArgument(2)));
        when(commands.findByKey(anyLong(),anyString(),anyString())).thenAnswer(call->journal.get(call.getArgument(1)+":"+call.getArgument(2)));
        when(commands.insert(any())).thenAnswer(call->{Row row=call.getArgument(0);journal.put(row.commandType()+":"+row.idempotencyKey(),row);return 1;});
        when(commands.complete(anyString(),anyLong(),anyString(),anyString())).thenAnswer(call->{
            Row row=journal.values().stream().filter(r->r.operationId().equals(call.getArgument(0))).findFirst().orElseThrow();
            journal.put(row.commandType()+":"+row.idempotencyKey(),new Row(row.operationId(),row.actorId(),row.commandType(),row.idempotencyKey(),row.requestHash(),row.permissionCode(),"SUCCEEDED",row.submittedAt(),call.getArgument(1),call.getArgument(2),call.getArgument(3)));return 1;
        });
        doAnswer(call->{String key=call.getArgument(0),value=call.getArgument(1);values.put(key,value);
            if(key.endsWith("policyVersion"))policy=new Policy(true,"on".equals(values.get(prefix("allowReplacement"))),Long.valueOf(values.get(prefix("minReplacementIntervalDays"))),Integer.valueOf(values.get(prefix("replacementWindowDays"))),Integer.valueOf(values.get(prefix("maxReplacementsInWindow"))),Long.valueOf(value));
            return null;
        }).when(config).upsertAdminValue(anyString(),anyString(),anyString(),anyString(),anyString());
        A2ReplayContext.enterReplay();
    }
    @AfterEach void clear(){A2ReplayContext.exitReplay();}
    private static String prefix(String name){return PhoneReplacementPolicyPublicationService.PREFIX+name;}

    @Test void singleFieldPublishesWholePolicyPreservingExplicitZero() {
        var receipt=service.publish(Map.of(prefix("allowReplacement"),"on"),7L,"confirm phone flag","original",true).getData().receipt();
        assertThat(policy).isEqualTo(new Policy(true,true,0L,30,2,8L));
        assertThat(receipt.status()).isEqualTo("SUCCEEDED");
        assertThat(receipt.currentResource()).isEqualTo(policy);
        verify(config,times(5)).upsertAdminValue(anyString(),anyString(),anyString(),anyString(),anyString());
        verify(audit).recordRequired(any());verify(events).publish(eq("PHONE_REPLACEMENT_POLICY"),eq("global"),eq("admin.phone_replacement_policy_updated"),any());
    }
    @Test void durableReplayRestoresOriginalEvenWhenPolicyWasReplacedAndClockIsBeyond24Hours() {
        var input=Map.of(prefix("allowReplacement"),"on");
        var original=service.publish(input,7L,"confirm phone flag","original",true).getData().receipt();
        policy=new Policy(true,false,20L,60,4,99L);
        var restart=new PhoneReplacementPolicyPublicationService(domain,config,commands,access,audit,events,Clock.offset(clock,Duration.ofDays(2)));
        var replay=restart.publish(input,7L,"confirm phone flag","original",true).getData().receipt();
        assertThat(replay.operationId()).isEqualTo(original.operationId());
        assertThat(replay.originalResult()).isEqualTo(original.originalResult());
        assertThat(replay.currentResource()).isEqualTo(policy);
        verify(commands,times(1)).insert(any());verify(audit,times(1)).recordRequired(any());
    }
    @Test void originalKeyWithChangedBodyHasStructuredConflict() {
        service.publish(Map.of(prefix("allowReplacement"),"on"),7L,"confirm phone flag","original",true);
        assertThatThrownBy(()->service.publish(Map.of(prefix("allowReplacement"),"off"),7L,"confirm phone flag","original",true))
                .isInstanceOfSatisfying(PhoneReplacementException.class,e->{
                    assertThat(e.details().conflictType()).isEqualTo("IDEMPOTENCY_MISMATCH");
                    assertThat(e.details().allowedActions()).containsExactly("QUERY_ORIGINAL","CANCEL");
                    assertThat(e.details().reason()).doesNotContain("IDEMPOTENCY");
                });
        verify(commands,times(1)).insert(any());
    }
    @Test void staleVersionNeverWritesAndReturnsRefreshableConflict() {
        assertThatThrownBy(()->service.publish(Map.of(prefix("allowReplacement"),"on"),6L,"confirm phone flag","stale",true))
                .isInstanceOfSatisfying(PhoneReplacementException.class,e->{assertThat(e.details().conflictType()).isEqualTo("POLICY_VERSION_CHANGED");assertThat(e.details().allowedActions()).contains("REFRESH","CANCEL");});
        verifyNoInteractions(config,audit,events);
    }
    @Test void missingOrMixedBatchAndOverflowCannotPartiallyPublish() {
        var full=PhoneReplacementPolicyPublicationService.values(policy);
        var mixed=new LinkedHashMap<>(full);mixed.put("E.compute.download.url","https://example.test");
        var overflow=new LinkedHashMap<>(full);overflow.put(prefix("minReplacementIntervalDays"),"9007199254740991");
        for(var input:List.of(Map.of(prefix("allowReplacement"),"on"),mixed,overflow)) {
            assertThatThrownBy(()->service.publish(input,7L,"confirm whole policy","invalid",false))
                    .isInstanceOfSatisfying(PhoneReplacementException.class,e->{assertThat(e.details().conflictType()).isEqualTo("INVALID_POLICY");assertThat(e.details().fieldErrors()).isNotEmpty();});
        }
        verifyNoInteractions(config,audit,events);verify(commands,never()).insert(any());
    }
    @Test void unpublishedOrInvalidStoredPolicyNeedsFullExplicitRepair() {
        policy=new Policy(false,null,null,null,null,7L);
        assertThatThrownBy(()->service.publish(Map.of(prefix("allowReplacement"),"on"),7L,"confirm phone flag","partial",true))
                .hasMessage("PHONE_REPLACEMENT_POLICY_UNAVAILABLE");
        var full=PhoneReplacementPolicyPublicationService.values(new Policy(true,true,1L,30,2,7L));
        assertThat(service.publish(full,7L,"repair complete policy","complete",false).getCode()).isZero();
        assertThat(policy).isEqualTo(new Policy(true,true,1L,30,2,8L));
    }
    @Test void directCallStillRequiresA2AndCannotWrite() {
        A2ReplayContext.exitReplay();
        assertThat(service.publish(Map.of(prefix("allowReplacement"),"on"),7L,"confirm phone flag","direct",true).getMessage()).isEqualTo("A2_CONFIRMATION_REQUIRED");
        verifyNoInteractions(domain,commands,config,audit,events);
    }
    @Test void versionDtoAndA2RejectDecimalsAndStringsInsteadOfTruncating() throws Exception {
        var json=new ObjectMapper();
        var method=OpsDeviceService.class.getDeclaredMethod("phonePolicyVersion",Map.class);method.setAccessible(true);
        for(String invalid:List.of("1.9","1.0","\"1\"","9007199254740992")){
            assertThatThrownBy(()->json.readValue("{\"expectedPolicyVersion\":"+invalid+"}",ComputeConfigParamUpdateRequest.class)).isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
            assertThatThrownBy(()->json.readValue("{\"expectedPolicyVersion\":"+invalid+"}",ComputeConfigBatchUpdateRequest.class)).isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        }
        for(Object raw:List.of(1.9,1.0,"1",9007199254740992L)){
            assertThatThrownBy(()->method.invoke(null,Map.of("expectedPolicyVersion",raw))).hasCauseInstanceOf(PhoneReplacementException.class);
        }
        assertThat(method.invoke(null,Map.of("expectedPolicyVersion",7))).isEqualTo(7L);
        assertThat(json.readValue("{\"expectedPolicyVersion\":7}",ComputeConfigBatchUpdateRequest.class).expectedPolicyVersion()).isEqualTo(7L);
    }
}
