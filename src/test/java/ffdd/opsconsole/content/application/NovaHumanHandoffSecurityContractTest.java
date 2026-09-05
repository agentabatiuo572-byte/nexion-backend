package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Red contract for Nova-to-human escalation.  It intentionally inspects the
 * public boundary and the application service rather than a model response:
 * model prose must never become an authorization decision or a transcript
 * supplied by the client.
 */
class NovaHumanHandoffSecurityContractTest {
    @Test
    void classifiesMandatoryHandoffsFromServerOwnedRulesAcrossSupportedLanguages() {
        assertThat(NovaHumanHandoffPolicy.reason("账号被盗，发现异常登录", "normal answer"))
                .isEqualTo("ACCOUNT_SECURITY");
        assertThat(NovaHumanHandoffPolicy.reason("My account was hacked and I see an unfamiliar login", "normal answer"))
                .isEqualTo("ACCOUNT_SECURITY");
        assertThat(NovaHumanHandoffPolicy.reason("Tài khoản của tôi bị hack, có đăng nhập lạ", "normal answer"))
                .isEqualTo("ACCOUNT_SECURITY");

        assertThat(NovaHumanHandoffPolicy.reason("提现超过48小时还没到账", "normal answer"))
                .isEqualTo("WITHDRAWAL_DELAY");
        assertThat(NovaHumanHandoffPolicy.reason("My withdrawal has not arrived after 48 hours", "normal answer"))
                .isEqualTo("WITHDRAWAL_DELAY");
        assertThat(NovaHumanHandoffPolicy.reason("Rút tiền quá 48 giờ vẫn chưa nhận được", "normal answer"))
                .isEqualTo("WITHDRAWAL_DELAY");

        assertThat(NovaHumanHandoffPolicy.reason("支付成功但订单没有生效", "normal answer"))
                .isEqualTo("PAYMENT_ORDER");
        assertThat(NovaHumanHandoffPolicy.reason("Payment succeeded but my order is not active", "normal answer"))
                .isEqualTo("PAYMENT_ORDER");
        assertThat(NovaHumanHandoffPolicy.reason("Thanh toán thành công nhưng đơn hàng chưa có hiệu lực", "normal answer"))
                .isEqualTo("PAYMENT_ORDER");

        assertThat(NovaHumanHandoffPolicy.reason("设备很久没有产出了，而且不是维护公告", "normal answer"))
                .isEqualTo("DEVICE_OUTPUT");
        assertThat(NovaHumanHandoffPolicy.reason("My device has had no output for a long time outside maintenance", "normal answer"))
                .isEqualTo("DEVICE_OUTPUT");
        assertThat(NovaHumanHandoffPolicy.reason("Thiết bị không có sản lượng lâu rồi và không bảo trì", "normal answer"))
                .isEqualTo("DEVICE_OUTPUT");

        assertThat(NovaHumanHandoffPolicy.reason("我要投诉，这完全不能接受", "normal answer"))
                .isEqualTo("COMPLAINT");
        assertThat(NovaHumanHandoffPolicy.reason("I want to make a complaint. This is unacceptable.", "normal answer"))
                .isEqualTo("COMPLAINT");
        assertThat(NovaHumanHandoffPolicy.reason("Tôi muốn khiếu nại, điều này không thể chấp nhận", "normal answer"))
                .isEqualTo("COMPLAINT");

        assertThat(NovaHumanHandoffPolicy.reason("我的余额和记录不一致", "normal answer"))
                .isEqualTo("ACCOUNT_DATA");
        assertThat(NovaHumanHandoffPolicy.reason("My balance does not match my account records", "normal answer"))
                .isEqualTo("ACCOUNT_DATA");
        assertThat(NovaHumanHandoffPolicy.reason("Số dư của tôi không khớp với lịch sử tài khoản", "normal answer"))
                .isEqualTo("ACCOUNT_DATA");
    }

