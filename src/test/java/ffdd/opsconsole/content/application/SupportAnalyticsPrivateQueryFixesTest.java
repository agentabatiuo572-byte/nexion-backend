package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.application.SupportAnalyticsPrivateQueryServiceTest.Fixture;
import static ffdd.opsconsole.content.application.SupportAnalyticsPrivateQueryServiceTest.*;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode;
import ffdd.opsconsole.content.mapper.SupportAnalyticsMapper.*;
import ffdd.opsconsole.device.facade.SupportDeviceReadFacade;
import ffdd.opsconsole.device.facade.SupportDeviceReadFacade.DeviceEvidence;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.*;
import ffdd.opsconsole.team.facade.SupportInvitationReadFacade;
import ffdd.opsconsole.team.facade.SupportInvitationReadFacade.*;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Source regressions: real Private→Stats composition with mocked readers, never a real filter chain or DB. */
class SupportAnalyticsPrivateQueryFixesTest {
    @BeforeEach void authentication(){authenticate("service_m1_read");}
    @AfterEach void clearAuthentication(){SecurityContextHolder.clearContext();}
    private static PermissionStamp grant(String code,long id){return new PermissionStamp(1L,2L,id-1,id,code,1,1);}
    private static Map<String,List<String>> stale(){return Map.of("expectedVersion",List.of("saq-v1:"+"0".repeat(64)));}
    private static void denied(Runnable call,int code){assertThatThrownBy(call::run).isInstanceOf(BizException.class).satisfies(ex->assertThat(((BizException)ex).getCode()).isEqualTo(code));}

    @Test void differentAuthenticationAndDbCapabilitiesNeverCombineIntoAReadGrant() {
        for(String auth:List.of("service_m1_read","service_m3_read")) {
            authenticate(auth);var f=new Fixture();String db=auth.equals("service_m1_read")?"service_m3_read":"service_m1_read";
            when(f.mapper.currentReadGrants(1L)).thenReturn(List.of(grant(db,4)));
            denied(()->f.service.query(stale()),403);verifyNoInteractions(f.stats);
        }
    }
    @Test void matchingM1AndM3CapabilitiesRemainUsableAndAbsentAuthenticationCannotRead() {
        for(String code:List.of("service_m1_read","service_m3_read")) {
            authenticate(code);var f=new Fixture();when(f.mapper.currentReadGrants(1L)).thenReturn(List.of(grant(code,4)));
            assertThat(f.service.query(Map.of()).get("versionState")).isEqualTo("READY");
        }
        SecurityContextHolder.clearContext();var absent=new Fixture();denied(()->absent.service.query(Map.of()),403);verifyNoInteractions(absent.stats);
    }
    @Test void replacingTheLiveM1GrantWithM3CannotBorrowAnOldM1AuthenticationAtFinalCheck() {
        var f=new Fixture();when(f.mapper.currentReadGrants(1L)).thenReturn(List.of(grant("service_m1_read",4)),List.of(grant("service_m3_read",5)));
        denied(()->f.service.query(stale()),403);verify(f.ownership,times(2)).defaultQueryScope(null,null);
    }
    @Test void allDirectoryRequiresA1InBothAuthenticationAndTheLiveDb() {
        for(boolean authA1:List.of(false,true))for(boolean dbA1:List.of(false,true)) {
            authenticate(authA1?new String[]{"service_m1_read","platform_a1_read"}:new String[]{"service_m1_read"});
            var chain=new RealStats(ReadMode.ALL);var rows=new ArrayList<>(List.of(grant("service_m1_read",4)));
            if(dbA1)rows.add(grant("platform_a1_read",5));when(chain.f.mapper.currentReadGrants(1L)).thenReturn(rows);
            assertThat(chain.api.query(Map.of()).get("versionState")).isEqualTo("READY");
            verify(chain.f.mapper,times(authA1 && dbA1?1:0)).supervisorAccountRows(chain.f.scope);
        }
    }
    @Test void ineffectiveDbA1DoesNotAlterVersionButEffectiveA1DoesAndItsLossIs409() {
        var chain=new RealStats(ReadMode.ALL);String before=(String)chain.api.query(Map.of()).get("queryVersion");
        when(chain.f.mapper.currentReadGrants(1L)).thenReturn(List.of(grant("service_m1_read",4),grant("platform_a1_read",5)));
        assertThat(chain.api.query(Map.of()).get("queryVersion")).isEqualTo(before);
        authenticate("service_m1_read","platform_a1_read");String enabled=(String)chain.api.query(Map.of()).get("queryVersion");assertThat(enabled).isNotEqualTo(before);
        when(chain.f.mapper.currentReadGrants(1L)).thenReturn(List.of(grant("service_m1_read",4),grant("platform_a1_read",5)),List.of(grant("service_m1_read",4)));
        denied(()->chain.api.query(stale()),409);
    }

