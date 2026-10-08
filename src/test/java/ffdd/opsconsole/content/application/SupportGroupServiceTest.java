package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.content.domain.SupportGroupFacts.*;
import ffdd.opsconsole.content.dto.SupportGroupRequests.*;
import ffdd.opsconsole.content.mapper.SupportGroupMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class SupportGroupServiceTest {
    private final SupportGroupMapper mapper=mock(SupportGroupMapper.class);
    private final SupportOwnershipService ownership=mock(SupportOwnershipService.class);
    private final AdminIdempotencyService idempotency=mock(AdminIdempotencyService.class);
    private final AuditLogService audit=mock(AuditLogService.class);
    private final SupportGroupService service=new SupportGroupService(mapper,ownership,idempotency,audit);
    private final LocalDateTime at=LocalDateTime.of(2026,10,7,0,0);
    @BeforeEach void setup(){
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("1","",List.of(
            new SimpleGrantedAuthority("service_m1_write"),new SimpleGrantedAuthority("service_m1_read"),
            new SimpleGrantedAuthority("platform_a1_write"),new SimpleGrantedAuthority("platform_a1_read"))));
        when(ownership.actorId()).thenReturn(1L);when(mapper.now()).thenReturn(at);
        when(mapper.lockAccount(anyLong())).thenAnswer(i->new Account(i.getArgument(0),1,0L));
        when(idempotency.execute(anyString(),anyString(),anyString(),any(),any())).thenAnswer(i->((Supplier<?>)i.getArgument(4)).get());
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    private void supervisor(){doThrow(new BizException(403,"SUPER_REQUIRED")).when(ownership).requireSuperAdminSnapshot();when(mapper.qualified(1L,"SUPERVISOR")).thenReturn(1);}
    @Test void managerWithoutGroupsGetsEmptyOwnedScope(){supervisor();when(mapper.groups(1L)).thenReturn(List.of());assertThat(service.groups()).isEmpty();verify(mapper).groups(1L);verify(mapper,never()).groups(isNull());}
    @Test void cannotForgeOwner(){supervisor();assertThatThrownBy(()->service.create("key",new Create("组",2L,"合法操作原因说明"))).isInstanceOf(BizException.class);verifyNoInteractions(idempotency);}
    @Test void cannotRenameOtherOwnerGroup(){supervisor();when(mapper.lockGroup(8L)).thenReturn(new Group(8L,"Other",2L,"ENABLED",1L));assertThatThrownBy(()->service.rename(8L,"key",new Rename("new",1L,"合法操作原因说明"))).hasMessage("SUPPORT_GROUP_NOT_FOUND");verify(mapper,never()).rename(any(),any(),any(),any());}
    @Test void managerCannotTakeUngroupedMember(){supervisor();when(mapper.qualification(3L,"SERVICE")).thenReturn(new ffdd.opsconsole.content.domain.SupportGroupFacts.Qualification(1L,3L,"SERVICE","ENABLED",1L,at));when(mapper.member(3L)).thenReturn(new Member(1L,3L,null,1L,at));when(mapper.lockGroup(8L)).thenReturn(new Group(8L,"Own",1L,"ENABLED",1L));assertThatThrownBy(()->service.move(3L,"key",new Move(8L,1L,null,1L,"合法操作原因说明"))).hasMessage("SUPPORT_GROUP_FORBIDDEN");verify(mapper,never()).closeMember(any(),any(),any());}
    @Test void staleVersionCannotMutate(){Group g=new Group(8L,"Own",1L,"ENABLED",2L);when(mapper.group(8L)).thenReturn(g);when(mapper.lockGroup(8L)).thenReturn(g);assertThatThrownBy(()->service.rename(8L,"key",new Rename("new",1L,"合法操作原因说明"))).hasMessage("SUPPORT_GROUP_VERSION_CONFLICT");verifyNoInteractions(audit);}
    @Test void nonemptyGroupCannotDisable(){Group g=new Group(8L,"Own",1L,"ENABLED",1L);when(mapper.group(8L)).thenReturn(g);when(mapper.lockGroup(8L)).thenReturn(g);when(mapper.memberCount(8L)).thenReturn(1L);assertThatThrownBy(()->service.status(8L,"key",new Status("DISABLED",1L,"合法操作原因说明"))).hasMessage("SUPPORT_GROUP_HANDOVER_REQUIRED");}
    @Test void activeRouteAlsoBlocksExit(){Group g=new Group(8L,"Own",1L,"DISABLED",1L);when(mapper.group(8L)).thenReturn(g);when(mapper.lockGroup(8L)).thenReturn(g);when(mapper.routeCount(8L)).thenReturn(1L);assertThatThrownBy(()->service.status(8L,"key",new Status("ARCHIVED",1L,"合法操作原因说明"))).hasMessage("SUPPORT_GROUP_HANDOVER_REQUIRED");}
    @Test void ordinaryDisablePreservesMemberBindingAndQualification(){when(mapper.boundCount(3L)).thenReturn(10L);service.accountChanging(3L,null,true,"disable","合法操作原因说明");service.accountChanged(3L,null,true,"disable","合法操作原因说明");verify(mapper,never()).closeMember(any(),any(),any());verify(mapper,never()).closeQualification(any(),any(),any());verifyNoInteractions(audit);}
    @Test void archivedOwnedGroupBlocksOldAccountDisable(){when(mapper.qualification(3L,"SUPERVISOR")).thenReturn(new ffdd.opsconsole.content.domain.SupportGroupFacts.Qualification(1L,3L,"SUPERVISOR","ENABLED",1L,at));when(mapper.ownedGroupCount(3L)).thenReturn(1L);assertThatThrownBy(()->service.accountChanging(3L,null,true,"disable","合法操作原因说明")).hasMessage("SUPPORT_GROUP_OWNER_HANDOVER_REQUIRED");verify(mapper,never()).closeQualification(any(),any(),any());}
    @Test void roleRemovalRequiresPersonalHandover(){when(mapper.boundCount(3L)).thenReturn(2L);assertThatThrownBy(()->service.accountChanging(3L,"finance",false,"role","合法操作原因说明")).hasMessage("SUPPORT_PERSONAL_HANDOVER_REQUIRED");}
    @Test void peopleAreUnionNotRoleSum(){when(mapper.supervisors()).thenReturn(List.of(Map.of("adminId",1L),Map.of("adminId",2L)));when(mapper.memberIds()).thenReturn(List.of(2L,3L));assertThat(service.supervisors()).containsEntry("supervisorCount",2).containsEntry("memberCount",2).containsEntry("peopleCount",3);}
    @Test void legacySeatCannotGrantQualificationAfterCutover(){when(mapper.cutoverApplied()).thenReturn(1);assertThatThrownBy(()->service.validateLegacySeat(3L,"MANAGER")).hasMessage("SUPPORT_EXPLICIT_QUALIFICATION_REQUIRED");}
    @Test void changedQualificationFailureIsNotSwallowed(){var q=new ffdd.opsconsole.content.domain.SupportGroupFacts.Qualification(1L,3L,"SUPERVISOR","ENABLED",1L,at);when(mapper.qualification(3L,"SUPERVISOR")).thenReturn(q);when(mapper.qualifications(3L)).thenReturn(List.of(q));when(mapper.closeQualification(1L,1L,at)).thenReturn(0);assertThatThrownBy(()->service.accountChanged(3L,null,true,"disable","合法操作原因说明")).hasMessage("SUPPORT_GROUP_VERSION_CONFLICT");verifyNoInteractions(audit);}
}
