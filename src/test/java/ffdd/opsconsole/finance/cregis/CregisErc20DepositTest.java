package ffdd.opsconsole.finance.cregis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.facade.SupportPaymentAttributionFacade;
import ffdd.opsconsole.finance.facade.FinanceWithdrawalControlFacade;
import ffdd.opsconsole.finance.mapper.CregisDepositMapper;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class CregisErc20DepositTest {
    private static final CregisNetwork ETH=CregisNetwork.ERC20;
    private static final String ADDRESS="0x"+"b".repeat(40), TX="0x"+"a".repeat(64), BLOCK="0x"+"c".repeat(64);

    @Test void configuredEthereumPoolStaysUnavailableUntilIndependentDepositApproval() {
        var f=new Fixture(false);
        f.config.setBscRpcUrl("https://bsc.example.test");
        when(f.db.provisionGate()).thenReturn(Map.of("state","IDLE","assignEnabled",1,"creditEnabled",1));
        when(f.db.poolPolicy(any(CregisNetwork.class))).thenReturn(Map.of("enabled",1,"target",1000,
            "lowWatermark",300,"batchSize",50,"concurrency",2,"version",0L));
        var closed=(Map<?,?>)f.service.exceptions().get("addressPools");
        assertThat((Map<String,Object>)closed.get("ERC20")).containsEntry("configured",true)
            .containsEntry("available",false).containsEntry("automatic",false);
        assertThat((Map<String,Object>)closed.get("BEP20")).containsEntry("configured",true).containsEntry("available",true);
        assertThatThrownBy(()->f.service.provisionPoolAddress(ETH)).hasMessage("CREGIS_PROVISION_NOT_CONFIGURED");
        f.config.setErc20DepositEnabled(true);
        var opened=(Map<?,?>)f.service.exceptions().get("addressPools");
        assertThat((Map<String,Object>)opened.get("ERC20")).containsEntry("configured",true)
            .containsEntry("available",true).containsEntry("automatic",true);
        f.service.closePoolWorkers();
    }

    @Test void legacyEnabledFlagsDoNotIssueEthereumAddressOrCreditWithoutIndependentApproval() throws Exception {
        var f=new Fixture(false);
        assertThat(f.service.address(ETH,42)).containsEntry("network","ERC20").containsEntry("enabled",false).doesNotContainKey("address");
        verifyNoInteractions(f.db);
        f.deliver(false);
        verify(f.db).insertEvent(eq(ETH),eq(42L),eq(88L),eq(77L),eq(TX),eq(2),eq(ADDRESS),argThat(a->a.compareTo(BigDecimal.TEN)==0),
            eq(BigDecimal.ZERO),eq(BigDecimal.ZERO),eq(101L),eq(BLOCK),eq(15),eq("REVIEW_HOLD"));
        verify(f.db,never()).creditWallet(any(),anyLong(),anyLong());
        f.service.closePoolWorkers();
    }

    @Test void ethereumAddressLookupNeverReadsBscOwnerEvenForSameHexAddress() {
        var f=new Fixture(true);
        when(f.db.addressForUser(42,"60")).thenReturn(List.of(Map.of("address",ADDRESS,"state","READY","projectId",88L)));
        when(f.db.provisionGate()).thenReturn(Map.of("state","IDLE","assignEnabled",1,"creditEnabled",1));
        when(f.gateway.projectCoins()).thenReturn(List.of(new CregisGateway.Coin(ETH.currency(),"USDT-ERC20","60",ETH.tokenId(),true,true)));
        when(f.proof.head(ETH)).thenReturn(new BscDepositProof.Head(120,BLOCK));
        assertThat(f.service.address(ETH,42)).containsEntry("network","ERC20").containsEntry("address",ADDRESS);
        verify(f.db,never()).addressForUser(42,"2510");
        verify(f.proof,never()).head(CregisNetwork.BEP20);
        f.service.closePoolWorkers();
    }

    @Test void signedEthereumDuplicateCallbacksProduceOneWalletCreditAndEthereumDepositRecord() throws Exception {
        var f=new Fixture(true);f.deliver(true);
        verify(f.db,times(1)).creditWallet(new BigDecimal("9"),42,7);
        verify(f.db,times(1)).insertDepositOrder(ETH,42,"CR-77",TX,2,new BigDecimal("9"),15,11);
        verify(f.treasury,times(1)).recordTopupReserve("CR-77",new BigDecimal("9"),"CREGIS:77");
        verify(f.gateway,times(2)).depositsByTxid(ETH,TX);
        verify(f.db,never()).addressOwner(88,"2510",ADDRESS);
        f.service.closePoolWorkers();
    }

    @Test void matchingAddressAndAmountOnWrongProviderChainCannotCredit() throws Exception {
        var f=new Fixture(true);
        when(f.gateway.depositsByTxid(ETH,TX)).thenReturn(List.of(new CregisGateway.DepositTrade(77,"2510",CregisNetwork.BEP20.tokenId(),ADDRESS,BigDecimal.TEN,TX,1)));
        f.deliver(false);
        verify(f.db).finishDelivery(1,"PROVIDER_MISMATCH_HOLD");
        verify(f.proof,never()).verify(eq(ETH),anyString(),anyString(),any(),anyLong());
        verify(f.db,never()).creditWallet(any(),anyLong(),anyLong());
        f.service.closePoolWorkers();
    }

    private static final class Fixture {
        final CregisProperties config=new CregisProperties();
        final CregisDepositMapper db=mock(CregisDepositMapper.class);
        final CregisGateway gateway=mock(CregisGateway.class);
        final BscDepositProof proof=mock(BscDepositProof.class);
        final TreasuryLedgerRepository treasury=mock(TreasuryLedgerRepository.class);
        final CregisDepositService service;
        final CregisSigner signer=new CregisSigner();
        final ObjectMapper json=new ObjectMapper();
        final AtomicReference<Map<String,Object>> event=new AtomicReference<>();
        Fixture(boolean approved) {
            config.setMode(CregisProperties.Mode.PROVIDER);config.setProjectId(88);config.setApiKey("erc20-fixture-key");
            config.setDepositEnabled(true);config.setDepositCreditEnabled(true);config.setErc20DepositEnabled(approved);
            config.setEthereumRpcUrl("https://ethereum.example.test");config.setCallbackSourceIps("127.0.0.1");
            var router=mock(CregisGatewayRouter.class);when(router.provider()).thenReturn(gateway);
            var tx=mock(PlatformTransactionManager.class);when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            when(db.lockProvisionGate()).thenReturn(Map.of("state","IDLE","assignEnabled",1,"creditEnabled",1));
            when(db.unresolvedExposure(88)).thenReturn(BigDecimal.ZERO);
            when(db.addressOwner(88,"60",ADDRESS)).thenReturn(List.of(Map.of("userId",42L,"allocationBlock",100L,"allocationHash",BLOCK)));
            when(db.lockEvent(ETH,88,77)).thenAnswer(i->event.get()==null?List.of():List.of(event.get()));
            when(gateway.depositsByTxid(ETH,TX)).thenReturn(List.of(new CregisGateway.DepositTrade(77,"60",ETH.tokenId(),ADDRESS,BigDecimal.TEN,TX,1)));
            when(proof.blockHash(ETH,100)).thenReturn(BLOCK);
            when(proof.verify(eq(ETH),eq(TX),eq(ADDRESS),any(),eq(101L))).thenReturn(Optional.of(new BscDepositProof.Proof(2,101,BLOCK,15)));
            when(db.lockActiveUser(42)).thenReturn(42L);
            when(db.lockWallet(42)).thenReturn(Map.of("usdtAvailable",BigDecimal.ZERO,"version",7L));
            when(db.creditWallet(any(),eq(42L),eq(7L))).thenReturn(1);when(db.ledgerId("CR-77")).thenReturn(11L);
            when(db.eventId(ETH,88,77)).thenReturn(12L);when(db.linkLedger(11,12)).thenReturn(1);
            when(db.insertDepositOrder(eq(ETH),eq(42L),eq("CR-77"),eq(TX),eq(2),any(),eq(15),eq(11L))).thenReturn(1);
            when(db.insertEvent(eq(ETH),eq(42L),eq(88L),eq(77L),eq(TX),eq(2),eq(ADDRESS),any(),any(),any(),eq(101L),eq(BLOCK),eq(15),anyString()))
                .thenAnswer(i->{event.set(Map.of("id",12L,"chainId","60","userId",42L,"txid",TX,"address",ADDRESS,"logIndex",2,"grossAmount",BigDecimal.TEN,"status",i.getArgument(13)));return 1;});
            var attribution=mock(SupportPaymentAttributionFacade.class);
            when(attribution.insertLedger(any(),any(),any(),anyString())).thenReturn(1);
            service=new CregisDepositService(config,router,proof,signer,json,db,tx,treasury,mock(FinanceWithdrawalControlFacade.class),attribution,
                mock(CregisPayoutControl.class),mock(CregisExceptionSourceService.class),mock(CregisReorgResolutionService.class),DepositPolicyFixture.policy());
        }
        void deliver(boolean twice) throws Exception {
            Map<String,Object> callback=new LinkedHashMap<>();
            callback.put("pid",88L);callback.put("cid",77L);callback.put("chain_id","60");callback.put("token_id",ETH.tokenId());
            callback.put("currency",ETH.currency());callback.put("address",ADDRESS);callback.put("txid",TX);callback.put("amount","10");
            callback.put("status",1);callback.put("block_height",101L);callback.put("timestamp",Instant.now().toEpochMilli());callback.put("nonce","abc123");
            callback.put("sign",signer.sign(config.getApiKey(),callback));String raw=json.writeValueAsString(callback);
            assertThat(service.receive(raw)).isEqualTo("success");
            var deliveries=new ArrayList<Map<String,Object>>();deliveries.add(Map.of("id",1L,"rawJson",raw));
            if(twice) {assertThat(service.receive(raw)).isEqualTo("success");deliveries.add(Map.of("id",2L,"rawJson",raw));}
            when(db.pendingDeliveries()).thenReturn(deliveries);service.reconcile();
        }
    }
}
