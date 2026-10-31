package ffdd.opsconsole.finance.purchase;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.cregis.*;
import ffdd.opsconsole.finance.mapper.PurchasePaymentMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class PurchaseErc20ContractTest {
    final PurchasePaymentMapper db=mock(PurchasePaymentMapper.class);
    final PurchasePaymentPolicyService policy=mock(PurchasePaymentPolicyService.class);
    final CregisProperties config=new CregisProperties();
    final CregisGatewayRouter router=mock(CregisGatewayRouter.class);
    final CregisGateway gateway=mock(CregisGateway.class);
    final PurchasePaymentService service=new PurchasePaymentService(db,policy,null,null,null,null,null,config,router,
            mock(BscDepositProof.class),null,mock(PlatformTransactionManager.class),new ObjectMapper(),null,null, org.mockito.Mockito.mock(ffdd.opsconsole.device.application.TestCloudActivationAdmission.class));

    void ready() {
        config.setMode(CregisProperties.Mode.PROVIDER);config.setProjectId(99);
        config.setDepositEnabled(true);config.setDepositCreditEnabled(true);config.setErc20DepositEnabled(true);config.setEthereumRpcUrl("https://ethereum.invalid");
        when(router.provider()).thenReturn(gateway);
        when(gateway.projectCoins()).thenReturn(List.of(new CregisGateway.Coin(CregisNetwork.ERC20.currency(),"USDT-ERC20","60",CregisNetwork.ERC20.tokenId(),false,true)));
        when(db.one(contains("nx_cregis_provision_gate"),any(Object[].class))).thenReturn(values("state","IDLE","credit_enabled",1,"assign_enabled",1));
        when(db.one(contains("FROM nx_user"),any(Object[].class))).thenReturn(values("id",7,"status","ACTIVE","sandbox",0));
        when(db.one(contains("FROM nx_order WHERE"),any(Object[].class))).thenReturn(values("created_at",java.time.Instant.now(),"order_no","ORDER-1","amount_usdt","19.995","subtotal_usdt","25","discount_usdt","5.005","payment_status","PENDING","order_status","PENDING_PAYMENT"));
        when(db.one(contains("FROM nx_promotion_order_receipt"),any(Object[].class))).thenReturn(null);
        when(policy.current()).thenReturn(values("version",3,"channels",List.of(values("paymentMethod","CREGIS_USDT_ERC20","configured",true,
                "channelFeeRatePct","2.50","channelFeeFixed","0.10","networkConfirmFeeUsdt","0.35"))));
    }
    Map<String,Object> quote(){return service.quote(7,values("kind","ORDER_PAY","paymentMethod","CREGIS_USDT_ERC20","payload",values("orderNo","ORDER-1")));}

    @Test void erc20QuotePersistsItsOwnFeesNetworkAndTotal() {
        ready();
        var quote=quote();
        assertThat(quote).containsEntry("paymentMethod","CREGIS_USDT_ERC20").containsEntry("network","ERC20")
                .containsEntry("subtotalUsdt","20.00").containsEntry("channelFeeUsdt","0.60")
                .containsEntry("networkConfirmFeeUsdt","0.35").containsEntry("totalUsdt","20.95").containsEntry("feePolicyVersion",3);
        verify(db).write(contains("INSERT INTO nx_purchase_payment_quote"),any(Object[].class));
        verify(gateway,never()).createPayout(any());
    }
    @Test void missingEthereumRpcOrAddressPermissionNeverCreatesAQuote() {
        ready();config.setEthereumRpcUrl("");
        assertThatThrownBy(this::quote).hasMessage("CREGIS_DIRECT_PAYMENT_UNAVAILABLE");
        config.setEthereumRpcUrl("https://ethereum.invalid");
        when(gateway.projectCoins()).thenReturn(List.of(new CregisGateway.Coin(CregisNetwork.ERC20.currency(),"USDT-ERC20","60",CregisNetwork.ERC20.tokenId(),true,false)));
        assertThatThrownBy(this::quote).hasMessage("CREGIS_DIRECT_PAYMENT_ASSET_UNAVAILABLE");
        verify(db,never()).write(anyString(),any(Object[].class));
    }
    @Test void closedEthereumPayinNeverAllocatesAnExistingPoolAddress() {
        ready();config.setErc20DepositEnabled(false);
        when(db.one(contains("FROM nx_cregis_deposit_address"),any(Object[].class))).thenReturn(values("id",2,"address","0x"+"a".repeat(40)));
        assertThatThrownBy(this::quote).hasMessage("CREGIS_DIRECT_PAYMENT_UNAVAILABLE");
        verify(db,never()).one(contains("FROM nx_cregis_deposit_address"),any(Object[].class));
        verify(db,never()).write(anyString(),any(Object[].class));
    }
    @Test void legacyPolicyGetsAnUnconfiguredErc20ChannelWithoutCopyingBscFees() {
        var deposit=mock(CregisDepositPolicyService.class);
        when(deposit.current()).thenReturn(new CregisDepositPolicy(1,BigDecimal.TEN,BigDecimal.ONE,15));
        when(db.one("SELECT * FROM nx_purchase_payment_policy WHERE id=1")).thenReturn(values("version",1,"policy_json",json(List.of(
                values("paymentMethod","CREGIS_USDT_BSC","configured",true,"channelFeeRatePct","2","channelFeeFixed","1","networkConfirmFeeUsdt","0.35","minTopupPrincipal","10")))));
        var current=new PurchasePaymentPolicyService(db,deposit,null,null,new ObjectMapper()).current();
        assertThat(maps(current.get("channels"))).filteredOn(row->"CREGIS_USDT_ERC20".equals(row.get("paymentMethod"))).singleElement()
                .satisfies(row->assertThat(row).containsEntry("configured",false).containsEntry("channelFeeRatePct",null)
                        .containsEntry("networkConfirmFeeUsdt",null).containsEntry("minTopupPrincipal","10"));
        verify(db,never()).write(anyString(),any(Object[].class));
    }
}
