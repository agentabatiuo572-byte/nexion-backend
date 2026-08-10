package ffdd.opsconsole.team.dto;

public record TeamCommissionConfigUpdateRequest(
        String key,
        String value,
        String reason,
        String operator,
        Long expectedVersion) {
    public TeamCommissionConfigUpdateRequest(String key,String value,String reason,String operator){this(key,value,reason,operator,null);}
}
