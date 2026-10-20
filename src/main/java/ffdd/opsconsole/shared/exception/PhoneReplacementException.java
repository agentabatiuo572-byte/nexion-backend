package ffdd.opsconsole.shared.exception;

import java.util.List;
import java.util.Map;

/** One user-readable conflict contract for phone policy and replacement commands. */
public class PhoneReplacementException extends BizException {
    public record Details(String conflictType,String reason,List<String> allowedActions,Map<String,String> fieldErrors) { }
    private final Details details;
    public PhoneReplacementException(int code,String type) { this(code,type,Map.of()); }
    public PhoneReplacementException(int code,String type,Map<String,String> fields) {
        super(code,type);
        String reason=switch(type) {
            case "POLICY_VERSION_CHANGED" -> "换机策略已更新，请刷新并重新确认。";
            case "BINDING_VERSION_CHANGED" -> "账户绑定已变化，请刷新当前绑定。";
            case "REQUEST_UNAVAILABLE" -> "申请已过期、关闭或处理，请查看当前状态。";
            case "GRANT_STATE_CHANGED" -> "特批状态已变化，请核对当前结果。";
            case "ACTIVE_GRANT_EXISTS" -> "账户已有有效特批，请先核对现有授权。";
            case "IDEMPOTENCY_MISMATCH" -> "此操作标识已用于另一份内容，请查询原操作结果。";
            case "INVALID_POLICY" -> "手机策略参数不合法，请修正标出的字段。";
            case "PERMISSION_DENIED", "ADMIN_AUTH_REQUIRED" -> "当前账号无权执行此操作。";
            case "PHONE_REPLACEMENT_EVIDENCE_REQUIRED" -> "请先核实本账户关联的客服记录。";
            case "PHONE_REPLACEMENT_CONFIRMATION_REQUIRED" -> "请核对操作内容并明确确认。";
            default -> "请求未完成，请刷新并核实当前状态。";
        };
        List<String> actions=switch(type) {
            case "IDEMPOTENCY_MISMATCH" -> List.of("QUERY_ORIGINAL","CANCEL");
            case "REQUEST_UNAVAILABLE", "GRANT_STATE_CHANGED" -> List.of("REFRESH","CANCEL","NEW_REQUEST");
            case "PERMISSION_DENIED", "ADMIN_AUTH_REQUIRED" -> List.of("CANCEL");
            default -> List.of("REFRESH","CANCEL");
        };
        details=new Details(type,reason,actions,Map.copyOf(fields));
    }
    public Details details(){return details;}
}
