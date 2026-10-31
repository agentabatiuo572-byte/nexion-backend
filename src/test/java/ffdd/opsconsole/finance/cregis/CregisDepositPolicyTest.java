package ffdd.opsconsole.finance.cregis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CregisDepositPolicyTest {
    private Map<String,Object> input(String min,String fee,int confirmations) {
        return Map.of("minDepositUsdt",min,"feeUsdt",fee,"confirmations",confirmations,"expectedVersion",0,"reason","Approved policy change");
    }
    @Test void supportsZeroFeeOneMinimumAndSerializesMoneyWithoutPrecisionLoss() throws Exception {
        var policy=CregisDepositPolicy.parse(input("1.000000","0.000000",15));
        assertThat(policy.minDepositUsdt().toPlainString()).isEqualTo("1.000000");
        assertThat(new ObjectMapper().valueToTree(policy).get("feeUsdt").isTextual()).isTrue();
    }
    @Test void rejectsUnsafeThresholdsAndPrecision() {
        for (var input : java.util.List.of(input("1","1",15),input("1","-1",15),input("1","0",14),input("1.5","0",15),input("1.0000001","0",15),input("1","0",10001)))
            assertThatThrownBy(()->CregisDepositPolicy.parse(input)).hasMessage("CREGIS_DEPOSIT_POLICY_INVALID");
    }
}
