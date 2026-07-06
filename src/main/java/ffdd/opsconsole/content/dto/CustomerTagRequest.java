package ffdd.opsconsole.content.dto;

/**
 * 客户档案:新增 / 移除自定义标签。
 *
 * <p>标签内容放请求体(避开中文标签的 URL 编码问题,删除亦走 body 而非路径)。
 * 对齐前端 mContentActions.addCustomerTag / removeCustomerTag。
 */
public record CustomerTagRequest(
        String tag,
        String reason,
        String operator) {
}
