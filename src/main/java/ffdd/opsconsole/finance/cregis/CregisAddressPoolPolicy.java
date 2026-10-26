package ffdd.opsconsole.finance.cregis;

import ffdd.opsconsole.shared.exception.BizException;
import java.util.Map;

public record CregisAddressPoolPolicy(boolean enabled, int target, int lowWatermark,
        int batchSize, int concurrency, long version) {
    public static CregisAddressPoolPolicy from(Map<String,Object> row) {
        if (row == null || row.isEmpty()) throw new IllegalStateException("CREGIS_POOL_POLICY_MISSING");
        var policy = new CregisAddressPoolPolicy(((Number)row.get("enabled")).intValue()==1,
                number(row,"target"),number(row,"lowWatermark"),number(row,"batchSize"),
                number(row,"concurrency"),((Number)row.get("version")).longValue());
        policy.validate();return policy;
    }
    public void validate() {
        if(target<10 || target>100000 || lowWatermark<1 || lowWatermark>=target
                || batchSize<1 || batchSize>100 || concurrency<1 || concurrency>10 || concurrency>batchSize
                || version<0) throw new BizException(400,"CREGIS_POOL_POLICY_INVALID");
    }
    private static int number(Map<String,Object> row,String key) {return ((Number)row.get(key)).intValue();}
}
