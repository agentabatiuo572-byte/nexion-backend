package ffdd.opsconsole.content.dto;

/**
 * 客户档案:删除内部备注(noteId 在路径,此处只带 operator/reason)。
 *
 * <p>对齐前端 mContentActions.removeCustomerNote。
 */
public record CustomerNoteRemoveRequest(
        String reason,
        String operator) {
}