    static final class RealStats {
        final Fixture f;
        final FinanceSupportPaymentFactsFacade finance=mock(FinanceSupportPaymentFactsFacade.class);
        final SupportInvitationReadFacade invitations=mock(SupportInvitationReadFacade.class);
        final SupportDeviceReadFacade devices=mock(SupportDeviceReadFacade.class);
        final SupportAnalyticsService stats;
        final SupportAnalyticsPrivateQueryService api;
        RealStats(){this(ReadMode.PERSONAL);}
        RealStats(ReadMode mode) {
            f=new Fixture(mode);stats=new SupportAnalyticsService(f.ownership,finance,f.mapper,invitations,devices);
            api=new SupportAnalyticsPrivateQueryService(f.ownership,stats,f.mapper);
            when(f.mapper.currentCustomers(f.scope)).thenReturn(List.copyOf(f.capture.current.values()));
            when(finance.readHistory(List.of(100L))).thenReturn(f.capture.snapshot);
            when(f.mapper.attributions(eq(f.scope),anyList())).thenReturn(List.of());
            when(invitations.readInvitations(List.of(100L))).thenReturn(List.copyOf(f.capture.trees.values()));
            when(devices.readCurrent(List.of(100L))).thenReturn(f.capture.stock);
            when(f.mapper.activityCoverage(f.scope)).thenReturn(f.capture.activityCoverage);
            when(f.mapper.activityRules(f.scope)).thenReturn(new ActivityRules(1L,7));
            when(f.mapper.activityEvents(f.scope,List.of(100L),T)).thenReturn(List.of(new ActivityRow(100L,T)));
            when(f.mapper.scopedGroupRows(f.scope)).thenReturn(List.of());
            when(f.mapper.serviceAccountRows(f.scope)).thenReturn(List.of());
            when(f.mapper.supervisorAccountRows(f.scope)).thenReturn(List.of());
        }
        void descendant() {
            when(invitations.readInvitations(List.of(100L))).thenReturn(List.of(new Invitation(100,0,List.of(200L),List.of(200L),Completeness.COMPLETE,Set.of())));
        }
    }
    @Test void actualStatsDescendantReadWrapperRechecksScopeAndMapsToFixed503() {
        var c=new RealStats();c.descendant();when(c.finance.readHistory(List.of(200L))).thenThrow(new DataAccessResourceFailureException("private db detail"));
        assertThatThrownBy(()->c.api.query(stale())).isInstanceOf(BizException.class).hasMessage("SUPPORT_ANALYTICS_SOURCE_UNAVAILABLE")
            .satisfies(ex->assertThat(((BizException)ex).getCode()).isEqualTo(503));
        verify(c.finance).readHistory(List.of(200L));verify(c.f.ownership,times(2)).defaultQueryScope(null,null);verify(c.f.mapper,times(2)).currentReadGrants(1L);
    }
    @Test void finalRevocationWinsOverTheActualStatsDescendantReadWrapper() {
        var c=new RealStats();c.descendant();when(c.finance.readHistory(List.of(200L))).thenThrow(new DataAccessResourceFailureException("private db detail"));
        when(c.f.ownership.defaultQueryScope(null,null)).thenReturn(c.f.scope).thenThrow(new BizException(403,"SUPPORT_SCOPE_FORBIDDEN"));
        denied(()->c.api.query(stale()),403);verify(c.finance).readHistory(List.of(200L));verify(c.f.ownership,times(2)).defaultQueryScope(null,null);
    }
    @Test void actualStatsInvalidCurrentContractGetsFixed503OrFinal403() {
        for(boolean revoked:List.of(false,true)) {
            var c=new RealStats();when(c.f.mapper.currentCustomers(c.f.scope)).thenReturn(List.of(new CurrentCustomer(100L,"BOUND","UNGROUPED",2,1L,null)));
            if(revoked)when(c.f.ownership.defaultQueryScope(null,null)).thenReturn(c.f.scope).thenThrow(new BizException(403,"SUPPORT_SCOPE_FORBIDDEN"));
            denied(()->c.api.query(stale()),revoked?403:503);verify(c.f.ownership,times(2)).defaultQueryScope(null,null);verifyNoInteractions(c.finance);
        }
    }
    @Test void successfulActualStatsDescendantReadRemainsReadyAndPrivate() {
        var c=new RealStats();c.descendant();var old=c.f.capture.snapshot;
        when(c.finance.readHistory(List.of(200L))).thenReturn(new Snapshot(List.of(),List.of(),old.coverage(),old.businessZone(),old.evaluatedAt(),List.of(new FirstHistory(200,SupportPaymentFacts.Status.READY,List.of()))));
        var response=c.api.query(Map.of());assertThat(response).containsEntry("versionState","READY");
        @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)response.get("records");
        assertThat(rows).hasSize(1);assertThat(rows.get(0)).containsEntry("customerId","100").doesNotContainKeys("descendantCustomerIds","evidence");
        verify(c.f.ownership,times(2)).defaultQueryScope(null,null);
    }
    @Test void knownBizAndSecurityExceptionsKeepTheirMeaningAndUnexpectedRuntimeIsNotSwallowed() {
        for(int code:List.of(401,403,404,409,422)) {
            var f=new Fixture();var original=new BizException(code,"KNOWN_"+code);when(f.stats.evaluateForQuery(any(),eq(f.scope),eq(false))).thenThrow(original);
            assertThatThrownBy(()->f.service.query(Map.of())).isSameAs(original);verify(f.ownership,times(2)).defaultQueryScope(null,null);
        }
        var denied=new Fixture();var permission=new AccessDeniedException("permission");when(denied.stats.evaluateForQuery(any(),eq(denied.scope),eq(false))).thenThrow(permission);
        assertThatThrownBy(()->denied.service.query(Map.of())).isSameAs(permission);
        var unexpected=new Fixture();var programming=new UnsupportedOperationException("unexpected");when(unexpected.stats.evaluateForQuery(any(),eq(unexpected.scope),eq(false))).thenThrow(programming);
        assertThatThrownBy(()->unexpected.service.query(Map.of())).isSameAs(programming);
        var unavailable=new Fixture();when(unavailable.stats.evaluateForQuery(any(),eq(unavailable.scope),eq(false))).thenThrow(new BizException(503,"SUPPORT_ANALYTICS_READER_UNAVAILABLE"));
        assertThatThrownBy(()->unavailable.service.query(Map.of())).hasMessage("SUPPORT_ANALYTICS_SOURCE_UNAVAILABLE");
    }
    @Test void unknownActivityCannotHideBirthReadFailedAndFinalRevocationStillWins() {
        for(boolean revoked:List.of(false,true)) {
            var f=new Fixture();f.capture.activityCoverage=null;f.capture.activityWindow=null;f.capture.activities=Map.of();var old=f.capture.snapshot;
            f.capture.snapshot=new Snapshot(old.facts(),old.issues(),old.coverage(),old.businessZone(),old.evaluatedAt(),List.of(new FirstHistory(100,SupportPaymentFacts.Status.UNKNOWN,List.of("BIRTH_SOURCE_READ_FAILED"))));
            if(revoked)when(f.ownership.defaultQueryScope(null,null)).thenReturn(f.scope).thenThrow(new BizException(403,"SUPPORT_SCOPE_FORBIDDEN"));
            denied(()->f.service.query(stale()),revoked?403:503);verify(f.ownership,times(2)).defaultQueryScope(null,null);
        }
    }

    private static DeviceEvidence device(String environment,String runId,String order) {
        return new DeviceEvidence(20,100,order,"GIFT","IDC",BigDecimal.ONE,"HELD","ACTIVE",T,null,0,environment,runId,null,SupportDeviceReadFacade.ConnectionStatus.UNKNOWN);
    }
    private static Fact purchase(long customer,String order) {
        return new Fact("PURCHASE:"+order,Kind.DEVICE_PURCHASE,Source.WALLET_ORDER,List.of("source20"),customer,20,"source20",order,"DEVICE",null,"USDT",BigDecimal.TEN,T,"paid_at",6,null,T,T,"v1",SupportPaymentFacts.Status.UNKNOWN);
    }
    private static Map<String,Object> deviceResponse(Fixture f) {
        var response=f.service.query(Map.of("view",List.of("DEVICES")));
        @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)response.get("records");assertThat(rows).hasSize(1);return rows.get(0);
    }
    private static void paid(Fixture f,DeviceEvidence device) {
        var p=purchase(100,"order20");assertThat(SupportPaymentFacts.validateCanonical(p,"Asia/Shanghai")).isNull();
        var old=f.capture.snapshot;var facts=new ArrayList<>(old.facts());facts.add(p);var lookup=new HashMap<>(f.capture.facts);lookup.put(p.factId(),p);f.capture.facts=lookup;
        f.capture.snapshot=new Snapshot(facts,old.issues(),old.coverage(),old.businessZone(),old.evaluatedAt(),old.firstHistory());
        f.capture.stock=new SupportDeviceReadFacade.Snapshot(List.of(device),List.of(),T);
    }
    @Test void unknownCurrentEnvironmentCannotUpgradeAcquisitionInDetailOrFingerprint() {
        for(var d:List.of(device(null,null,"order20"),device("PRODUCTION","run20","order20"))) {
            var f=new Fixture();paid(f,d);assertThat(SupportAnalyticsService.productionDevice(d)).isZero();
            assertThat(deviceResponse(f)).containsEntry("holdingStatus","UNKNOWN").containsEntry("acquisition","UNKNOWN");
            String observed=(String)f.service.query(Map.of("view",List.of("DEVICES"))).get("queryVersion");assertThat(observed).startsWith("saq-v1:");
            // Isolate proof encoding: financial snapshot is unchanged while the mock purchase lookup is absent.
            f.capture.facts=Map.of();assertThat(f.service.query(Map.of("view",List.of("DEVICES"))).get("queryVersion")).isEqualTo(observed);
        }
    }
    @Test void trustedProductionPurchaseStillConfirmsAndChangesFingerprintWhenProofIsMissing() {
        var f=new Fixture();paid(f,device("PRODUCTION",null,"order20"));
        assertThat(deviceResponse(f)).containsEntry("holdingStatus","AVAILABLE").containsEntry("acquisition","PAID_PURCHASE");
        String observed=(String)f.service.query(Map.of("view",List.of("DEVICES"))).get("queryVersion");assertThat(observed).startsWith("saq-v1:");f.capture.facts=Map.of();
        assertThat(deviceResponse(f)).containsEntry("acquisition","UNKNOWN");assertThat(f.service.query(Map.of("view",List.of("DEVICES"))).get("queryVersion")).isNotEqualTo(observed);
    }
    @Test void channelLabelsWrongCustomerWrongOrderAndStrictIssueCannotConfirmPurchase() {
        var absent=new Fixture();absent.capture.stock=new SupportDeviceReadFacade.Snapshot(List.of(device("PRODUCTION",null,"order20")),List.of(),T);
        assertThat(deviceResponse(absent)).containsEntry("acquisition","UNKNOWN");
        for(var wrong:List.of(purchase(200,"order20"),purchase(100,"otherOrder"))) {
            var f=new Fixture();paid(f,device("PRODUCTION",null,"order20"));f.capture.facts=Map.of(wrong.factId(),wrong);
            assertThat(deviceResponse(f)).containsEntry("acquisition","UNKNOWN");
        }
        var rejected=new Fixture();paid(rejected,device("PRODUCTION",null,"order20"));var old=rejected.capture.snapshot;
        rejected.capture.snapshot=new Snapshot(old.facts(),List.of(new Issue(Source.WALLET_ORDER,"source20","SETTLEMENT_MISMATCH",100L)),old.coverage(),old.businessZone(),old.evaluatedAt(),old.firstHistory());
        assertThat(deviceResponse(rejected)).containsEntry("acquisition","UNKNOWN");
    }
    @Test void deviceProofNeverBypassesFinalRevocation() {
        var f=new Fixture();paid(f,device("PRODUCTION",null,"order20"));when(f.mapper.currentReadGrants(1L)).thenReturn(List.of(grant("service_m1_read",4)),List.of());
        denied(()->f.service.query(Map.of("view",List.of("DEVICES"))),403);
    }
}
