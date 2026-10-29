package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportAnalyticsStats.*;
import static ffdd.opsconsole.content.domain.SupportAnalyticsStats.*;
import ffdd.opsconsole.content.domain.SupportGroupFacts.*;
import ffdd.opsconsole.content.domain.SupportRules;
import ffdd.opsconsole.content.dto.SupportAnalyticsQueryRequest.Direction;
import ffdd.opsconsole.content.mapper.SupportAnalyticsMapper;
import ffdd.opsconsole.content.mapper.SupportAnalyticsMapper.*;
import ffdd.opsconsole.device.facade.SupportDeviceReadFacade;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import ffdd.opsconsole.team.facade.SupportInvitationReadFacade;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Pure/mock contract tests only. RR isolation, locking grants and SQL are an independent MySQL acceptance gate. */
class SupportAnalyticsPrivateQueryServiceTest {
    @Test void customerBodiesAreProjectedOnlyAfterSortingAndPaging() {
        var projected=new ArrayList<Long>();
        var rows=new ArrayList<SupportAnalyticsPrivateQueryService.Row>();
        for(long id:List.of(3L,1L,2L)) {
            rows.add(new SupportAnalyticsPrivateQueryService.Row(id,"",id,()->{
                projected.add(id);return Map.of("customerId",Long.toString(id));
            }));
        }
        rows.sort(SupportAnalyticsPrivateQueryService.rowOrder(Direction.ASC));
        assertThat(projected).isEmpty();
        assertThat(rows.subList(1,2).stream().map(SupportAnalyticsPrivateQueryService.Row::body).toList())
            .containsExactly(Map.of("customerId","2"));
        assertThat(projected).containsExactly(2L);
    }
    static final LocalDateTime T=LocalDateTime.of(2026,10,1,8,0);
    static void authenticate(String... codes) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("1","unused",
            Arrays.stream(codes).map(SimpleGrantedAuthority::new).toList()));
    }
    @BeforeEach void authentication(){authenticate("service_m1_read");}
    @AfterEach void clearAuthentication(){SecurityContextHolder.clearContext();}
    static final class Fixture {
        final SupportOwnershipService ownership=mock(SupportOwnershipService.class);
        final SupportAnalyticsService stats=mock(SupportAnalyticsService.class);
        final SupportAnalyticsMapper mapper=mock(SupportAnalyticsMapper.class);
        final ReadScope scope;
        final SupportAnalyticsService.QueryCapture capture=new SupportAnalyticsService.QueryCapture();
        final SupportAnalyticsPrivateQueryService service=new SupportAnalyticsPrivateQueryService(ownership,stats,mapper);
        Fixture(){this(ReadMode.PERSONAL);}
        Fixture(boolean missingWindow){this(ReadMode.PERSONAL,missingWindow);}
        Fixture(ReadMode mode){this(mode,false);}
        Fixture(ReadMode mode,boolean missingWindow) {
            scope=new ReadScope(1L,mode,null,null);
            when(ownership.defaultQueryScope(null,null)).thenReturn(scope);
            when(mapper.currentReadGrants(1L)).thenReturn(List.of(new PermissionStamp(1L,2L,3L,4L,"service_m1_read",1,1)));
            when(mapper.currentGrantedGroupIds(scope)).thenReturn(List.of());
            capture.scope=scope;capture.current=Map.of(100L,new CurrentCustomer(100L,"BOUND","UNGROUPED",0,1L,null));
            capture.financialIds=Set.of(100L);
            var f=new SupportPaymentFacts.Fact("DEPOSIT:7",SupportPaymentFacts.Kind.DEPOSIT,SupportPaymentFacts.Source.DEPOSIT_ORDER,List.of("source7"),100,7,"source7",null,null,null,"USDT",BigDecimal.TEN,T,"paid_at",6,null,T,T,"v1",SupportPaymentFacts.Status.UNKNOWN);
            capture.facts=Map.of(f.factId(),f);capture.first=Map.of(100L,f);capture.selected=List.of(f);
            capture.firstHistory=Map.of(100L,new SupportPaymentFacts.FirstHistory(100,SupportPaymentFacts.Status.READY,List.of()));
            capture.snapshot=new SupportPaymentFacts.Snapshot(List.of(f),List.of(),Arrays.stream(SupportPaymentFacts.Source.values()).filter(s->s!=SupportPaymentFacts.Source.FREE_TRIAL && s!=SupportPaymentFacts.Source.UNMATCHED_LEDGER)
                .map(s->new SupportPaymentFacts.Coverage(s,SupportPaymentFacts.Status.READY,SupportPaymentFacts.Status.UNKNOWN,SupportPaymentFacts.Status.UNKNOWN,SupportPaymentFacts.Status.UNKNOWN,T,List.of("HISTORY_UNPROVEN"),0,"v1")).toList(),"Asia/Shanghai",Instant.EPOCH,List.copyOf(capture.firstHistory.values()));
            capture.trees=Map.of(100L,new SupportInvitationReadFacade.Invitation(100,0,List.of(),List.of(),SupportInvitationReadFacade.Completeness.COMPLETE,Set.of()));
            capture.stock=new SupportDeviceReadFacade.Snapshot(List.of(),List.of(),T);
            capture.activityCoverage=new ActivityCoverage(T.minusDays(30),T,T);
            capture.activityWindow=new ActivityWindow(missingWindow?null:7,missingWindow?null:T.minusDays(7),T,T.minusDays(30),1L,"INTERACTIVE_LOGIN",missingWindow?Status.UNKNOWN:Status.AVAILABLE);
            capture.activities=Map.of(100L,new CustomerActivity(missingWindow?null:T,missingWindow?WindowState.UNKNOWN:WindowState.ACTIVE,missingWindow?Status.UNKNOWN:Status.AVAILABLE,List.of()));
            var customer=new Customer(100,Category.BOUND,Placement.UNGROUPED,false,new FirstSelection(new FirstCandidate("DEPOSIT","DEPOSIT_ORDER",T,6,BigDecimal.TEN,"USDT",new Attribution(AttributionStatus.UNKNOWN,AttributionStatus.UNKNOWN,AttributionStatus.UNKNOWN)),Status.AVAILABLE,List.of(),FirstState.CONFIRMED),new CurrentOwner(1L,null),new CustomerMetrics(Basis.CURRENT_CUSTOMER_HISTORY,Status.UNKNOWN,List.of(),new InvitationSummary(new Count(0L,0L,Status.AVAILABLE),new Count(0L,0L,Status.AVAILABLE),List.of(),Status.AVAILABLE,List.of()),unavailableDevices(),capture.activities.get(100L)));
            var result=new Result(new Query(scope.mode(),null,null,Basis.CURRENT_CUSTOMER_HISTORY,null,null,"Asia/Shanghai",null),new CurrentScope(scope.mode(),null,null,Status.AVAILABLE,1L,1L,0L,0L,List.of()),List.of(customer),new FinancialSummary(Status.UNKNOWN,List.of(),new Count(1L,1L,Status.AVAILABLE),List.of(),List.of()),new RestrictedSummary(new Count(0L,null,Status.UNKNOWN),new Count(0L,null,Status.UNKNOWN)),List.of(),Instant.EPOCH,List.of(),unavailableCurrentMetrics(),unavailablePersonnel(),List.of());
            when(stats.evaluateForQuery(any(),eq(scope),eq(false))).thenReturn(new SupportAnalyticsService.QueryEvaluation(result,capture));
            when(stats.selectedCurrent(any(),anyList())).thenReturn(unavailableCurrentMetrics());
            when(mapper.groupStamps(eq(scope),anyCollection())).thenReturn(List.of());
            when(mapper.personnelStamps(eq(scope),anyCollection())).thenReturn(List.of(new PersonStamp(1L,1,1L,1,0,1L,"GENERAL","Agent","agent")));
            when(mapper.roleStamps(eq(scope),anyCollection())).thenReturn(List.of(new RoleStamp(1L,2L,1L,"SUPPORT",1)));
            when(mapper.qualificationStamps(eq(scope),anyCollection())).thenReturn(List.of(new QualificationStamp(1L,1L,"SERVICE","ENABLED",1L,T.minusDays(1),null)));
            when(mapper.assignmentStamps(eq(scope),anyCollection())).thenReturn(List.of(new RelationshipStamp("ASSIGNMENT",10L,100L,1L,null,null,"ACTIVE",0,1L,T.minusDays(1),null)));
            when(mapper.routeStamps(eq(scope),anyCollection())).thenReturn(List.of());when(mapper.memberStamps(eq(scope),anyCollection())).thenReturn(List.of());when(mapper.ownerStamps(eq(scope),anyCollection())).thenReturn(List.of());
            when(mapper.rootDisplayRows(eq(scope),anyCollection())).thenReturn(List.of(new RootDisplayRow(100L,"Customer","U00000100","ACTIVE",T.minusDays(1),T,T.minusDays(1),null,null)));
            when(mapper.queryRules(scope)).thenReturn(new SupportRules(1L,7,7,missingWindow?null:7,"INFINITE",null,"SUPERVISOR",null));
            when(mapper.activityIdentityRows(eq(scope),anyCollection(),eq(T))).thenReturn(List.of(new ActivityIdentityRow(100L,10L,1L,"LOGIN:10",T)));
            when(mapper.taskRows(eq(scope),anyCollection(),eq(T),any(),any(),any(),eq(7),isNull(),eq(T))).thenReturn(List.of(new TaskRow(100L,1,1L,null,null,null,null,1,null,null,0L,0,0,null,"ACTIVE",missingWindow?"UNKNOWN":"ACTIVE",T,0,null,null,null)));
            when(mapper.pendingReplyRows(eq(scope),anyCollection())).thenReturn(List.of());
        }
    }
    @Test void missingWindowDoesNotBlockNonemptyActiveOrFirstContactTodoButKeepsUnknownActivityAndVersion() {
        for(String filter:List.of("ACTIVE","TODO")) {
            var f=new Fixture(true);
            var response=f.service.query(Map.of("view",List.of("CUSTOMERS"),"filter",List.of(filter)));
            @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)response.get("records");
            assertThat(rows).singleElement().satisfies(row->{
                assertThat(row).containsEntry("customerId","100");
                @SuppressWarnings("unchecked") var activity=(Map<String,Object>)row.get("activity");
                @SuppressWarnings("unchecked") var task=(Map<String,Object>)row.get("task");
                assertThat(activity).containsEntry("state","UNKNOWN").containsEntry("status","UNKNOWN");
                assertThat(task).containsEntry("status","COMPLETE").containsEntry("firstContact",true);
            });
            assertThat(response).containsEntry("versionState","UNKNOWN").containsEntry("queryVersion",null).containsEntry("total",null);
            assertThat(f.capture.activityWindow.status()).isEqualTo(Status.UNKNOWN);
            verify(f.mapper).taskRows(eq(f.scope),eq(Set.of(100L)),eq(T),eq(T.minusDays(30)),eq(T.minusDays(7)),isNull(),eq(7),isNull(),eq(T));
            verify(f.mapper,never()).activityIdentityRows(any(),anyCollection(),any());
        }
    }
    @Test void activeWithoutDormantRuleStillFailsClosed() {
        var f=new Fixture(true);when(f.mapper.queryRules(f.scope)).thenReturn(new SupportRules(1L,null,7,null,"INFINITE",null,"SUPERVISOR",null));
        assertThatThrownBy(()->f.service.query(Map.of("filter",List.of("ACTIVE")))).isInstanceOf(BizException.class)
            .satisfies(ex->assertThat(((BizException)ex).getCode()).isEqualTo(503));
        verify(f.ownership,times(2)).defaultQueryScope(null,null);
    }
    @Test void missingOrInvalidActivityCoverageDoesNotReadTasksOrInventFilteredEmptyResults() {
        for(ActivityCoverage coverage:Arrays.asList(null,new ActivityCoverage(T.plusDays(1),T,T),new ActivityCoverage(T.minusDays(30),T,T.minusMinutes(1)))) {
            for(String filter:List.of("ACTIVE","TODO")) {
                var f=new Fixture(true);f.capture.activityCoverage=coverage;
                assertThatThrownBy(()->f.service.query(Map.of("filter",List.of(filter)))).isInstanceOf(BizException.class)
                    .satisfies(ex->assertThat(((BizException)ex).getCode()).isEqualTo(503));
                verify(f.mapper,never()).taskRows(any(),anyCollection(),any(),any(),any(),any(),any(),any(),any());
                verify(f.ownership,times(2)).defaultQueryScope(null,null);
            }
        }
    }
    @Test void taskSqlFailureWithoutWindowStillFailsClosedAndReauthorizes() {
        var f=new Fixture(true);when(f.mapper.taskRows(any(),anyCollection(),any(),any(),any(),any(),any(),any(),any()))
            .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("task source unavailable"));
        assertThatThrownBy(()->f.service.query(Map.of("filter",List.of("TODO")))).isInstanceOf(BizException.class)
            .satisfies(ex->assertThat(((BizException)ex).getCode()).isEqualTo(503));
        verify(f.ownership,times(2)).defaultQueryScope(null,null);
    }
    @Test void finalRevocationWithoutWindowWinsBeforeAnyTaskFilterIsReturned() {
        var f=new Fixture(true);when(f.ownership.defaultQueryScope(null,null)).thenReturn(f.scope).thenThrow(new BizException(403,"SUPPORT_SCOPE_FORBIDDEN"));
        assertThatThrownBy(()->f.service.query(Map.of("filter",List.of("TODO")))).isInstanceOf(BizException.class)
            .satisfies(ex->assertThat(((BizException)ex).getCode()).isEqualTo(403));
        verify(f.mapper).taskRows(any(),anyCollection(),any(),any(),any(),any(),any(),any(),any());
    }
    @Test void canonicalCustomerCodeNumericIdAndNameSearchKeepTheAuthorizedCustomerAndRejectWrongCodes() {
        var f=new Fixture();
        for(String view:List.of("CUSTOMERS","OVERVIEW","FINANCE","ACTIVITY")) {
            for(String keyword:List.of("U00000100","u00000100","100","Cust","customer")) {
                var response=f.service.query(Map.of("view",List.of(view),"keyword",List.of(keyword)));
                @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)response.get("records");
                assertThat(rows).as("%s search %s",view,keyword).singleElement()
                    .satisfies(row->assertThat(row).containsEntry("customerId","100").containsEntry("customerNo","U00000100"));
            }
            for(String keyword:List.of("U00000101","U100","U00000100x","0100","101"))
                assertThat((List<?>)f.service.query(Map.of("view",List.of(view),"keyword",List.of(keyword))).get("records"))
                    .as("%s rejects %s",view,keyword).isEmpty();
        }
        verify(f.mapper,atLeastOnce()).rootDisplayRows(eq(f.scope),eq(Set.of(100L)));
    }
    @Test void sameEvaluationFeedsPageCardsAndReadyVersionWithoutAnotherSummaryCall() {
        var f=new Fixture();var first=f.service.query(Map.of());String token=(String)first.get("queryVersion");assertThat(token).startsWith("saq-v1:");
        var second=f.service.query(Map.of("pageNum",List.of("2"),"expectedVersion",List.of(token)));
        assertThat(second.get("queryVersion")).isEqualTo(token);assertThat((List<?>)second.get("records")).isEmpty();
        verify(f.stats,times(2)).evaluateForQuery(any(),eq(f.scope),eq(false));verify(f.stats,never()).summarize(any());
    }
    @Test void staleVersionIs409WithOnlyFixedBusinessCode() {
        var f=new Fixture();assertThatThrownBy(()->f.service.query(Map.of("pageNum",List.of("2"),"expectedVersion",List.of("saq-v1:"+"0".repeat(64)))))
            .isInstanceOf(BizException.class).satisfies(ex->assertThat(((BizException)ex).getCode()).isEqualTo(409)).hasMessage("SUPPORT_ANALYTICS_QUERY_CHANGED");
    }
    @Test void revokedGrantWinsOverStaleVersionAndStatsAreNotRead() {
        var f=new Fixture();when(f.mapper.currentReadGrants(1L)).thenReturn(List.of());
        assertThatThrownBy(()->f.service.query(Map.of("pageNum",List.of("2"),"expectedVersion",List.of("saq-v1:"+"0".repeat(64)))))
            .isInstanceOf(BizException.class).satisfies(ex->assertThat(((BizException)ex).getCode()).isEqualTo(403));verifyNoInteractions(f.stats);
    }
    @Test void finalRevocationAlsoWinsOverConflict() {
        var f=new Fixture();when(f.ownership.defaultQueryScope(null,null)).thenReturn(f.scope).thenThrow(new BizException(403,"SUPPORT_SCOPE_FORBIDDEN"));
        assertThatThrownBy(()->f.service.query(Map.of("expectedVersion",List.of("saq-v1:"+"0".repeat(64)))))
            .isInstanceOf(BizException.class).satisfies(ex->assertThat(((BizException)ex).getCode()).isEqualTo(403));
    }
    @Test void finalRevocationWinsEvenWhenTheCurrentSourceFailed() {
        var f=new Fixture();f.capture.currentFailed=true;when(f.ownership.defaultQueryScope(null,null)).thenReturn(f.scope).thenThrow(new BizException(403,"SUPPORT_SCOPE_FORBIDDEN"));
        assertThatThrownBy(()->f.service.query(Map.of("expectedVersion",List.of("saq-v1:"+"0".repeat(64)))))
            .isInstanceOf(BizException.class).satisfies(ex->assertThat(((BizException)ex).getCode()).isEqualTo(403));
    }
    @Test void failedRosterIsNotAReadyEmptyDirectory() {
        var f=new Fixture();f.capture.rosterStatus=Status.FAILED;
        assertThatThrownBy(()->f.service.query(Map.of())).isInstanceOf(BizException.class).satisfies(ex->assertThat(((BizException)ex).getCode()).isEqualTo(503));
    }
    @Test void directOtherGroupFailsBeforeAnySourceOrVersionRead() {
        var f=new Fixture();when(f.ownership.defaultQueryScope(99L,null)).thenThrow(new BizException(404,"SUPPORT_GROUP_NOT_FOUND"));
        assertThatThrownBy(()->f.service.query(Map.of("groupId",List.of("99"),"expectedVersion",List.of("saq-v1:"+"0".repeat(64)))))
            .isInstanceOf(BizException.class).satisfies(ex->assertThat(((BizException)ex).getCode()).isEqualTo(404));verifyNoInteractions(f.mapper,f.stats);
    }
    @Test void financeIsAnActualTransactionRowAndUsesDecimalStringsWithoutCanonicalIds() {
        var f=new Fixture();var response=f.service.query(Map.of("view",List.of("FINANCE")));
        @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)response.get("records");
        assertThat(rows).hasSize(1);assertThat(rows.get(0)).containsEntry("kind","DEPOSIT").containsEntry("amount","10");
        assertThat(rows.get(0)).doesNotContainKeys("factId","ledgerId","sourceBusinessId","sourceIds","evidence");
        assertThat(((Map<?,?>)response.get("funds")).get("balanceStatus")).isEqualTo("UNAVAILABLE");
    }
    @Test void financeAmountSortRequiresExplicitCurrencyAndClientRoleNeverGrantsScope() {
        var f=new Fixture();assertThatThrownBy(()->f.service.query(Map.of("view",List.of("FINANCE"),"sortKey",List.of("AMOUNT")))).hasMessage("SUPPORT_ANALYTICS_CURRENCY_REQUIRED");
        assertThatThrownBy(()->f.service.query(Map.of("mode",List.of("ALL")))).isInstanceOf(BizException.class);
        assertThatThrownBy(()->f.service.query(Map.of("view",List.of("FINANCE"),"currency",List.of("BTC")))).hasMessage("SUPPORT_ANALYTICS_CURRENCY_INVALID");
        verifyNoInteractions(f.stats,f.mapper);
    }
    @Test void changedCanonicalSourceWithIdenticalAmountInvalidatesOldPage() {
        var f=new Fixture();String token=(String)f.service.query(Map.of()).get("queryVersion");var old=f.capture.snapshot;
        var a=old.facts().get(0);var changed=new SupportPaymentFacts.Fact(a.factId(),a.kind(),SupportPaymentFacts.Source.CARD_TOPUP,List.of("card7"),a.customerId(),a.ledgerId(),"card7",a.orderNo(),a.orderType(),a.originalFactId(),a.currency(),a.amount(),a.succeededAt(),a.successTimeField(),a.fractionalSecondDigits(),a.providerPaidAt(),a.ledgerRecordedAt(),a.sourceConfirmationAt(),a.sourceVersion(),a.historicalEnvironmentStatus());
        f.capture.snapshot=new SupportPaymentFacts.Snapshot(List.of(changed),old.issues(),old.coverage(),old.businessZone(),Instant.now(),old.firstHistory());
        assertThatThrownBy(()->f.service.query(Map.of("pageNum",List.of("2"),"expectedVersion",List.of(token)))).hasMessage("SUPPORT_ANALYTICS_QUERY_CHANGED");
    }
    @Test void unknownObservationNeverHasStableTokenOrExactTotalAndCannotContinue() {
        var f=new Fixture();var old=f.capture.snapshot;var cover=new ArrayList<>(old.coverage());var a=cover.get(0);cover.set(0,new SupportPaymentFacts.Coverage(a.source(),SupportPaymentFacts.Status.UNKNOWN,a.historyStatus(),a.refundStatus(),a.historicalEnvironmentStatus(),a.supportedFrom(),a.reasons(),0,a.adapterVersion()));
        f.capture.snapshot=new SupportPaymentFacts.Snapshot(old.facts(),old.issues(),cover,old.businessZone(),Instant.EPOCH,old.firstHistory());
        var response=f.service.query(Map.of());assertThat(response).containsEntry("versionState","UNKNOWN").containsEntry("queryVersion",null).containsEntry("total",null).containsEntry("canContinue",false);
        assertThatThrownBy(()->f.service.query(Map.of("pageNum",List.of("2"),"expectedVersion",List.of("saq-v1:"+"0".repeat(64))))).hasMessage("SUPPORT_ANALYTICS_VERSION_UNVERIFIABLE");
    }
    @Test void nullLastWorksInBothDirectionsAndDecimalSortHasNoStringOrdering() {
        assertThat(SupportAnalyticsPrivateQueryService.compare(null,BigDecimal.TEN,Direction.ASC)).isPositive();assertThat(SupportAnalyticsPrivateQueryService.compare(null,BigDecimal.TEN,Direction.DESC)).isPositive();
        assertThat(SupportAnalyticsPrivateQueryService.compare(new BigDecimal("2"),new BigDecimal("10"),Direction.ASC)).isNegative();
        assertThat(SupportAnalyticsPrivateQueryService.decimal(new BigDecimal("10.000000"))).isEqualTo("10");assertThat(SupportAnalyticsPrivateQueryService.decimal(null)).isNull();
        var rows=new ArrayList<>(List.of(new SupportAnalyticsPrivateQueryService.Row(10,"",BigDecimal.TEN,Map.of()),new SupportAnalyticsPrivateQueryService.Row(2,"",BigDecimal.TEN,Map.of()),new SupportAnalyticsPrivateQueryService.Row(1,"",null,Map.of())));
        rows.sort(SupportAnalyticsPrivateQueryService.rowOrder(Direction.DESC));assertThat(rows.stream().map(SupportAnalyticsPrivateQueryService.Row::id)).containsExactly(2L,10L,1L);
    }
    @Test void netSortOnlyAllowsCurrentHistoryCustomerRowsAndRejectsBeforeReadingSources() {
        var f=new Fixture();
        for(String view:List.of("AGENTS","FINANCE","DEVICES"))
            assertThatThrownBy(()->f.service.query(Map.of("view",List.of(view),"sortKey",List.of("NET_CONTRIBUTION"),"currency",List.of("USDT"))))
                .isInstanceOf(BizException.class).hasMessage("SUPPORT_ANALYTICS_SORT_INVALID");
        for(String basis:List.of("CURRENT_ASSET","PERIOD_EVENT")){
            var raw=new HashMap<String,List<String>>(Map.of("basis",List.of(basis),"sortKey",List.of("NET_CONTRIBUTION"),"currency",List.of("USDT")));
            if(basis.equals("PERIOD_EVENT")){raw.put("from",List.of("2026-10-01T00:00:00Z"));raw.put("to",List.of("2026-10-02T00:00:00Z"));}
            assertThatThrownBy(()->f.service.query(raw)).isInstanceOf(BizException.class).hasMessage("SUPPORT_ANALYTICS_SORT_INVALID");
        }
        verifyNoInteractions(f.ownership,f.stats,f.mapper);
        for(String view:List.of("CUSTOMERS","OVERVIEW","ACTIVITY")){
            var response=f.service.query(Map.of("view",List.of(view),"sortKey",List.of("NET_CONTRIBUTION"),"currency",List.of("USDT")));
            assertThat((List<?>)response.get("records")).hasSize(1);
        }
        verify(f.stats,times(3)).evaluateForQuery(any(),eq(f.scope),eq(false));verify(f.stats,never()).summarize(any());
    }
    @Test void netSortUsesSelectedCurrencyExactSignedDecimalsStableTiesAndUnknownLast() {
        var totals=new LinkedHashMap<Long,List<CustomerCurrencyTotals>>();
        totals.put(10L,netTotals("10.000001","-2",Status.AVAILABLE));
        totals.put(2L,netTotals("10.000001","10",Status.AVAILABLE));
        totals.put(3L,netTotals("-2","10",Status.AVAILABLE));
        totals.put(4L,netTotals("0","0",Status.AVAILABLE));
        totals.put(1L,netTotals("0","0",Status.UNKNOWN)); // A stale confirmed zero must remain unknown.
        totals.put(5L,netTotals(null,null,Status.AVAILABLE));totals.put(6L,List.of());
        assertThat(netOrder(totals,"USDT",Direction.DESC)).containsExactly(2L,10L,4L,3L,1L,5L,6L);
        assertThat(netOrder(totals,"USDT",Direction.ASC)).containsExactly(3L,4L,2L,10L,1L,5L,6L);
        assertThat(netOrder(totals,"UVT",Direction.DESC)).containsExactly(2L,3L,4L,10L,1L,5L,6L);
        assertThat(netOrder(totals,"UVT",Direction.ASC)).containsExactly(10L,4L,2L,3L,1L,5L,6L);
        for(Status status:List.of(Status.PARTIAL,Status.UNKNOWN,Status.FAILED,Status.UNAVAILABLE))
            assertThat(SupportAnalyticsPrivateQueryService.net(netTotals("0","0",status),"USDT")).isNull();
    }
    private static List<CustomerCurrencyTotals> netTotals(String usdt,String nex,Status status){
        return List.of(netTotal("USDT",usdt,status),netTotal("UVT",nex,status));
    }
    private static CustomerCurrencyTotals netTotal(String currency,String value,Status status){
        var amount=value==null?null:new BigDecimal(value);
        var money=new Money(amount,amount,null,null,null,status,status==Status.AVAILABLE?List.of():List.of("NET_SOURCE_UNVERIFIED"));
        return new CustomerCurrencyTotals(currency,money,money,money);
    }
    private static List<Long> netOrder(Map<Long,List<CustomerCurrencyTotals>> totals,String currency,Direction direction){
        var rows=new ArrayList<SupportAnalyticsPrivateQueryService.Row>();
        totals.forEach((id,values)->rows.add(new SupportAnalyticsPrivateQueryService.Row(id,"",SupportAnalyticsPrivateQueryService.net(values,currency),Map.of())));
        rows.sort(SupportAnalyticsPrivateQueryService.rowOrder(direction));return rows.stream().map(SupportAnalyticsPrivateQueryService.Row::id).toList();
    }
}
