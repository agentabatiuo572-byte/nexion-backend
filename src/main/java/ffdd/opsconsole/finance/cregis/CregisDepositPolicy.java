package ffdd.opsconsole.finance.cregis;

import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.util.Map;

public record CregisDepositPolicy(long version,
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) BigDecimal minDepositUsdt,
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) BigDecimal feeUsdt, int confirmations) {
    private static final BigDecimal MAX = new BigDecimal("999999999999.999999");
    public static CregisDepositPolicy parse(Map<String,Object> values) {
        if (values == null || !values.keySet().equals(java.util.Set.of("minDepositUsdt", "feeUsdt", "confirmations", "expectedVersion", "reason"))) throw invalid();
        try {
            BigDecimal min = decimal(values.get("minDepositUsdt"));
            BigDecimal fee = decimal(values.get("feeUsdt"));
            int confirmations = new BigDecimal(String.valueOf(values.get("confirmations"))).intValueExact();
            long version = new BigDecimal(String.valueOf(values.get("expectedVersion"))).longValueExact();
            if (!(values.get("reason") instanceof String)) throw invalid();
            String reason = ((String) values.get("reason")).trim();
            if (version < 0 || min.signum() <= 0 || fee.signum() < 0 || min.compareTo(fee) <= 0 || confirmations < 15
                    || confirmations > 10000 || reason.length() < 10 || reason.length() > 300) throw invalid();
            return new CregisDepositPolicy(version,min,fee,confirmations);
        } catch (ArithmeticException | NumberFormatException ex) { throw invalid(); }
    }
    private static BigDecimal decimal(Object raw) {
        if (raw == null) throw invalid();
        BigDecimal value = new BigDecimal(String.valueOf(raw));
        if (value.scale() > 6 || value.compareTo(MAX) > 0) throw invalid();
        return value;
    }
    private static BizException invalid() {return new BizException(422,"CREGIS_DEPOSIT_POLICY_INVALID");}
}
