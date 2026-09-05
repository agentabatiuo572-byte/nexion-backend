package ffdd.opsconsole.content.application;

import java.util.Locale;
import java.util.regex.Pattern;

/** Deterministic rules classify user concerns; generated prose grants no authority. */
public final class NovaHumanHandoffPolicy {
    private NovaHumanHandoffPolicy() {}

    public static String reason(String message, String serverOutcome) {
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (has(text, "被盗", "异常登录", "hacked", "unfamiliar login", "bị hack", "đăng nhập lạ")) return "ACCOUNT_SECURITY";
        if (has(text, "提现", "withdrawal", "rút tiền") && has(text, "48", "不到账", "未到账", "not arrived", "chưa nhận")) return "WITHDRAWAL_DELAY";
        if (has(text, "支付", "付款", "payment", "paid", "thanh toán") && has(text, "订单", "order", "đơn hàng")) return "PAYMENT_ORDER";
        if (has(text, "设备", "device", "thiết bị") && has(text, "没有产出", "不产出", "no output", "không có sản lượng")) return "DEVICE_OUTPUT";
        if (has(text, "投诉", "不能接受", "complaint", "unacceptable", "khiếu nại", "không thể chấp nhận")) return "COMPLAINT";
        if (has(text, "余额", "记录", "balance", "records", "số dư", "lịch sử") && has(text, "不一致", "不对", "not match", "incorrect", "không khớp")) return "ACCOUNT_DATA";
        if (has(text, "人工", "human agent", "human support", "real person", "nhân viên")) return "USER_REQUEST";
        return "NOVA_AI_UNANSWERABLE".equals(serverOutcome) ? "UNANSWERABLE" : "";
    }

    private static boolean has(String text, String... keywords) {
        for (String keyword : keywords) if (text.contains(keyword)) return true;
        return false;
    }

    public static String redact(String text) {
        if (text == null) return "";
        // Discard the remainder after a credential label, including multi-word seeds.
        String safe = Pattern.compile("(?isu)(密码|验证码|私钥|助记词|password|passphrase|otp|private[ -]?key|seed[ -]?phrase|mnemonic|mật khẩu|mã xác minh|khóa riêng|cụm từ khôi phục).*$")
                .matcher(text).replaceAll("[REDACTED]");
        safe = safe.replaceAll("(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}", "[REDACTED]")
                .replaceAll("(?<![a-zA-Z0-9])(?:\\+?\\d[ -]?){6,}(?![a-zA-Z0-9])", "[REDACTED]")
                .replaceAll("(?i)(?:0x)?[a-f0-9]{32,}", "[REDACTED]");
        return safe.length() > 320 ? safe.substring(0, 320) : safe;
    }
}
