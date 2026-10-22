package ffdd.opsconsole.finance.cregis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.mapper.*;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;

class CregisAutoForwardServiceTest {
    final CregisProperties config=new CregisProperties();
    final CregisAutoForwardMapper db=mock(CregisAutoForwardMapper.class);
    final CregisFundsPreparationMapper addresses=mock(CregisFundsPreparationMapper.class);
    final CregisDepositMapper deposits=mock(CregisDepositMapper.class);
    final CregisPayoutControl payout=mock(CregisPayoutControl.class);
    final CregisGatewayRouter router=mock(CregisGatewayRouter.class);
    final CregisGateway vendor=mock(CregisGateway.class);
    final BscDepositProof chain=mock(BscDepositProof.class);
    final CregisExceptionAdminGuard admins=mock(CregisExceptionAdminGuard.class);
    final ffdd.opsconsole.shared.security.AdminPermissionCache permissions=mock(ffdd.opsconsole.shared.security.AdminPermissionCache.class);
    final AdminIdempotencyService idempotency=mock(AdminIdempotencyService.class);
    final AuditLogService audit=mock(AuditLogService.class);
    final CregisSigner signer=new CregisSigner();
    int commits;
    final PlatformTransactionManager tm=new PlatformTransactionManager(){
        public TransactionStatus getTransaction(TransactionDefinition d){return new SimpleTransactionStatus();}
        public void commit(TransactionStatus s){commits++;if(afterCommit!=null)afterCommit.run();}
        public void rollback(TransactionStatus s){}
    };
    Runnable afterCommit;
    final CregisAutoForwardService service=new CregisAutoForwardService(config,db,addresses,deposits,payout,router,chain,admins,permissions,idempotency,audit,new ObjectMapper(),signer,tm);
    final String source="0x"+"a".repeat(40),depositTx="0x"+"b".repeat(64),collectTx="0x"+"c".repeat(64),paymentTx="0x"+"d".repeat(64);
    final String reason="仅测试自动流程关闭和原单恢复，不发真实资金";
    final Map<String,Object> policy=new HashMap<>(),event=new HashMap<>(),task=new HashMap<>();
    @BeforeEach void setup(){
        config.setMode(CregisProperties.Mode.PROVIDER);config.setProjectId(42);config.setApiKey("offline-test-key");config.setCallbackSourceIps("127.0.0.1");
        policy.put("collectionEnabled",false);policy.put("paymentEnabled",false);policy.put("startEventId",null);policy.put("version",0L);policy.put("makerId",null);
        event.putAll(Map.of("id",100L,"address",source,"status","CREDITED","grossAmount",new BigDecimal("5"),"netAmount",new BigDecimal("4"),"feeAmount",BigDecimal.ONE,"txid",depositTx,"logIndex",2,"blockNumber",90L));
        task.putAll(Map.of("id","1","eventId","100","sourceAddress",source,"platformAddress",CregisFundsPreparationService.PLATFORM,"counterpartyAddress",CregisFundsPreparationService.COUNTERPARTY,"grossAmount","5.000000","netAmount","4.000000","feeAmount","1.000000","paymentKey","CA-P-42-100"));
        task.put("state","WAIT_COLLECTION");task.put("version",0L);
        when(db.policy(42)).thenAnswer(c->new HashMap<>(policy));when(db.lockPolicy(42)).thenAnswer(c->new HashMap<>(policy));
        when(db.lock(42,1)).thenAnswer(c->new HashMap<>(task));when(db.lockEvent(42,100)).thenAnswer(c->new HashMap<>(event));
        when(db.work(eq(42L),anyLong())).thenAnswer(c->((Long)c.getArgument(1))<1&&!Set.of("COMPLETED","COLLECTION_FAILED","PAYMENT_FAILED").contains(task.get("state"))?List.of(new HashMap<>(task)):List.of());
        when(db.save(eq(42L),anyMap(),anyString(),nullable(String.class))).thenAnswer(c->{Map<String,Object> row=c.getArgument(1);
            if(!Objects.equals(row.get("version"),task.get("version")))return 0;
            task.putAll(row);task.put("state",c.getArgument(2));task.put("lastError",c.getArgument(3));task.put("version",((Number)task.get("version")).longValue()+1);return 1;});
        when(deposits.lockProvisionGate()).thenReturn(Map.of("state","IDLE"));when(payout.enabled()).thenReturn(true);when(payout.requiredConfirmations()).thenReturn(15);
        when(addresses.config(42)).thenReturn(Map.of("platformAddress",CregisFundsPreparationService.PLATFORM,"counterpartyAddress",CregisFundsPreparationService.COUNTERPARTY,"network","BSC","asset","USDT"));
        when(router.provider()).thenReturn(vendor);when(router.payoutCallbackUrl()).thenReturn("https://example.invalid/cregis/payout");
        when(vendor.addressBelongs(CregisConstants.BSC_CHAIN_ID,source)).thenReturn(true);
        when(chain.preparationBalance(anyString())).thenReturn(new BscDepositProof.PreparationBalance("100","1",100,"0x"+"e".repeat(64)));
        when(chain.verifyExact(eq(depositTx),eq(2),eq(source),any(),eq(90L))).thenReturn(new BscDepositProof.TransferProof(2,90,"0x"+"e".repeat(64),15,source,source,new BigDecimal("5"),true));
        when(chain.verifyPayout(eq(collectTx),eq(CregisFundsPreparationService.PLATFORM),any())).thenReturn(Optional.of(new BscDepositProof.Proof(1,110,"0x"+"e".repeat(64),15,source)));
        when(chain.verifyPayout(eq(paymentTx),eq(CregisFundsPreparationService.COUNTERPARTY),any())).thenReturn(Optional.of(new BscDepositProof.Proof(1,120,"0x"+"e".repeat(64),15,CregisFundsPreparationService.PLATFORM)));
        when(vendor.createCollection(any())).thenAnswer(c->{assertThat(task.get("state")).isEqualTo("COLLECTION_SUBMITTING");assertThat(commits).isPositive();return 123L;});
        when(vendor.queryCollection(eq(123L),any())).thenReturn(Optional.of(new CregisGateway.CollectionOrder(123,1,collectTx)));
        when(vendor.createAutoForwardPayout(any())).thenReturn(new CregisGateway.PayoutSubmission(456,"CA-P-42-100"));
        when(vendor.queryPayout(any())).thenReturn(new CregisGateway.PayoutOrder(456,CregisConstants.USDT_BEP20_CURRENCY,"2510",CregisConstants.USDT_BEP20_TOKEN_ID,CregisFundsPreparationService.COUNTERPARTY,new BigDecimal("4"),"CA-P-42-100",CregisGateway.PayoutStatus.SUCCEEDED,paymentTx));
        when(admins.require(true)).thenReturn(7L);
        when(permissions.getPermissionCodes(anyLong())).thenReturn(Set.of("finance_d1_read","finance_d1_channel_manage","finance_d2_withdrawal_approve"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("7","unused",List.of(new SimpleGrantedAuthority("finance_d2_withdrawal_approve"))));
        when(idempotency.executeRetained(anyString(),anyString(),anyString(),eq(Map.class),any())).thenAnswer(c->((Supplier<?>)c.getArgument(4)).get());
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    void enable(){config.setAutoForwardEnabled(true);policy.put("collectionEnabled",true);policy.put("paymentEnabled",true);policy.put("startEventId","0");}
    void ticks(int n){for(int i=0;i<n;i++)service.tick();}
    @Test void allDefaultsClosedAndNeitherScanningNorSendingCanBeTriggered(){
        assertThat(service.status()).containsEntry("runtimeApproved",false).containsEntry("collectionEnabled",false).containsEntry("paymentEnabled",false).containsEntry("executionEnabled",false);
        ticks(3);verify(db,never()).candidates(anyLong(),anyLong());verifyNoInteractions(vendor,chain);
        policy.put("collectionEnabled",true);policy.put("paymentEnabled",true);ticks(2);verifyNoInteractions(vendor,chain);
        config.setAutoForwardEnabled(true);policy.put("collectionEnabled",false);policy.put("paymentEnabled",false);ticks(2);verifyNoInteractions(vendor,chain);
    }
    @Test void fullAutomaticPathCollectsGrossThenPaysNetExactlyOnce(){enable();ticks(8);
        assertThat(task.get("state")).isEqualTo("COMPLETED");
        var collected=org.mockito.ArgumentCaptor.forClass(CregisGateway.CollectionRequest.class);verify(vendor).createCollection(collected.capture());
        assertThat(collected.getValue().amount()).isEqualByComparingTo("5");assertThat(collected.getValue().fromAddress()).isEqualTo(source);assertThat(collected.getValue().toAddress()).isEqualTo(CregisFundsPreparationService.PLATFORM);
        var paid=org.mockito.ArgumentCaptor.forClass(CregisGateway.PayoutRequest.class);verify(vendor).createAutoForwardPayout(paid.capture());
        assertThat(paid.getValue().amount()).isEqualByComparingTo("4");assertThat(paid.getValue().address()).isEqualTo(CregisFundsPreparationService.COUNTERPARTY);
        assertThat(mockingDetails(deposits).getInvocations()).allMatch(i->i.getMethod().getName().equals("lockProvisionGate"));
        verify(vendor,never()).createPayout(any());verify(db,times(2)).resolveUnknown(42,1);
    }
    @Test void providerSuccessWithoutFifteenChainConfirmationsCannotStartPayment(){enable();
        when(chain.verifyPayout(eq(collectTx),anyString(),any())).thenReturn(Optional.of(new BscDepositProof.Proof(1,110,"0x"+"e".repeat(64),14,source)));
        ticks(4);assertThat(task.get("state")).isEqualTo("COLLECTION_PENDING");verify(vendor,never()).createAutoForwardPayout(any());}
    @Test void timeoutAndRestartIntentNeverResendAndRaiseExistingRiskHold(){enable();
        doThrow(new CregisGatewayException(CregisGatewayException.Kind.SUBMISSION_UNKNOWN,"CREGIS_SUBMISSION_UNKNOWN")).when(vendor).createCollection(any());
        ticks(5);assertThat(task.get("state")).isEqualTo("COLLECTION_UNKNOWN");verify(vendor,times(1)).createCollection(any());verify(db).holdUnknown(eq(42L),eq(1L),anyString());
        assertThatThrownBy(()->service.recover("x",1,((Number)task.get("version")).longValue(),"COLLECTION",null,true,reason)).hasMessage("CREGIS_AUTO_STATE_CONFLICT");
        reset(vendor);task.put("state","COLLECTION_SUBMITTING");task.put("intentAt",LocalDateTime.now().minusMinutes(3));ticks(3);verifyNoInteractions(vendor);
    }
    @Test void pauseStopsNewPaymentButAlreadySentCollectionStillGetsObserved(){enable();service.tick();config.setAutoForwardEnabled(false);policy.put("collectionEnabled",false);policy.put("paymentEnabled",false);
        ticks(3);assertThat(task.get("state")).isEqualTo("WAIT_PAYMENT");verify(vendor,atLeastOnce()).queryCollection(eq(123L),any());verify(vendor,never()).createAutoForwardPayout(any());}
    @Test void reorgWrongSourceAndMissingGasCannotSend(){enable();event.put("status","REORG_INVESTIGATING");ticks(2);verify(vendor,never()).createCollection(any());
        event.put("status","CREDITED");when(chain.preparationBalance(anyString())).thenReturn(new BscDepositProof.PreparationBalance("100","0",100,"hash"));ticks(2);
        assertThat(task.get("lastError")).isEqualTo("CREGIS_AUTO_BNB_REQUIRED");verify(vendor,never()).createCollection(any());
        when(chain.preparationBalance(anyString())).thenReturn(new BscDepositProof.PreparationBalance("100","1",100,"hash"));service.tick();
        when(chain.verifyPayout(eq(collectTx),anyString(),any())).thenReturn(Optional.of(new BscDepositProof.Proof(1,110,"hash",15,CregisFundsPreparationService.COUNTERPARTY)));
        ticks(3);assertThat(task.get("state")).isEqualTo("COLLECTION_PENDING");verify(vendor,never()).createAutoForwardPayout(any());}
    @Test void explicitCreateRejectionCanBeRetriedButUnknownCannot(){enable();
        doThrow(new CregisGatewayException(CregisGatewayException.Kind.REJECTED,"CREGIS_PROVIDER_REJECTED")).when(vendor).createCollection(any());service.tick();
        assertThat(task.get("state")).isEqualTo("COLLECTION_FAILED");config.setAutoForwardEnabled(false);
        service.recover("retry",1,((Number)task.get("version")).longValue(),"COLLECTION",null,true,reason);
        assertThat(task.get("state")).isEqualTo("WAIT_COLLECTION");ticks(2);verify(vendor,times(1)).createCollection(any());}
    @Test void originalCidRecoveryIsReadOnlyAndMustMatchVersionAndProviderIdentity(){enable();task.put("state","COLLECTION_UNKNOWN");task.put("intentAt",LocalDateTime.now());task.put("collectionFloor",100L);
        when(vendor.queryCollection(eq(999L),any())).thenThrow(new CregisGatewayException(CregisGatewayException.Kind.INVALID_RESPONSE,"CREGIS_RESPONSE_INVALID"));
        assertThatThrownBy(()->service.recover("wrong",1,0,"COLLECTION","999",false,reason)).hasMessage("CREGIS_RESPONSE_INVALID");
        service.recover("right",1,0,"COLLECTION","123",false,reason);assertThat(task).containsEntry("state","COLLECTION_PENDING");verify(vendor,never()).createCollection(any());
        assertThatThrownBy(()->service.recover("stale",1,0,"COLLECTION","123",false,reason)).hasMessage("CREGIS_AUTO_STATE_CONFLICT");}
    @Test void policyNeedsRuntimeApprovalDifferentActiveAdminAndVersion(){
        assertThatThrownBy(()->service.policy("off", "PROPOSE",0,true,true,reason)).hasMessage("CREGIS_AUTO_ACTIVATION_NOT_APPROVED");
        policy.put("makerId","7");policy.put("pendingCollection",true);policy.put("pendingPayment",true);
        assertThatThrownBy(()->service.policy("self","APPROVE",0,null,null,reason)).hasMessage("CREGIS_AUTO_DIFFERENT_CHECKER_REQUIRED");
        policy.put("makerId","8");enable();when(db.apply(42,0,1,1,reason)).thenReturn(1);service.policy("other","APPROVE",0,null,null,reason);verify(admins).requireActive(8);
        assertThatThrownBy(()->service.policy("stale","APPROVE",9,null,null,reason)).hasMessage("CREGIS_AUTO_STATE_CONFLICT");}
    @Test void makerLosingFinancialPermissionsInvalidatesPendingProposal(){enable();policy.put("makerId","8");policy.put("pendingCollection",true);policy.put("pendingPayment",true);
        when(permissions.getPermissionCodes(8L)).thenReturn(Set.of("finance_d1_read"));
        assertThatThrownBy(()->service.policy("lost","APPROVE",0,null,null,reason)).hasMessage("ADMIN_FINANCE_AUTH_REQUIRED");verify(db,never()).apply(anyLong(),anyLong(),anyInt(),anyInt(),anyString());}
    @Test void collectionReorgAfterIntentCommitCannotDispatchSupplierPayment(){enable();task.put("state","WAIT_PAYMENT");task.put("collectionCid",123L);task.put("collectionTxid",collectTx);task.put("collectionFloor",100L);
        afterCommit=()->{if(task.get("state").equals("PAYMENT_SUBMITTING"))doThrow(new IllegalStateException("SIMULATED_REORG_AFTER_INTENT")).when(chain).verifyPayout(eq(collectTx),anyString(),any());};
        service.tick();assertThat(task.get("state")).isEqualTo("WAIT_PAYMENT");verify(vendor,never()).createAutoForwardPayout(any());}
    @Test void confirmedUnsignedProviderRejectionResolvesOnlyItsUnknownHoldWithoutResending(){
        task.put("state","PAYMENT_UNKNOWN");task.put("paymentCid",456L);task.put("intentAt",LocalDateTime.now());
        when(vendor.queryPayout(any())).thenReturn(new CregisGateway.PayoutOrder(456,CregisConstants.USDT_BEP20_CURRENCY,"2510",CregisConstants.USDT_BEP20_TOKEN_ID,CregisFundsPreparationService.COUNTERPARTY,new BigDecimal("4"),"CA-P-42-100",CregisGateway.PayoutStatus.fromProviderCode(2),null));
        service.tick();assertThat(task.get("state")).isEqualTo("PAYMENT_FAILED");verify(db).resolveUnknown(42,1);
        verify(vendor,never()).createAutoForwardPayout(any());verify(vendor,never()).createCollection(any());
    }
    @Test void signedCallbackOnlyBindsQueryIdentityAndNeverMarksPaidOrSends(){
        task.put("state","PAYMENT_UNKNOWN");task.put("intentAt",LocalDateTime.now());
        Map<String,Object> body=new HashMap<>(Map.of("pid",42L,"cid",456L,"third_party_id","CA-P-42-100","address",CregisFundsPreparationService.COUNTERPARTY,"amount","4.000000","chain_id","2510","token_id",CregisConstants.USDT_BEP20_TOKEN_ID,"currency",CregisConstants.USDT_BEP20_CURRENCY,"nonce","abc123","timestamp",Instant.now().toEpochMilli()));
        body.put("status",6);body.put("txid",paymentTx);body.put("sign",signer.sign(config.getApiKey(),body));when(db.byKey(42,"CA-P-42-100")).thenAnswer(c->new HashMap<>(task));
        assertThat(service.callback(body,"192.0.2.1")).isFalse();assertThat(service.callback(body,"127.0.0.1")).isTrue();
        assertThat(task.get("state")).isEqualTo("PAYMENT_PENDING");verifyNoInteractions(vendor,chain);
        body.put("amount","400");assertThat(service.callback(body,"127.0.0.1")).isFalse();}
}
