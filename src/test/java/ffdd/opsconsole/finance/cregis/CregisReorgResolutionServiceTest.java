package ffdd.opsconsole.finance.cregis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.facade.*;
import ffdd.opsconsole.finance.mapper.*;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static ffdd.opsconsole.finance.cregis.CregisReorgResolutionService.*;

class CregisReorgResolutionServiceTest {
    final CregisReorgResolutionMapper db=mock(CregisReorgResolutionMapper.class);
    final CregisProperties config=new CregisProperties();
    final BscDepositProof chain=mock(BscDepositProof.class);
    final CregisExceptionSourceService sources=mock(CregisExceptionSourceService.class);
    final CregisGatewayRouter router=mock(CregisGatewayRouter.class);
    final CregisGateway provider=mock(CregisGateway.class);
    final SupportFundJournalFacade funds=mock(SupportFundJournalFacade.class);
    final SupportFundJournalMapper journals=mock(SupportFundJournalMapper.class);
    final TreasuryLedgerRepository treasury=mock(TreasuryLedgerRepository.class);
    final AdminOperatorRoleResolver admins=mock(AdminOperatorRoleResolver.class);
    final AdminIdempotencyService idem=mock(AdminIdempotencyService.class);
    final AuditLogService audit=mock(AuditLogService.class);
    final AuditObjectLockMapper locks=mock(AuditObjectLockMapper.class);
    final FinanceWithdrawalControlFacade withdrawals=mock(FinanceWithdrawalControlFacade.class);
    final PlatformTransactionManager tm=mock(PlatformTransactionManager.class);
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final CregisReorgResolutionService service=new CregisReorgResolutionService(db,config,chain,sources,router,funds,journals,treasury,audit,admins,idem,locks,withdrawals,tm,json);
    final Map<String,Object> row=new HashMap<>(),wallet=new HashMap<>(),proposal=new HashMap<>(),account=new HashMap<>();
    final List<Map<String,Object>> operations=new ArrayList<>();
    final String tx="0x"+"a".repeat(64),address="0x"+"b".repeat(40),reason="Chain and provider evidence checked independently";
    @BeforeEach void setup() {
        config.setMode(CregisProperties.Mode.PROVIDER);config.setProjectId(42);auth(8,"ADMIN");
        row.putAll(map("id",1L,"userStatus","FROZEN","freezeSource","CREGIS_DEPOSIT_INCIDENT","eventId",2L,"projectId",42L,"cid",3L,"userId",7L,"netAmount",n("100"),"grossAmount",n("100"),"heldAmount",n("40"),"recoveredAmount",n("0"),"outstandingDebt",n("0"),"status","INVESTIGATING","version",0L,"eventStatus","REORG_INVESTIGATING","txid",tx,"transferFrom","0x"+"c".repeat(40),"originalLogIndex",0,"logIndex",0,"address",address,"blockNumber",100L,"blockHash","old","ledgerId",10L));
        wallet.putAll(map("id",1L,"userId",7L,"usdtAvailable",n("0"),"nexAvailable",n("0"),"pendingWithdraw",n("0"),"cregisRiskHeld",n("40"),"cumulativeDeposit",n("100"),"version",1L,"incarnation","wallet-7","revision",1L,"sandbox",0,"isDeleted",0,"createdAt",LocalDateTime.of(2026,10,22,0,0)));
        account.putAll(map("id",7L,"status","FROZEN","freezeSource","CREGIS_DEPOSIT_INCIDENT","freezeRef","CR-3"));
        when(admins.isActiveAdministrator(anyLong())).thenReturn(true);
        when(idem.executeRetained(any(),any(),any(),eq(Result.class),any())).thenAnswer(c->c.<Supplier<Result>>getArgument(4).get());
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(db.incident(42,1)).thenAnswer(c->new HashMap<>(row));when(db.lockIncident(42,1)).thenAnswer(c->new HashMap<>(row));
        when(db.wallet(7)).thenAnswer(c->new HashMap<>(wallet));when(db.lockUser(7)).thenAnswer(c->new HashMap<>(account));when(db.lockGate()).thenReturn("BLOCKED");
        when(db.walletChange(eq(7L),anyLong(),any(),any(),any())).thenAnswer(c->{wallet.put("usdtAvailable",amount(wallet,"usdtAvailable").add(c.getArgument(2)));wallet.put("cregisRiskHeld",amount(wallet,"cregisRiskHeld").add(c.getArgument(3)));wallet.put("cumulativeDeposit",amount(wallet,"cumulativeDeposit").add(c.getArgument(4)));wallet.put("version",((Number)wallet.get("version")).longValue()+1);wallet.put("revision",((Number)wallet.get("revision")).longValue()+1);return 1;});
        when(db.update(eq(1L),anyLong(),any(),any(),any(),any())).thenAnswer(c->{row.put("version",((Number)row.get("version")).longValue()+1);row.put("status",c.getArgument(2));row.put("heldAmount",c.getArgument(3));row.put("recoveredAmount",c.getArgument(4));row.put("outstandingDebt",c.getArgument(5));return 1;});
        when(db.eventStatus(anyLong(),any(),any(),anyLong(),any(),anyInt(),anyInt())).thenAnswer(c->{row.put("eventStatus",c.getArgument(2));row.put("logIndex",c.getArgument(6));return 1;});when(db.depositStatus(any(),anyLong(),anyLong(),any())).thenReturn(1);
        when(db.operations(1)).thenReturn(operations);when(db.operation(any())).thenAnswer(c->{var op=new HashMap<String,Object>(c.getArgument(0));op.put("eventsJson",op.get("events"));operations.add(op);return 1;});
        when(journals.journal(any(),anyLong(),anyLong())).thenReturn(List.of(map("revision",2L)));
        when(db.proposal(5)).thenAnswer(c->new HashMap<>(proposal));when(db.lockProposal(5)).thenAnswer(c->new HashMap<>(proposal));when(db.decide(eq(5L),eq(0L),eq(8L),any())).thenReturn(1);
        when(router.provider()).thenReturn(provider);when(provider.depositsByTxid(CregisNetwork.BEP20,tx)).thenReturn(List.of(new CregisGateway.DepositTrade(3,CregisConstants.BSC_CHAIN_ID,CregisConstants.USDT_BEP20_TOKEN_ID,address,n("100"),tx,1)));
        absent();
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @ParameterizedTest @CsvSource({"100,0,100,0","40,20,60,40","0,0,0,100"})
    void reversalUsesOnlyOwnHeldThenAvailableAndPersistsDebt(String held,String available,String recovered,String debt){
        row.put("heldAmount",n(held));wallet.put("cregisRiskHeld",n(held));wallet.put("usdtAvailable",n(available));approve(Action.REVERSE);
        assertThat(amount(row,"recoveredAmount")).isEqualByComparingTo(recovered);assertThat(amount(row,"outstandingDebt")).isEqualByComparingTo(debt);
        assertThat(amount(wallet,"cregisRiskHeld")).isZero();assertThat(amount(wallet,"usdtAvailable")).isZero();assertThat(amount(wallet,"cumulativeDeposit")).isZero();
        verify(treasury).reverseTopupReserve("CR-3:REORG:1",n("100"),"CR-REORG-1:1");verify(funds).bind(any(),any());
        assertThat(operations.get(0).get("eventsJson").toString()).contains("REVOKE_DEPOSIT");
        assertThatThrownBy(()->service.evidence(1,Action.REVERSE)).hasMessage("CREGIS_REORG_ACTION_NOT_ALLOWED");
    }
    @Test void halfDebtCollectionThenCanonicalRestoreReturnsOnlyActuallyRecoveredSixty(){
        approve(Action.REVERSE);assertThat(amount(row,"outstandingDebt")).isEqualByComparingTo("60");
        wallet.put("usdtAvailable",n("20"));approve(Action.COLLECT_DEBT);assertThat(amount(row,"outstandingDebt")).isEqualByComparingTo("40");
        canonical();approve(Action.RESTORE);
        assertThat(amount(wallet,"usdtAvailable")).isEqualByComparingTo("60");assertThat(amount(wallet,"cumulativeDeposit")).isEqualByComparingTo("100");assertThat(amount(row,"outstandingDebt")).isZero();
        assertThat(row).containsEntry("status","RESTORED");verify(treasury,times(1)).recordTopupReserve("CR-3:REORG-RESTORE:1",n("100"),"CR-REORG-1:3");
        assertThat(operations.get(2).get("eventsJson").toString()).contains("RESTORE_DEPOSIT","REORG_REFUND").doesNotContain("\"action\":\"DEPOSIT\"");
    }
    @Test void remintedSameTransactionUsesFreshLogAndPreservesOriginalHistory() {
        approve(Action.REVERSE);
        when(chain.inspectCanonical(org.mockito.ArgumentMatchers.eq(CregisNetwork.BEP20),any(),anyInt(),any(),any(),anyLong(),any(),any())).thenReturn(new BscDepositProof.ReorgProof(false,new BscDepositProof.TransferProof(9,110,"new",15,"0x"+"c".repeat(40),address,n("100"),true),124,"new"));
        approve(Action.RESTORE);
        assertThat(row).containsEntry("logIndex",9).containsEntry("originalLogIndex",0);
        verify(sources).relocateCredited(argThat(old->old.logIndex()==0 && old.cid()==3),argThat(fresh->fresh.logIndex()==9 && fresh.cid()==3),eq("CR-REORG-1:2"));
        assertThat(operations.get(1).get("proof").toString()).contains("originalPosition","originalLogIndex","originalSender");
        verify(db).depositStatus("CR-3",7,10,"CREDITED");
    }
    @Test void responseIdentifiersAreStringsWithoutLosingBigintPrecision() {
        when(db.incidents(42,0,21)).thenReturn(List.of(map("id",9007199254740993L,"eventId",9007199254740994L,"cid",9007199254740995L,"userId",9007199254740996L,"status","INVESTIGATING","outstandingDebt",n("0"),"heldAmount",n("1"),"version",2L)));
        var page=service.incidents(0,20);var record=(Map<?,?>)((List<?>)page.get("records")).get(0);
        assertThat(record.get("id")).isEqualTo("9007199254740993");assertThat(record.get("eventId")).isEqualTo("9007199254740994");assertThat(record.get("cid")).isEqualTo("9007199254740995");assertThat(record.get("userId")).isEqualTo("9007199254740996");assertThat(record.get("version")).isEqualTo(2L);
        assertThat(service.evidence(1,Action.REVERSE).get("incidentId")).isEqualTo("1");assertThat(new Result(9007199254740993L,"MAKER_DONE",0).id()).isEqualTo("9007199254740993");
    }
    @Test void falseAlarmOnlyReturnsOwnHoldWithoutNewCreditOrReserve(){
        wallet.put("cregisRiskHeld",n("70"));canonical();
        when(provider.depositsByTxid(CregisNetwork.BEP20,tx)).thenReturn(List.of(new CregisGateway.DepositTrade(3,CregisConstants.BSC_CHAIN_ID,CregisConstants.USDT_BEP20_TOKEN_ID.toUpperCase(Locale.ROOT),address,n("100"),tx,1)));
        approve(Action.RELEASE_HOLD);
        assertThat(amount(wallet,"usdtAvailable")).isEqualByComparingTo("40");assertThat(amount(wallet,"cregisRiskHeld")).isEqualByComparingTo("30");assertThat(amount(wallet,"cumulativeDeposit")).isEqualByComparingTo("100");
        verify(treasury,never()).recordTopupReserve(any(),any(),any());assertThat(row).containsEntry("status","RELEASED");
    }
    @Test void rpcFailureCannotBecomeAbsenceOrWriteMoney(){when(chain.inspectCanonical(org.mockito.ArgumentMatchers.eq(CregisNetwork.BEP20),any(),anyInt(),any(),any(),anyLong(),any(),any())).thenThrow(new IllegalStateException("rpc"));assertThatThrownBy(()->service.evidence(1,Action.REVERSE)).hasMessage("CREGIS_REORG_PROOF_UNAVAILABLE");verify(db,never()).walletChange(anyLong(),anyLong(),any(),any(),any());}
    @Test void differentCheckerActiveMakerAndAdminSubjectAreMandatory(){
        var evidence=service.evidence(1,Action.REVERSE);prepare(Action.REVERSE,evidence);auth(7,"ADMIN");assertThatThrownBy(()->service.decide(5,"same",new Decision("APPROVE",0,reason))).hasMessage("CREGIS_REORG_DIFFERENT_CHECKER_REQUIRED");
        auth(8,"APP");assertThatThrownBy(()->service.evidence(1,Action.REVERSE)).hasMessage("ADMIN_FINANCE_AUTH_REQUIRED");auth(8,"ADMIN");when(admins.isActiveAdministrator(7)).thenReturn(false);assertThatThrownBy(()->service.decide(5,"inactive",new Decision("APPROVE",0,reason))).hasMessage("CREGIS_REORG_MAKER_INACTIVE");
    }
    @Test void inactiveMakerCanBeRejectedByAnotherActiveCheckerAndDoesNotStrandNewProposal() {
        var evidence=service.evidence(1,Action.REVERSE);prepare(Action.REVERSE,evidence);
        when(admins.isActiveAdministrator(7)).thenReturn(false);
        when(db.decide(5,0,8,"REJECTED")).thenAnswer(call->{proposal.put("status","REJECTED");return 1;});
        var rejected=service.decide(5,"reject-inactive-maker",new Decision("REJECT",0,reason));
        assertThat(rejected.status()).isEqualTo("REJECTED");
        when(db.propose(any())).thenAnswer(call->{
            assertThat(proposal.get("status")).isEqualTo("REJECTED");
            call.<Map<String,Object>>getArgument(0).put("id",6L);return 1;
        });
        var replacement=service.propose(1,"replace-rejected-proposal",new Proposal(Action.REVERSE,0,reason,evidence.get("evidenceHash").toString()));
        assertThat(replacement.id()).isEqualTo("6");assertThat(replacement.status()).isEqualTo("MAKER_DONE");
        verify(db,never()).walletChange(anyLong(),anyLong(),any(),any(),any());verifyNoInteractions(treasury);
    }
    @Test void actionHashCannotApproveAnotherActionAndA2LockPreventsMutation(){
        var evidence=service.evidence(1,Action.REVERSE);prepare(Action.REVERSE,evidence);when(locks.countActiveByTarget("C","user","7")).thenReturn(1);assertThatThrownBy(()->service.decide(5,"locked",new Decision("APPROVE",0,reason))).hasMessage("CREGIS_REORG_OBJECT_LOCKED_BY_A2");verify(db,never()).walletChange(anyLong(),anyLong(),any(),any(),any());
    }
    @Test void unfreezeRejectsOtherFreezeAndOutstandingIncident(){
        row.put("status","RELEASED");row.put("heldAmount",n("0"));canonical();when(db.openForUser(7)).thenReturn(1);assertThatThrownBy(()->approve(Action.UNFREEZE)).hasMessage("CREGIS_REORG_OPEN_INCIDENT_OR_DEBT");when(db.openForUser(7)).thenReturn(0);account.put("freezeSource","K1_MULTI_ACCOUNT");assertThatThrownBy(()->approve(Action.UNFREEZE)).hasMessage("CREGIS_REORG_OTHER_FREEZE_SOURCE");verify(db,never()).unfreeze(anyLong(),any());
    }
    @Test void evidenceHashDoesNotExpireOnUnrelatedNewHeadButDoesBindAction(){
        canonical();String release=service.evidence(1,Action.RELEASE_HOLD).get("evidenceHash").toString();when(chain.inspectCanonical(org.mockito.ArgumentMatchers.eq(CregisNetwork.BEP20),any(),anyInt(),any(),any(),anyLong(),any(),any())).thenReturn(new BscDepositProof.ReorgProof(false,new BscDepositProof.TransferProof(0,110,"new",22,"0x"+"c".repeat(40),address,n("100"),true),131,"new"));assertThat(service.evidence(1,Action.RELEASE_HOLD)).containsEntry("evidenceHash",release);
    }
    void approve(Action action){var evidence=service.evidence(1,action);prepare(action,evidence);service.decide(5,"key-"+action+row.get("version"),new Decision("APPROVE",0,reason));}
    void prepare(Action action,Map<String,Object> evidence){proposal.clear();proposal.putAll(map("id",5L,"incidentId",1L,"action",action.name(),"expectedVersion",row.get("version"),"evidenceHash",evidence.get("evidenceHash"),"makerId",7L,"status","MAKER_DONE","version",0L));}
    void absent(){when(chain.inspectCanonical(org.mockito.ArgumentMatchers.eq(CregisNetwork.BEP20),any(),anyInt(),any(),any(),anyLong(),any(),any())).thenReturn(new BscDepositProof.ReorgProof(true,null,130,"new"));}
    void canonical(){when(chain.inspectCanonical(org.mockito.ArgumentMatchers.eq(CregisNetwork.BEP20),any(),anyInt(),any(),any(),anyLong(),any(),any())).thenReturn(new BscDepositProof.ReorgProof(false,new BscDepositProof.TransferProof(0,110,"new",15,"0x"+"c".repeat(40),address,n("100"),true),124,"new"));}
    static void auth(long id,String subject){var auth=new UsernamePasswordAuthenticationToken(String.valueOf(id),"",List.of(new SimpleGrantedAuthority("finance_d1_read"),new SimpleGrantedAuthority("finance_d1_channel_manage")));auth.setDetails(Map.of("subjectType",subject));SecurityContextHolder.getContext().setAuthentication(auth);}
    static BigDecimal n(String value){return new BigDecimal(value);}static BigDecimal amount(Map<String,Object> row,String key){return new BigDecimal(String.valueOf(row.get(key)));}
    static Map<String,Object> map(Object... pairs){var result=new HashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
}
