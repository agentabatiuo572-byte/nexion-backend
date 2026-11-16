package ffdd.opsconsole.team.dto;

public record TeamCommissionConfigUpdateRequest(
        String key,
        String value,
        String reason,
        String operator,
        Long expectedVersion,
        Long expectedMaterialRevision,
        String expectedRevision) {
    public TeamCommissionConfigUpdateRequest(String key,String value,String reason,String operator){this(key,value,reason,operator,null,null,null);}
    public TeamCommissionConfigUpdateRequest(String key,String value,String reason,String operator,Long expectedVersion){this(key,value,reason,operator,expectedVersion,null,null);}
    public TeamCommissionConfigUpdateRequest(String key,String value,String reason,String operator,Long expectedVersion,Long expectedMaterialRevision){this(key,value,reason,operator,expectedVersion,expectedMaterialRevision,null);}
}
