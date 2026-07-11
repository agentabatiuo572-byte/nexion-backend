package ffdd.opsconsole.content.dto;

/** 新建文案位置槽位请求(position_key 全局唯一,绑定投放位置 surface)。 */
public record CopyPositionCreateRequest(
        String positionKey,
        String name,
        String surface,
        Integer sortOrder,
        String operator,
        String reason) {

    public CopyPositionCreateRequest(String positionKey, String name, String surface, String operator, String reason) {
        this(positionKey, name, surface, 0, operator, reason);
    }
}
