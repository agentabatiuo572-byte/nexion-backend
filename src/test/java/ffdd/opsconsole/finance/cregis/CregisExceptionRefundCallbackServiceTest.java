package ffdd.opsconsole.finance.cregis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.finance.mapper.CregisExceptionRefundMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;

class CregisExceptionRefundCallbackServiceTest {
    @Test void signatureIpSnapshotAndDurableWriteAreRequiredBeforeAcknowledgement(){
        var db=mock(CregisExceptionRefundMapper.class);var config=new CregisProperties();config.setMode(CregisProperties.Mode.PROVIDER);config.setProjectId(42);config.setApiKey("fixture-only-not-a-secret");config.setCallbackSourceIps("127.0.0.1");
        var signer=new CregisSigner();var gate=mock(ffdd.opsconsole.finance.mapper.CregisDepositMapper.class);
        when(gate.lockProvisionGate()).thenReturn(Map.of("state","BLOCKED"));
        var refunds=mock(CregisExceptionRefundService.class);
        var service=new CregisExceptionRefundCallbackService(db,config,signer,gate,refunds);
        String address="0x"+"a".repeat(40),tx="0x"+"b".repeat(64);
        var row=CregisExceptionRefundService.map("caseNo","CR-RF-test","businessKey","CR-RF-test","returnAddress",address,"netAmount",BigDecimal.ONE,"providerCid",901L,"dispatchIntentAt",LocalDateTime.now());
        when(db.byKey(42,"CR-RF-test")).thenReturn(row);
        var payload=CregisExceptionRefundService.map("pid",42L,"cid",901L,"timestamp",Instant.now().toEpochMilli(),"nonce","abc123","chain_id","2510","token_id",CregisConstants.USDT_BEP20_TOKEN_ID,
                "currency","USDT-BEP20","third_party_id","CR-RF-test","amount","1.000000","address",address,"status",6L,"txid",tx);
        payload.put("sign",signer.sign(config.getApiKey(),payload));
        assertThat(service.receive(payload,"192.0.2.1")).isFalse();verify(db,never()).callback(any(),any(),anyLong(),anyInt(),any());
        payload.put("sign","0".repeat(32));assertThat(service.receive(payload,"127.0.0.1")).isFalse();
        payload.put("sign",signer.sign(config.getApiKey(),payload));assertThat(service.receive(payload,"127.0.0.1")).isTrue();
        verify(db).callback(any(),eq("CR-RF-test"),eq(901L),eq(6),eq(tx));
        verify(refunds).lateCallback("CR-RF-test");
        when(db.callback(any(),any(),anyLong(),anyInt(),any())).thenThrow(new IllegalStateException("durable storage unavailable"));
        assertThatThrownBy(()->service.receive(payload,"127.0.0.1")).hasMessage("durable storage unavailable");
        payload.put("amount","2");payload.put("sign",signer.sign(config.getApiKey(),payload));assertThat(service.receive(payload,"127.0.0.1")).isFalse();
    }
}
