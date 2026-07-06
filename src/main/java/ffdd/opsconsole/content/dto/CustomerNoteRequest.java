package ffdd.opsconsole.content.dto;

/**
 * 客户档案:新增内部备注。
 *
 * <p>对齐前端 mContentActions.addCustomerNote;持久化于 nx_customer_note(软删除留痕)。
 */
public record CustomerNoteRequest(
        String text,
        String reason,
        String operator) {
}
