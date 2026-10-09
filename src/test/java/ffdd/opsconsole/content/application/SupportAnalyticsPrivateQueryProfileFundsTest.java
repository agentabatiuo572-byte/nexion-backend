package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.application.SupportAnalyticsPrivateQueryServiceTest.Fixture;
import static ffdd.opsconsole.content.application.SupportAnalyticsPrivateQueryServiceTest.*;
import ffdd.opsconsole.content.domain.SupportAnalyticsStats.*;
import ffdd.opsconsole.content.mapper.SupportAnalyticsMapper.*;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade.*;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Private candidate wiring tests with mocked facades; SQL, Spring proxies, RR and HTTP remain separate gates. */
class SupportAnalyticsPrivateQueryProfileFundsTest {
    @BeforeEach void authentication(){authenticate("service_m1_read");}
    @AfterEach void clearAuthentication(){SecurityContextHolder.clearContext();}
    static final class Funding {
        final Fixture f=new Fixture();
        final SupportFundsReadFacade facade=mock(SupportFundsReadFacade.class);
        final SupportAnalyticsPrivateQueryService api=new SupportAnalyticsPrivateQueryService(f.ownership,f.stats,f.mapper,facade);
        Snapshot snapshot=ready();
        Funding(){when(facade.readCurrent(anyCollection())).thenAnswer(i->snapshot);}
        String token(){var response=api.query(Map.of());assertThat(response.get("versionState")).isEqualTo("READY");return (String)response.get("queryVersion");}
    }
    private static WalletEvidence wallet(long customer,long version,String usdt,String nex) {
        return new WalletEvidence(customer,customer+1000,version,usdt==null?null:new BigDecimal(usdt),nex==null?null:new BigDecimal(nex),T,
            usdt==null || nex==null?EvidenceState.UNKNOWN:EvidenceState.READY,usdt==null || nex==null?List.of(Reason.USDT_BALANCE_UNVERIFIED,Reason.NEX_BALANCE_UNVERIFIED):List.of());
    }
    private static WithdrawalEvidence withdrawal(long id,long customer,String status,WithdrawalState state,String principal,String fee,String net) {
        return new WithdrawalEvidence(id,customer,"USDT",new BigDecimal(principal),fee==null?null:new BigDecimal(fee),net==null?null:new BigDecimal(net),status,status,state,
            state==WithdrawalState.SUCCESS?T:null,T,state==WithdrawalState.UNKNOWN?List.of(Reason.UNKNOWN_WITHDRAWAL_STATUS):List.of());
    }
    private static Snapshot ready(){return snapshot(List.of(100L),List.of(wallet(100,1,"10","2")),ReadState.READY,List.of(),
        List.of(withdrawal(7,100,"CONFIRMED",WithdrawalState.SUCCESS,"10","1","9")),ReadState.READY,List.of());}
    private static Snapshot snapshot(List<Long> roots,List<WalletEvidence> wallets,ReadState ws,List<Reason> wr,List<WithdrawalEvidence> withdrawals,ReadState ds,List<Reason> dr) {
        return new Snapshot(roots,new WalletRead(wallets,ws,wr,T),new WithdrawalRead(withdrawals,ds,dr,T,CoverageState.UNKNOWN,CoverageState.UNKNOWN),"Asia/Shanghai");
    }
    private static RootDisplayRow profile(String name,String key,String vRank,String level) {
        return new RootDisplayRow(100L,name,"100","ACTIVE",T.minusDays(1),T,T.minusDays(1),null,null,key,vRank,level);
    }
    private static void profile(Funding c,RootDisplayRow row){when(c.f.mapper.rootDisplayRows(eq(c.f.scope),anyCollection())).thenReturn(List.of(row));}
    private static PersonStamp person(String asset,long version){return new PersonStamp(1L,1,1L,1,0,1L,"GENERAL","Agent","agent",asset,version);}
    @SuppressWarnings("unchecked") private static Map<String,Object> firstCustomer(Map<String,Object> response){return ((List<Map<String,Object>>)response.get("records")).get(0);}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") private static Map<String,Object> money(Map<String,Object> response,String currency){return ((List<Map<String,Object>>)((Map<String,Object>)response.get("funds")).get("balances")).stream().filter(m->currency.equals(m.get("currency"))).findFirst().orElseThrow();}
    private static void conflict(Funding c,String old){assertThatThrownBy(()->c.api.query(Map.of("pageNum",List.of("2"),"expectedVersion",List.of(old)))).isInstanceOf(BizException.class).hasMessage("SUPPORT_ANALYTICS_QUERY_CHANGED").satisfies(e->assertThat(((BizException)e).getCode()).isEqualTo(409));}