    @Test
    void doesNotDelegateHandoffAuthorityToModelPhrasingAndFailsClosedForAConfirmedUnknownAnswer() {
        assertThat(NovaHumanHandoffPolicy.reason("How do I update my public profile?", "need_human=true"))
                .isBlank();
        assertThat(NovaHumanHandoffPolicy.reason("What is the policy for this unlisted product?",
                "NOVA_AI_UNANSWERABLE"))
                .isEqualTo("UNANSWERABLE");
        assertThat(NovaHumanHandoffPolicy.reason("我要人工客服", "normal answer"))
                .isEqualTo("USER_REQUEST");
    }

    @Test
    void redactsChineseEnglishAndVietnameseCredentialsBeforeTheyCanEnterHumanConversation() {
        String chinese = "账号 u-42，密码 Admin@123，验证码 654321，私钥 0xabc123，助记词 苹果 香蕉 西瓜";
        String english = "account u-42 password: SecretPass! OTP 654321 card 4111 1111 1111 1111 "
                + "seed phrase alpha beta gamma";
        String vietnamese = "tài khoản u-42 mật khẩu: MatKhau123 mã OTP 654321 "
                + "khóa riêng 0xabc123 cụm từ khôi phục mot hai ba";

        String redactedChinese = NovaHumanHandoffPolicy.redact(chinese);
        String redactedEnglish = NovaHumanHandoffPolicy.redact(english);
        String redactedVietnamese = NovaHumanHandoffPolicy.redact(vietnamese);

        assertThat(redactedChinese).doesNotContain("Admin@123", "654321", "0xabc123", "苹果 香蕉 西瓜")
                .contains("u-42");
        assertThat(redactedEnglish).doesNotContain("SecretPass!", "654321", "4111 1111 1111 1111", "alpha beta gamma")
                .contains("u-42");
        assertThat(redactedVietnamese).doesNotContain("MatKhau123", "654321", "0xabc123", "mot hai ba")
                .contains("u-42");
    }

    @Test
    void handoffIsServerClassifiedConfirmedAndCreatedThroughTheDurableSupportPath() throws Exception {
        String policy = source("ffdd/opsconsole/content/application/NovaHumanHandoffPolicy.java");
        String service = source("ffdd/opsconsole/content/application/NovaHumanHandoffService.java");
        String controller = source("ffdd/opsconsole/content/web/AppNovaAiController.java");
        String nova = source("ffdd/opsconsole/content/application/AppNovaAiService.java");
        String mapper = source("ffdd/opsconsole/content/mapper/AppNovaConversationMapper.java");

        // PRD 10.2: rules are server-owned. The RAG response's need_human flag
        // is informational only and cannot grant a handoff permission.
        assertThat(policy).contains("ACCOUNT_SECURITY", "WITHDRAWAL_DELAY", "PAYMENT_ORDER", "DEVICE_OUTPUT",
                "COMPLAINT", "ACCOUNT_DATA", "UNANSWERABLE");
        assertThat(policy).contains("static String reason").doesNotContain("need_human", "needHuman");
        assertThat(nova).contains("NovaHumanHandoffPolicy").doesNotContain("root.path(\"need_human\")");

        // The browser supplies neither a transcript nor an arbitrary recipient.
        assertThat(controller).contains("@PostMapping(\"/handoffs\")", "Idempotency-Key", "handoffService.confirm");
        assertThat(service).contains("NovaHandoffConfirmRequest", "conversationId", "turnId")
                .doesNotContain("request.context", "request.transcript", "request.openingText", "request.userId");

        // Both the trigger and every copied turn are selected with the caller's
        // user id. The resulting SUPPORT conversation remains the existing
        // durable/PC-readable route and carries the ordinary idempotency key.
        assertThat(service).contains("mapper.turn(userId, request.turnId()")
                .contains("mapper.turns(userId, conversationId, request.turnId()")
                .contains("AppSupportService.StartConversationRequest(\"SUPPORT\"")
                .contains("supportService.startConversation(userId, idempotencyKey")
                .contains("redact", "2000");
        assertThat(mapper).contains("WHERE user_id=#{userId} AND turn_id=#{turnId}")
                .contains("WHERE user_id=#{userId} AND conversation_id=#{conversationId}");
    }

    private String source(String relative) throws Exception {
        return Files.readString(Path.of("src/main/java").resolve(relative));
    }
}
