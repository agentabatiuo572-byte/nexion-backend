package ffdd.opsconsole.content.businessnotification;

import ffdd.opsconsole.content.businessnotification.mapper.BusinessNotificationMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Projects committed facts only. Notification retries never touch financial or device state. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name="nexion.business-notifications.enabled",havingValue="true")
public class BusinessNotificationService {
    private final BusinessNotificationMapper mapper;

    @Scheduled(fixedDelayString="${nexion.business-notifications.delay-ms:30000}")
    @Transactional(rollbackFor=Exception.class)
    public void scan() {
        if (mapper.lockControl()==null) throw new IllegalStateException("BUSINESS_NOTIFICATION_MIGRATION_REQUIRED");
        mapper.seedDevices();
        mapper.resetRecoveredDevices();
        mapper.startAbnormalDevices();
        mapper.facts().forEach(this::emit);
        mapper.trialDeadlines().forEach(this::emit);
        for (var fact : mapper.deviceAlerts()) {
            emit(fact);
            mapper.markDeviceAlert(fact.factKey());
        }
    }

    void emit(BusinessNotificationFact fact) {
        if (fact.userId()==null || fact.userId()<=0) throw new IllegalArgumentException("NOTIFICATION_RECIPIENT_INVALID");
        Text text = text(fact.messageKey());
        String existing = "deposit".equals(fact.messageKey()) ? mapper.existingTopup(fact.factKey(),fact.userId()) : null;
        if("payment_method".equals(fact.messageKey())) existing=mapper.existingPaymentMethod(fact.factKey(),fact.userId());
        String bizNo = existing==null ? bizNo(fact.factKey()) : existing;
        if (mapper.receipt(fact.factKey(),bizNo,fact.userId())==0) return;
        mapper.notify(fact,bizNo,text.zh(),text.en(),text.vi());
    }

    static String bizNo(String key) {
        try { return "NATIVE1:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(key.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    record Text(String zh,String en,String vi) {}
    static Text text(String key) {
        Text text=TEXT.get(key);
        if(text==null) throw new IllegalArgumentException("UNKNOWN_BUSINESS_NOTIFICATION_MESSAGE");
        return text;
    }
    private static final Map<String,Text> TEXT=Map.ofEntries(
        Map.entry("deposit",new Text("充值已到账，请查看钱包记录。","Your deposit has been credited. View your wallet history.","Tiền nạp đã được ghi có. Vui lòng xem lịch sử ví.")),
        Map.entry("withdrawal_completed",new Text("提现已完成，请查看钱包记录。","Your withdrawal is complete. View your wallet history.","Rút tiền đã hoàn tất. Vui lòng xem lịch sử ví.")),
        Map.entry("withdrawal_returned",new Text("提现未完成，资金已退回，请查看钱包记录。","Your withdrawal was not completed and funds were returned. View your wallet history.","Rút tiền chưa hoàn tất và tiền đã được hoàn lại. Vui lòng xem lịch sử ví.")),
        Map.entry("refund",new Text("订单退款已完成，请查看钱包记录。","Your order refund is complete. View your wallet history.","Hoàn tiền đơn hàng đã hoàn tất. Vui lòng xem lịch sử ví.")),
        Map.entry("password",new Text("您的登录密码已更新。如非本人操作，请及时检查账号安全。","Your sign-in password was updated. Review account security if this was not you.","Mật khẩu đăng nhập đã được cập nhật. Kiểm tra bảo mật nếu không phải bạn thực hiện.")),
        Map.entry("payment_method",new Text("您的收付款信息已变更。如非本人操作，请及时检查账号安全。","Your payment details changed. Review account security if this was not you.","Thông tin thanh toán đã thay đổi. Kiểm tra bảo mật nếu không phải bạn thực hiện.")),
        Map.entry("security",new Text("您的账号安全状态已变更，请查看详情。","Your account security status changed. View details.","Trạng thái bảo mật tài khoản đã thay đổi. Vui lòng xem chi tiết.")),
        Map.entry("new_signin",new Text("您的账号出现新的登录环境，请检查账号安全。","A new sign-in environment was detected. Review account security.","Phát hiện môi trường đăng nhập mới. Vui lòng kiểm tra bảo mật tài khoản.")),
        Map.entry("support",new Text("客服有新回复，请打开会话查看。","Support sent a new reply. Open your conversation to view it.","Bộ phận hỗ trợ có phản hồi mới. Mở cuộc trò chuyện để xem.")),
        Map.entry("device_alert",new Text("您的设备持续异常，请查看设备状态。","Your device has a persistent issue. Review its status.","Thiết bị đang gặp sự cố kéo dài. Vui lòng kiểm tra trạng thái.")),
        Map.entry("trial_24h",new Text("设备试用将在24小时内结束，请查看试用详情。","Your device trial ends within 24 hours. View trial details.","Dùng thử thiết bị sẽ kết thúc trong 24 giờ. Vui lòng xem chi tiết.")),
        Map.entry("trial_1h",new Text("设备试用将在1小时内结束，请查看试用详情。","Your device trial ends within 1 hour. View trial details.","Dùng thử thiết bị sẽ kết thúc trong 1 giờ. Vui lòng xem chi tiết.")),
        Map.entry("trial_end",new Text("设备试用期已结束，请查看抵扣保留期详情。","Your device trial has ended. View the deduction retention details.","Thời gian dùng thử thiết bị đã kết thúc. Xem thời hạn giữ khấu trừ.")),
        Map.entry("retention_24h",new Text("试用抵扣保留期将在24小时内结束，请查看详情。","Your trial deduction retention ends within 24 hours. View details.","Thời hạn giữ khấu trừ dùng thử sẽ kết thúc trong 24 giờ. Xem chi tiết.")),
        Map.entry("retention_1h",new Text("试用抵扣保留期将在1小时内结束，请查看详情。","Your trial deduction retention ends within 1 hour. View details.","Thời hạn giữ khấu trừ dùng thử sẽ kết thúc trong 1 giờ. Xem chi tiết.")),
        Map.entry("retention_end",new Text("试用抵扣保留期已结束，请查看试用详情。","Your trial deduction retention has ended. View trial details.","Thời hạn giữ khấu trừ dùng thử đã kết thúc. Vui lòng xem chi tiết."))
    );
}
