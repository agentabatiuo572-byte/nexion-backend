package ffdd.opsconsole.content.domain;

import java.util.List;

/**
 * 即时会话客户档案(只读跨域快照 + 可编辑标注)。
 *
 * <p>由 {@code OpsConversationService.detail} 按 {@code conversation.userId} 聚合
 * user / finance / device / risk / content 工单域的真实数据,供坐席接待时一眼看清客户价值与风险。
 * 客户侧真实账户操作仍回 C/D 域;此处仅做只读辅助呈现,任一子域查不到时以合理兜底值填充,
 * 绝不阻断会话详情返回。</p>
 *
 * <p>字段与前端 {@code CustomerProfile} 类型对齐(见 m-tabs/data.ts):
 * uid / nickname / phone / vlevel / kyc / systemTags / customTags / risk / riskNote /
 * recharge / withdraw / balance / tickets / device / hashrate / idle /
 * region / joined / lastActive / ledger / notes。</p>
 *
 * <p>标签拆分:{@code systemTags} 为派生只读(会话类型 / V等级 / KYC / 风控 / 账户状态,每次刷新重算);
 * {@code customTags} 为坐席手动添加的持久化标签(存于 nx_customer_tag,按 user_id 聚合,跨会话共享)。</p>
 */
public record ConversationCustomerProfile(
        String uid,
        String nickname,
        String phone,
        String vlevel,
        String kyc,
        List<String> systemTags,
        List<String> customTags,
        String risk,
        String riskNote,
        String recharge,
        String withdraw,
        String balance,
        Integer tickets,
        String device,
        String hashrate,
        String idle,
        String region,
        String joined,
        String lastActive,
        List<LedgerEntry> ledger,
        List<CustomerNote> notes) {

    // record 紧凑构造器:列表字段兜底为空列表,避免前端空指针
    public ConversationCustomerProfile {
        systemTags = systemTags == null ? List.of() : systemTags;
        customTags = customTags == null ? List.of() : customTags;
        ledger = ledger == null ? List.of() : ledger;
        notes = notes == null ? List.of() : notes;
    }

    /** 资金/充提流水条目(对齐前端 CustomerLedgerEntry)。 */
    public record LedgerEntry(
            String label,
            String when,
            String amount,
            Boolean up,
            Boolean pending) {
    }

    /** 客户档案备注(对齐前端 CustomerNote;持久化于 nx_customer_note,按 user_id 聚合,软删除留痕)。 */
    public record CustomerNote(
            String id,
            Long ts,
            String author,
            String text) {
    }
}