    @Test void productionConstructorInjectsTheFacadeAndRetainsTheSameRrEntry() throws Exception {
        var ctor=SupportAnalyticsPrivateQueryService.class.getConstructor(SupportOwnershipService.class,SupportAnalyticsService.class,ffdd.opsconsole.content.mapper.SupportAnalyticsMapper.class,SupportFundsReadFacade.class);
        assertThat(ctor.isAnnotationPresent(Autowired.class)).isTrue();var tx=SupportAnalyticsPrivateQueryService.class.getMethod("query",Map.class).getAnnotation(Transactional.class);
        assertThat(tx.readOnly()).isTrue();assertThat(tx.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
        var old=new Fixture();assertThat(((Map<?,?>)old.service.query(Map.of()).get("funds")).get("balanceStatus")).isEqualTo("UNAVAILABLE");
    }
    @Test void customerAndAdvisorImagesExposeOnlyTheAuthorizedExistingEndpoints() {
        var c=new Funding();profile(c,profile("Customer","users/100/avatar/actual-file.png","V2","L1"));
        String asset="12345678-1234-1234-1234-123456789abc";when(c.f.mapper.personnelStamps(eq(c.f.scope),anyCollection())).thenReturn(List.of(person(asset,4)));
        when(c.f.mapper.customerTagRows(eq(c.f.scope),anyCollection())).thenReturn(List.of(new CustomerTagRow(9L,100L,"已联系",T,T)));
        var row=firstCustomer(c.api.query(Map.of()));assertThat(row).containsEntry("avatar","/api/admin/content/support-workbench/customers/100/avatar").containsEntry("level","V2").containsEntry("customTags",List.of("已联系"));
        @SuppressWarnings("unchecked") var owner=(Map<String,Object>)row.get("owner");assertThat(owner).containsEntry("advisorAvatarRef","/api/admin/content/support-agents/1/avatar?customerId=100");
        assertThat(row.toString()).doesNotContain("users/100/avatar/actual-file.png","avatarObjectKey","phone","signedUrl");
        assertThat(row).containsEntry("profileRef","/api/admin/content/support-workbench/customers/100");
    }
    @Test void foreignOrTraversalCustomerAvatarKeyCannotBecomeAPublicReference() {
        for(String key:List.of("users/200/avatar/file.png","users/100/avatar/../file.png","https://storage.example/private")) {
            var c=new Funding();profile(c,profile("Customer",key,null,null));assertThat(firstCustomer(c.api.query(Map.of())).get("avatar")).isNull();
        }
    }
    @Test void sameProfileTagAndAvatarSourcesRemainStableButEveryChangedDisplayedDomainRejectsOldPage() {
        var stable=new Funding();profile(stable,profile("Customer","users/100/avatar/a.png","V1","L1"));
        when(stable.f.mapper.customerTagRows(eq(stable.f.scope),anyCollection())).thenReturn(List.of(new CustomerTagRow(1L,100L,"标签",T,T)));
        assertThat(stable.token()).isEqualTo(stable.token());
        for(String domain:List.of("NAME","PROFILE_UPDATE","AVATAR","LEVEL","TAG","PERSON_AVATAR")) {
            var c=new Funding();profile(c,profile("Customer","users/100/avatar/a.png","V1","L1"));String token=c.token();
            if(domain.equals("NAME"))profile(c,profile("Changed","users/100/avatar/a.png","V1","L1"));
            if(domain.equals("PROFILE_UPDATE"))profile(c,new RootDisplayRow(100L,"Customer","100","ACTIVE",T.minusDays(1),T.plusSeconds(1),T.minusDays(1),null,null,"users/100/avatar/a.png","V1","L1"));
            if(domain.equals("AVATAR"))profile(c,profile("Customer","users/100/avatar/b.png","V1","L1"));
            if(domain.equals("LEVEL"))profile(c,profile("Customer","users/100/avatar/a.png","V2","L1"));
            if(domain.equals("TAG"))when(c.f.mapper.customerTagRows(eq(c.f.scope),anyCollection())).thenReturn(List.of(new CustomerTagRow(1L,100L,"新增标签",T,T)));
            if(domain.equals("PERSON_AVATAR"))when(c.f.mapper.personnelStamps(eq(c.f.scope),anyCollection())).thenReturn(List.of(person("12345678-1234-1234-1234-123456789abc",1)));
            conflict(c,token);
        }
    }
    @Test void missingProfileObservationHasNoReadyTokenAndForeignTagNeverEntersTheResponse() {
        var missing=new Funding();profile(missing,new RootDisplayRow(100L,"Customer","100","ACTIVE",T,null,T,null,null,"users/100/avatar/a.png","V1","L1"));
        assertThat(missing.api.query(Map.of())).containsEntry("versionState","UNKNOWN").containsEntry("queryVersion",null);
        var foreign=new Funding();when(foreign.f.mapper.customerTagRows(eq(foreign.f.scope),anyCollection())).thenReturn(List.of(new CustomerTagRow(1L,200L,"private",T,T)));
        assertThatThrownBy(()->foreign.api.query(Map.of())).hasMessage("SUPPORT_ANALYTICS_SOURCE_UNAVAILABLE");
    }
    @Test void readyWalletAmountsAreConfirmedPerCurrencyWhileWithdrawalCoverageRemainsUnknown() {
        var c=new Funding();var response=c.api.query(Map.of());assertThat(money(response,"USDT")).containsEntry("observedAmount","10").containsEntry("confirmedAmount","10");
        assertThat(money(response,"NEX")).containsEntry("observedAmount","2").containsEntry("confirmedAmount","2");
        @SuppressWarnings("unchecked") var f=(Map<String,Object>)response.get("funds");assertThat(f).containsEntry("historicalEnvironmentStatus","UNKNOWN").containsEntry("eventOwnershipStatus","UNKNOWN").containsEntry("sourceObservationStatus","COMPLETE").containsEntry("walletReadStatus","READY").containsEntry("withdrawalReadStatus","READY");
        @SuppressWarnings("unchecked") var totals=(List<Map<String,Object>>)f.get("withdrawals");assertThat(object(totals.get(0).get("successNet"))).containsEntry("observedAmount","9").containsEntry("confirmedAmount",null);
        verify(c.facade,times(1)).readCurrent(List.of(100L));
    }
    @Test void observedMissingWalletAndNullAmountsCanHaveStableVersionButNeverConfirmedZero() {
        for(boolean missing:List.of(false,true)) {
            var c=new Funding();var wallet=missing?new WalletEvidence(100,null,null,null,null,null,EvidenceState.UNKNOWN,List.of(Reason.WALLET_MISSING)):wallet(100,1,null,null);
            c.snapshot=snapshot(List.of(100L),List.of(wallet),ReadState.UNKNOWN,wallet.reasons(),List.of(),ReadState.READY,List.of());
            String unknown=c.token();var response=c.api.query(Map.of());assertThat(money(response,"USDT")).containsEntry("observedAmount",null).containsEntry("confirmedAmount",null);
            assertThat(object(response.get("funds"))).containsEntry("sourceObservationStatus","COMPLETE").containsEntry("walletReadStatus","UNKNOWN").containsEntry("balanceStatus","UNKNOWN");
            c.snapshot=snapshot(List.of(100L),List.of(wallet(100,1,"0","0")),ReadState.READY,List.of(),List.of(),ReadState.READY,List.of());
            assertThat(money(c.api.query(Map.of()),"USDT")).containsEntry("confirmedAmount","0");conflict(c,unknown);
        }
    }
    @Test void completeRawWithdrawalWithBusinessUnknownCanBeVersionedWithoutClaimingSuccess() {
        var c=new Funding();c.snapshot=snapshot(List.of(100L),List.of(wallet(100,1,"10","2")),ReadState.READY,List.of(),
            List.of(withdrawal(7,100,"new-status",WithdrawalState.UNKNOWN,"10",null,null)),ReadState.UNKNOWN,List.of(Reason.UNKNOWN_WITHDRAWAL_STATUS));
        assertThat(c.token()).isEqualTo(c.token());
        @SuppressWarnings("unchecked") var totals=(List<Map<String,Object>>)((Map<?,?>)c.api.query(Map.of()).get("funds")).get("withdrawals");
        assertThat(object(totals.get(0).get("successPrincipal"))).containsEntry("observedAmount",null).containsEntry("confirmedAmount",null);
    }
    @Test void sourceObservationUnverifiedHasNoTokenEvenWithTypedWalletValues() {
        var c=new Funding();c.snapshot=new Snapshot(List.of(100L),new WalletRead(List.of(wallet(100,1,"10","2")),ReadState.UNKNOWN,List.of(Reason.SOURCE_OBSERVATION_UNVERIFIED),null),c.snapshot.withdrawals(),"Asia/Shanghai");
        var response=c.api.query(Map.of());assertThat(response).containsEntry("versionState","UNKNOWN").containsEntry("queryVersion",null);assertThat(money(response,"USDT")).containsEntry("confirmedAmount",null);
    }
    @Test void eachWalletSourceTupleAndWithdrawalSourceTupleChangeRejectsTheOldPage() {
        for(String domain:List.of("WALLET_ID","WALLET_VERSION","USDT","NEX","WALLET_UPDATE","WITHDRAW_ID","PRINCIPAL_FEE_NET","STATUS","COMPLETED","WITHDRAW_UPDATE")) {
            var c=new Funding();String token=c.token();var w=c.snapshot.wallets().rows().get(0);var d=c.snapshot.withdrawals().rows().get(0);
            WalletEvidence wallet=w;WithdrawalEvidence withdrawal=d;
            if(domain.equals("WALLET_ID"))wallet=new WalletEvidence(100,9000L,w.version(),w.usdtAvailable(),w.nexAvailable(),T,w.state(),w.reasons());
            if(domain.equals("WALLET_VERSION"))wallet=wallet(100,2,"10","2");
            if(domain.equals("USDT"))wallet=wallet(100,1,"11","2");if(domain.equals("NEX"))wallet=wallet(100,1,"10","3");
            if(domain.equals("WALLET_UPDATE"))wallet=new WalletEvidence(100,w.walletId(),w.version(),w.usdtAvailable(),w.nexAvailable(),T.minusSeconds(1),w.state(),w.reasons());
            if(domain.equals("WITHDRAW_ID"))withdrawal=withdrawal(8,100,"CONFIRMED",WithdrawalState.SUCCESS,"10","1","9");
            if(domain.equals("PRINCIPAL_FEE_NET"))withdrawal=withdrawal(7,100,"CONFIRMED",WithdrawalState.SUCCESS,"20","2","18");
            if(domain.equals("STATUS"))withdrawal=withdrawal(7,100,"PROCESSING",WithdrawalState.PROCESSING,"10","1","9");
            if(domain.equals("COMPLETED"))withdrawal=new WithdrawalEvidence(7,100,d.currency(),d.principal(),d.actualFee(),d.net(),d.status(),d.canonicalStatus(),d.state(),T.minusSeconds(1),T,d.reasons());
            if(domain.equals("WITHDRAW_UPDATE"))withdrawal=new WithdrawalEvidence(7,100,d.currency(),d.principal(),d.actualFee(),d.net(),d.status(),d.canonicalStatus(),d.state(),T.minusSeconds(1),T.minusSeconds(1),d.reasons());
            c.snapshot=snapshot(List.of(100L),List.of(wallet),ReadState.READY,List.of(),List.of(withdrawal),ReadState.READY,List.of());conflict(c,token);
        }
    }
    @Test void facadeObservationClocksAndDecimalScaleDoNotChangeTheVersion() {
        var c=new Funding();String before=c.token();var f=c.snapshot;
        c.snapshot=new Snapshot(f.customerIds(),new WalletRead(List.of(wallet(100,1,"10.000000","2.0")),ReadState.READY,List.of(),T.plusSeconds(30)),
            new WithdrawalRead(f.withdrawals().rows(),ReadState.READY,List.of(),T.plusSeconds(30),CoverageState.UNKNOWN,CoverageState.UNKNOWN),f.businessZone());
        assertThat(c.token()).isEqualTo(before);
    }
    @Test void foreignWalletOrWithdrawalAndMissingRequestedWalletCannotFakeTheCompleteScope() {
        for(boolean walletForeign:List.of(false,true)) {
            var c=new Funding();c.snapshot=snapshot(List.of(100L),walletForeign?List.of(wallet(200,1,"999","999")):c.snapshot.wallets().rows(),ReadState.READY,List.of(),
                walletForeign?c.snapshot.withdrawals().rows():List.of(withdrawal(9,200,"CONFIRMED",WithdrawalState.SUCCESS,"999","9","990")),ReadState.READY,List.of());
            assertThatThrownBy(()->c.api.query(Map.of())).hasMessage("SUPPORT_ANALYTICS_SOURCE_UNAVAILABLE");
        }
        var missing=new Funding();missing.snapshot=snapshot(List.of(100L),List.of(),ReadState.READY,List.of(),List.of(),ReadState.READY,List.of());
        assertThat(missing.api.query(Map.of())).containsEntry("versionState","UNKNOWN").containsEntry("queryVersion",null);
    }
    @Test void everySourceFailureReauthorizesBeforeFixed503AndRevocationWinsOverFailureAndStaleVersion() {
        for(String domain:List.of("WALLET","WITHDRAWAL","BOTH"))for(boolean revoked:List.of(false,true)) {
            var c=new Funding();var f=c.snapshot;boolean wf=!domain.equals("WITHDRAWAL"),df=!domain.equals("WALLET");
            c.snapshot=new Snapshot(f.customerIds(),new WalletRead(f.wallets().rows(),wf?ReadState.FAILED:ReadState.UNKNOWN,wf?List.of(Reason.WALLET_READ_FAILED):List.of(),T),
                new WithdrawalRead(f.withdrawals().rows(),df?ReadState.FAILED:ReadState.UNKNOWN,df?List.of(Reason.WITHDRAWAL_READ_FAILED):List.of(),T,CoverageState.UNKNOWN,CoverageState.UNKNOWN),f.businessZone());
            c.f.capture.activityCoverage=null;c.f.capture.activityWindow=null;c.f.capture.activities=Map.of();
            if(revoked)when(c.f.mapper.currentReadGrants(1L)).thenReturn(List.of(new PermissionStamp(1L,2L,3L,4L,"service_m1_read",1,1)),List.of());
            assertThatThrownBy(()->c.api.query(Map.of("expectedVersion",List.of("saq-v1:"+"0".repeat(64))))).isInstanceOf(BizException.class)
                .satisfies(e->assertThat(((BizException)e).getCode()).isEqualTo(revoked?403:503)).hasMessage(revoked?"SUPPORT_SCOPE_FORBIDDEN":"SUPPORT_ANALYTICS_SOURCE_UNAVAILABLE");
            verify(c.f.ownership,times(2)).defaultQueryScope(null,null);
        }
    }
    @Test void rootsOnlyAreReadEvenWhenHistoricalAndDescendantEvidenceIsIncomplete() {
        var c=new Funding();c.f.capture.financialIds=Set.of(100L,900L);
        c.f.capture.trees=Map.of(100L,new ffdd.opsconsole.team.facade.SupportInvitationReadFacade.Invitation(100,0,List.of(800L),List.of(800L),ffdd.opsconsole.team.facade.SupportInvitationReadFacade.Completeness.COMPLETE,Set.of()));
        var response=c.api.query(Map.of());verify(c.facade).readCurrent(List.of(100L));assertThat(money(response,"USDT")).containsEntry("confirmedAmount","10");
        assertThat(firstCustomer(response).get("customerId")).isEqualTo("100");assertThat(response.get("observedTotal")).isEqualTo(1L);
    }
    private static void result(Funding c,List<Customer> customers) {
        var original=c.f.stats.evaluateForQuery(null,c.f.scope,false).result();
        var r=new Result(original.query(),new CurrentScope(c.f.scope.mode(),null,null,Status.AVAILABLE,(long)customers.size(),(long)customers.size(),0L,0L,List.of()),customers,
            original.financialSummary(),original.restrictedSummary(),original.coverage(),original.asOf(),original.reasons(),original.currentMetrics(),original.personnel(),original.groups());
        when(c.f.stats.evaluateForQuery(any(),eq(c.f.scope),eq(false))).thenReturn(new SupportAnalyticsService.QueryEvaluation(r,c.f.capture));
    }
    @Test void filteringUsesFullRootFundsObservationButCustomerAndGroupAmountsUseOnlySelectedRoots() {
        var c=new Funding();var old=c.f.stats.evaluateForQuery(null,c.f.scope,false).result().currentCustomers().get(0);
        var roots=List.of(new Customer(100,Category.BOUND,Placement.GROUPED,false,old.first(),new CurrentOwner(1L,9L),old.metrics()),
            new Customer(200,Category.BOUND,Placement.GROUPED,false,new FirstSelection(null,Status.AVAILABLE,List.of(),FirstState.NONE),new CurrentOwner(1L,9L),old.metrics()));
        c.f.capture.current=Map.of(100L,new CurrentCustomer(100L,"BOUND","GROUPED",0,1L,9L),200L,new CurrentCustomer(200L,"BOUND","GROUPED",0,1L,9L));
        c.f.capture.financialIds=Set.of(100L,200L);var finance=c.f.capture.snapshot;
        var history=new ArrayList<>(finance.firstHistory());history.add(new ffdd.opsconsole.finance.facade.SupportPaymentFacts.FirstHistory(200,ffdd.opsconsole.finance.facade.SupportPaymentFacts.Status.READY,List.of()));
        c.f.capture.snapshot=new ffdd.opsconsole.finance.facade.SupportPaymentFacts.Snapshot(finance.facts(),finance.issues(),finance.coverage(),finance.businessZone(),finance.evaluatedAt(),history);
        c.f.capture.firstHistory=Map.of(100L,history.get(0),200L,history.get(1));
        var tree=c.f.capture.trees.get(100L);c.f.capture.trees=Map.of(100L,tree,200L,new ffdd.opsconsole.team.facade.SupportInvitationReadFacade.Invitation(200,0,List.of(),List.of(),ffdd.opsconsole.team.facade.SupportInvitationReadFacade.Completeness.COMPLETE,Set.of()));
        c.f.capture.activities=Map.of(100L,c.f.capture.activities.get(100L),200L,c.f.capture.activities.get(100L));result(c,roots);
        when(c.f.mapper.currentGrantedGroupIds(c.f.scope)).thenReturn(List.of(9L));when(c.f.mapper.groupStamps(eq(c.f.scope),anyCollection())).thenReturn(List.of(new GroupStamp(9L,"Group",1L,"ENABLED",1L)));
        when(c.f.mapper.ownerStamps(eq(c.f.scope),anyCollection())).thenReturn(List.of(new RelationshipStamp("GROUP_OWNER",90L,null,null,9L,1L,"PRESENT",0,1L,T,null)));
        when(c.f.mapper.assignmentStamps(eq(c.f.scope),anyCollection())).thenReturn(List.of(new RelationshipStamp("ASSIGNMENT",10L,100L,1L,9L,null,"ACTIVE",0,1L,T,null),new RelationshipStamp("ASSIGNMENT",20L,200L,1L,9L,null,"ACTIVE",0,1L,T,null)));
        when(c.f.mapper.rootDisplayRows(eq(c.f.scope),anyCollection())).thenReturn(List.of(profile("Customer",null,null,null),new RootDisplayRow(200L,"Other","200","ACTIVE",T,T,T,null,null)));
        when(c.f.mapper.activityIdentityRows(eq(c.f.scope),anyCollection(),eq(T))).thenReturn(List.of(new ActivityIdentityRow(100L,10L,1L,"LOGIN:10",T),new ActivityIdentityRow(200L,20L,1L,"LOGIN:20",T)));
        when(c.f.mapper.taskRows(eq(c.f.scope),anyCollection(),eq(T),any(),any(),any(),eq(7),isNull(),eq(T))).thenReturn(List.of(new TaskRow(100L,1,1L,null,null,null,null,1,null,null,0L,0,0,null,"ACTIVE","ACTIVE",T,0,null,null,null),new TaskRow(200L,1,1L,null,null,null,null,1,null,null,0L,0,0,null,"ACTIVE","ACTIVE",T,0,null,null,null)));
        c.snapshot=snapshot(List.of(100L,200L),List.of(wallet(100,1,"10","2"),wallet(200,1,"90","8")),ReadState.READY,List.of(),
            List.of(withdrawal(7,100,"CONFIRMED",WithdrawalState.SUCCESS,"10","1","9"),withdrawal(8,200,"CONFIRMED",WithdrawalState.SUCCESS,"90","9","81")),ReadState.READY,List.of());
        var response=c.api.query(Map.of("keyword",List.of("Customer")));assertThat(response).containsEntry("versionState","READY").containsEntry("selectedCustomerCount",1L);
        verify(c.facade).readCurrent(List.of(100L,200L));assertThat(money(response,"USDT")).containsEntry("confirmedAmount","10");
        @SuppressWarnings("unchecked") var groups=(List<Map<String,Object>>)((Map<?,?>)response.get("funds")).get("groups");
        @SuppressWarnings("unchecked") var groupFunds=(Map<String,Object>)groups.get(0).get("funds");
        assertThat(groups.get(0)).containsEntry("groupId","9");assertThat(money(Map.of("funds",groupFunds),"USDT")).containsEntry("confirmedAmount","10");
        @SuppressWarnings("unchecked") var customerFunds=(Map<String,Object>)firstCustomer(response).get("funds");assertThat(money(Map.of("funds",customerFunds),"NEX")).containsEntry("confirmedAmount","2");
    }
    @Test void authorizedEmptyRootsDoNotReadFundSourcesOrInventWithdrawalHistoryZero() {
        var c=new Funding();c.f.capture.current=Map.of();c.f.capture.financialIds=Set.of();c.f.capture.snapshot=null;c.f.capture.first=Map.of();c.f.capture.firstHistory=Map.of();c.f.capture.trees=Map.of();c.f.capture.activities=Map.of();result(c,List.of());
        when(c.f.mapper.rootDisplayRows(eq(c.f.scope),anyCollection())).thenReturn(List.of());when(c.f.mapper.assignmentStamps(eq(c.f.scope),anyCollection())).thenReturn(List.of());
        when(c.f.mapper.activityIdentityRows(eq(c.f.scope),anyCollection(),eq(T))).thenReturn(List.of());
        when(c.f.mapper.taskRows(eq(c.f.scope),anyCollection(),eq(T),any(),any(),any(),eq(7),isNull(),eq(T))).thenReturn(List.of());
        c.snapshot=snapshot(List.of(),List.of(),ReadState.READY,List.of(),List.of(),ReadState.READY,List.of());
        var response=c.api.query(Map.of());assertThat(response).containsEntry("versionState","READY").containsEntry("total",0L);verify(c.facade).readCurrent(List.of());
        assertThat(money(response,"USDT")).containsEntry("confirmedAmount","0");assertThat(object(response.get("funds"))).containsEntry("withdrawals",List.of()).containsEntry("historicalEnvironmentStatus","UNKNOWN");
        // The mock empty facade call is not SQL proof; actual facade's proven empty path executes zero source calls.
    }
    @Test void nonemptyRootScopeWithEmptySelectionKeepsFullSourceVersionAndZeroSelectedFunds() {
        var c=new Funding();var old=c.f.stats.evaluateForQuery(null,c.f.scope,false).result().currentCustomers().get(0);
        result(c,List.of(new Customer(100,Category.BOUND,Placement.GROUPED,false,old.first(),new CurrentOwner(1L,9L),old.metrics())));
        c.f.capture.current=Map.of(100L,new CurrentCustomer(100L,"BOUND","GROUPED",0,1L,9L));
        when(c.f.mapper.currentGrantedGroupIds(c.f.scope)).thenReturn(List.of(9L));
        when(c.f.mapper.groupStamps(eq(c.f.scope),anyCollection())).thenReturn(List.of(new GroupStamp(9L,"Group",1L,"ENABLED",1L)));
        when(c.f.mapper.ownerStamps(eq(c.f.scope),anyCollection())).thenReturn(List.of(new RelationshipStamp("GROUP_OWNER",90L,null,null,9L,1L,"PRESENT",0,1L,T,null)));
        when(c.f.mapper.assignmentStamps(eq(c.f.scope),anyCollection())).thenReturn(List.of(new RelationshipStamp("ASSIGNMENT",10L,100L,1L,9L,null,"ACTIVE",0,1L,T,null)));
        doCallRealMethod().when(c.f.stats).selectedCurrent(any(),anyList());
        var query=Map.of("keyword",List.of("NO_MATCH_CUSTOMER"));var response=c.api.query(query);
        assertThat(response).containsEntry("versionState","READY").containsEntry("total",0L).containsEntry("selectedCustomerCount",0L).containsEntry("records",List.of());
        verify(c.facade,times(1)).readCurrent(List.of(100L));
        verify(c.f.mapper).rootDisplayRows(eq(c.f.scope),eq(new TreeSet<>(List.of(100L))));
        verify(c.f.stats).selectedCurrent(any(),eq(List.of()));
        var selectedCurrent=object(response.get("selectedCurrent"));assertThat(selectedCurrent).containsEntry("ownLifetime",List.of());
        assertThat(object(selectedCurrent.get("firstConfirmed"))).containsEntry("observed",0L).containsEntry("confirmed",0L);
        assertThat(money(response,"USDT")).containsEntry("observedAmount","0").containsEntry("confirmedAmount","0");
        assertThat(money(response,"NEX")).containsEntry("observedAmount","0").containsEntry("confirmedAmount","0");
        @SuppressWarnings("unchecked") var groups=(List<Map<String,Object>>)object(response.get("funds")).get("groups");assertThat(groups).hasSize(1);
        assertThat(groups.get(0)).containsEntry("groupId","9");var groupFunds=object(groups.get(0).get("funds"));
        assertThat(groupFunds).containsEntry("customerCount",0L).containsEntry("withdrawals",List.of()).containsEntry("historicalEnvironmentStatus","UNKNOWN");
        assertThat(money(Map.of("funds",groupFunds),"USDT")).containsEntry("observedAmount","0").containsEntry("confirmedAmount","0");
        assertThat(money(Map.of("funds",groupFunds),"NEX")).containsEntry("observedAmount","0").containsEntry("confirmedAmount","0");
        String token=(String)response.get("queryVersion");assertThat(token).startsWith("saq-v1:");assertThat(c.api.query(query).get("queryVersion")).isEqualTo(token);
        c.snapshot=snapshot(List.of(100L),List.of(wallet(100,2,"20","3")),ReadState.READY,List.of(),c.snapshot.withdrawals().rows(),ReadState.READY,List.of());
        assertThatThrownBy(()->c.api.query(Map.of("keyword",List.of("NO_MATCH_CUSTOMER"),"pageNum",List.of("2"),"expectedVersion",List.of(token))))
            .isInstanceOf(BizException.class).hasMessage("SUPPORT_ANALYTICS_QUERY_CHANGED").satisfies(e->assertThat(((BizException)e).getCode()).isEqualTo(409));
        verify(c.facade,times(3)).readCurrent(List.of(100L));
    }
    @Test void mixedKnownDualCurrencyAndMissingWalletKeepsObservedAmountsAndUnknownCustomerWithoutConfirmedTotal() {
        var c=new Funding();var old=c.f.stats.evaluateForQuery(null,c.f.scope,false).result().currentCustomers().get(0);
        result(c,List.of(old,new Customer(200,Category.BOUND,Placement.UNGROUPED,false,new FirstSelection(null,Status.AVAILABLE,List.of(),FirstState.NONE),new CurrentOwner(1L,null),old.metrics())));
        c.f.capture.current=Map.of(100L,new CurrentCustomer(100L,"BOUND","UNGROUPED",0,1L,null),200L,new CurrentCustomer(200L,"BOUND","UNGROUPED",0,1L,null));
        c.f.capture.financialIds=Set.of(100L,200L);var finance=c.f.capture.snapshot;
        var history=new ArrayList<>(finance.firstHistory());history.add(new ffdd.opsconsole.finance.facade.SupportPaymentFacts.FirstHistory(200,ffdd.opsconsole.finance.facade.SupportPaymentFacts.Status.READY,List.of()));
        c.f.capture.snapshot=new ffdd.opsconsole.finance.facade.SupportPaymentFacts.Snapshot(finance.facts(),finance.issues(),finance.coverage(),finance.businessZone(),finance.evaluatedAt(),history);
        c.f.capture.firstHistory=Map.of(100L,history.get(0),200L,history.get(1));
        c.f.capture.trees=Map.of(100L,c.f.capture.trees.get(100L),200L,new ffdd.opsconsole.team.facade.SupportInvitationReadFacade.Invitation(200,0,List.of(),List.of(),ffdd.opsconsole.team.facade.SupportInvitationReadFacade.Completeness.COMPLETE,Set.of()));
        c.f.capture.activities=Map.of(100L,c.f.capture.activities.get(100L),200L,c.f.capture.activities.get(100L));
        when(c.f.mapper.assignmentStamps(eq(c.f.scope),anyCollection())).thenReturn(List.of(new RelationshipStamp("ASSIGNMENT",10L,100L,1L,null,null,"ACTIVE",0,1L,T,null),new RelationshipStamp("ASSIGNMENT",20L,200L,1L,null,null,"ACTIVE",0,1L,T,null)));
        when(c.f.mapper.rootDisplayRows(eq(c.f.scope),anyCollection())).thenReturn(List.of(profile("Customer",null,null,null),new RootDisplayRow(200L,"Missing","200","ACTIVE",T,T,T,null,null)));
        when(c.f.mapper.activityIdentityRows(eq(c.f.scope),anyCollection(),eq(T))).thenReturn(List.of(new ActivityIdentityRow(100L,10L,1L,"LOGIN:10",T),new ActivityIdentityRow(200L,20L,1L,"LOGIN:20",T)));
        when(c.f.mapper.taskRows(eq(c.f.scope),anyCollection(),eq(T),any(),any(),any(),eq(7),isNull(),eq(T))).thenReturn(List.of(new TaskRow(100L,1,1L,null,null,null,null,1,null,null,0L,0,0,null,"ACTIVE","ACTIVE",T,0,null,null,null),new TaskRow(200L,1,1L,null,null,null,null,1,null,null,0L,0,0,null,"ACTIVE","ACTIVE",T,0,null,null,null)));
        c.snapshot=snapshot(List.of(100L,200L),List.of(wallet(100,1,"10","2"),new WalletEvidence(200,null,null,null,null,null,EvidenceState.UNKNOWN,List.of(Reason.WALLET_MISSING))),ReadState.PARTIAL,List.of(Reason.WALLET_MISSING),c.snapshot.withdrawals().rows(),ReadState.READY,List.of());
        var response=c.api.query(Map.of());assertThat(response).containsEntry("versionState","READY").containsEntry("total",2L).containsEntry("selectedCustomerCount",2L);
        verify(c.facade,times(1)).readCurrent(List.of(100L,200L));
        assertThat(object(response.get("funds"))).containsEntry("sourceObservationStatus","COMPLETE").containsEntry("walletReadStatus","PARTIAL").containsEntry("balanceStatus","PARTIAL").containsEntry("walletReadReasons",List.of("WALLET_MISSING"));
        assertThat(money(response,"USDT")).containsEntry("observedAmount","10").containsEntry("confirmedAmount",null);
        assertThat(money(response,"NEX")).containsEntry("observedAmount","2").containsEntry("confirmedAmount",null);
        @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)response.get("records");
        var known=object(rows.stream().filter(r->"100".equals(r.get("customerId"))).findFirst().orElseThrow().get("funds"));
        var missing=object(rows.stream().filter(r->"200".equals(r.get("customerId"))).findFirst().orElseThrow().get("funds"));
        assertThat(known).containsEntry("balanceStatus","READY");assertThat(money(Map.of("funds",known),"USDT")).containsEntry("confirmedAmount","10");assertThat(money(Map.of("funds",known),"NEX")).containsEntry("confirmedAmount","2");
        assertThat(missing).containsEntry("balanceStatus","UNKNOWN");assertThat(money(Map.of("funds",missing),"USDT")).containsEntry("observedAmount",null).containsEntry("confirmedAmount",null);assertThat(money(Map.of("funds",missing),"NEX")).containsEntry("observedAmount",null).containsEntry("confirmedAmount",null);
    }
}
