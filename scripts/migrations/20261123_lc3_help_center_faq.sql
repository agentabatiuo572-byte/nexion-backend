-- Launch Help Center FAQ, English and Vietnamese. Seed only an empty FAQ
-- catalog: any FAQ, including a draft or soft-deleted row, blocks the batch.
START TRANSACTION;
SET @lc3_help_seed = NOT EXISTS (SELECT 1 FROM nx_help_article WHERE format = 'faq');
SET @lc3_help_seeded_at = CURRENT_TIMESTAMP;

INSERT INTO nx_help_article
  (article_code, category, level, sort_order, title, content, format, surface,
   duration_min, reward_nex, progress_pct, featured, emoji, tint, version_no,
   status, created_at, updated_at, is_deleted)
SELECT faq.article_code, faq.category, faq.language, faq.sort_order, faq.title, faq.content,
       'faq', 'Help Center', 3, 0, 0, 0, '?', '#c6ff3a', 1, 1,
       @lc3_help_seeded_at, @lc3_help_seeded_at, 0
  FROM (
    SELECT 'FAQ-LC3-HELP-01-EN' AS article_code, 'account' AS category, 'en-US' AS language, 10 AS sort_order,
      'How do I create a UVEL account?' AS title,
      'Tap Sign up and register with your Vietnamese phone number (+84) and the SMS code, or use another option shown on the Sign up page, such as Google, Apple or Telegram. If a friend invited you, open their invite link or enter their invite code during sign-up.' AS content
    UNION ALL SELECT 'FAQ-LC3-HELP-01-VI', 'account', 'vi-VN', 10,
      'Làm sao để tạo tài khoản UVEL?',
      'Nhấn Đăng ký và đăng ký bằng số điện thoại Việt Nam (+84) cùng mã SMS, hoặc dùng cách khác hiển thị trên trang Đăng ký, như Google, Apple hay Telegram. Nếu được bạn bè mời, hãy mở liên kết mời hoặc nhập mã mời của họ khi đăng ký.'
    UNION ALL SELECT 'FAQ-LC3-HELP-02-EN', 'account', 'en-US', 20,
      'I cannot sign in. What should I do?',
      'Use the same method you signed up with. If you registered with a phone number, you can sign in with your password or an SMS code. If you forgot your password, tap Forgot password? and verify with your phone number. If it still fails, contact support and tell us the time and the error message you saw.'
    UNION ALL SELECT 'FAQ-LC3-HELP-02-VI', 'account', 'vi-VN', 20,
      'Tôi không đăng nhập được. Phải làm gì?',
      'Hãy dùng đúng cách bạn đã đăng ký. Nếu đăng ký bằng số điện thoại, bạn có thể đăng nhập bằng mật khẩu hoặc mã SMS. Nếu quên mật khẩu, nhấn Quên mật khẩu? và xác minh bằng số điện thoại. Nếu vẫn không được, hãy liên hệ hỗ trợ và cho biết thời gian cùng thông báo lỗi bạn thấy.'
    UNION ALL SELECT 'FAQ-LC3-HELP-03-EN', 'general', 'en-US', 30,
      'How do invite codes and the welcome reward work?',
      'Sign up with a friend''s invite link or code to join their team. Eligible new users who join through an invite receive a welcome reward in UVT, and the friend who invited them can also earn a reward. Your welcome reward is credited after your first paid order and the related checks are complete. The current amount, conditions and status are shown in the app.'
    UNION ALL SELECT 'FAQ-LC3-HELP-03-VI', 'general', 'vi-VN', 30,
      'Mã mời và quà chào mừng hoạt động như thế nào?',
      'Đăng ký bằng liên kết hoặc mã mời của bạn bè để vào đội của họ. Người dùng mới đủ điều kiện đăng ký qua lời mời sẽ nhận quà chào mừng bằng UVT, và người mời cũng có thể nhận thưởng. Quà chào mừng của bạn được ghi có sau đơn hàng thanh toán đầu tiên và khi các bước kiểm tra liên quan hoàn tất. Số tiền, điều kiện và trạng thái hiện tại được hiển thị trong ứng dụng.'
    UNION ALL SELECT 'FAQ-LC3-HELP-04-EN', 'account', 'en-US', 40,
      'How do I keep my account and funds safe?',
      'Set a Payment PIN in Me and keep it different from your login password. Never share your password, Payment PIN or verification codes with anyone, including people who say they are UVEL staff. UVEL will never ask you to transfer funds to unlock your account. If you forget your Payment PIN, go to Me > Payment PIN and choose Reset payment PIN. After 5 wrong attempts within 15 minutes, the Payment PIN is locked for 15 minutes.'
    UNION ALL SELECT 'FAQ-LC3-HELP-04-VI', 'account', 'vi-VN', 40,
      'Làm sao để giữ an toàn cho tài khoản và tiền của tôi?',
      'Thiết lập Mã PIN thanh toán trong mục Của tôi và đặt khác với mật khẩu đăng nhập. Không bao giờ chia sẻ mật khẩu, Mã PIN thanh toán hay mã xác minh với bất kỳ ai, kể cả người tự xưng là nhân viên UVEL. UVEL không bao giờ yêu cầu bạn chuyển tiền để mở khóa tài khoản. Nếu quên Mã PIN thanh toán, vào Của tôi > Mã PIN thanh toán và chọn Đặt lại mã PIN thanh toán. Nếu nhập sai 5 lần trong 15 phút, Mã PIN thanh toán sẽ bị khóa 15 phút.'
    UNION ALL SELECT 'FAQ-LC3-HELP-05-EN', 'other', 'en-US', 50,
      'How do I contact support?',
      'Go to Me > Support and open Live messages, or tap Contact support at the top of the Help center. Your support advisor will reply in the chat. To get help faster, include your order or withdrawal number, the time, and a screenshot with sensitive details covered.'
    UNION ALL SELECT 'FAQ-LC3-HELP-05-VI', 'other', 'vi-VN', 50,
      'Làm sao để liên hệ hỗ trợ?',
      'Vào Của tôi > Hỗ trợ khách hàng và mở Tin nhắn trực tiếp, hoặc nhấn Liên hệ hỗ trợ ở đầu Trung tâm trợ giúp. Nhân viên hỗ trợ sẽ trả lời trong cuộc trò chuyện. Để được giúp nhanh hơn, hãy gửi kèm mã đơn hàng hoặc mã lệnh rút, thời gian và ảnh chụp màn hình đã che thông tin nhạy cảm.'
    UNION ALL SELECT 'FAQ-LC3-HELP-06-EN', 'earnings', 'en-US', 60,
      'How are my earnings calculated and when are they credited?',
      'The daily figure on a product page is an estimate based on the device''s monthly target and its current capacity. Actual earnings are credited as your device completes tasks, so the amount and timing can differ from the estimate. You can see every credit in Bills in your wallet.'
    UNION ALL SELECT 'FAQ-LC3-HELP-06-VI', 'earnings', 'vi-VN', 60,
      'Thu nhập được tính thế nào và khi nào được ghi có?',
      'Con số hằng ngày trên trang sản phẩm là ước tính, dựa trên mục tiêu tháng và công suất hiện tại của thiết bị. Thu nhập thực tế được ghi có khi thiết bị hoàn thành nhiệm vụ, nên số tiền và thời điểm có thể khác với ước tính. Bạn có thể xem từng khoản ghi có trong mục Lịch sử của Ví.'
    UNION ALL SELECT 'FAQ-LC3-HELP-07-EN', 'earnings', 'en-US', 70,
      'What is UVT and why do I have two balances?',
      'UVT is the UVEL platform token. Some earnings and rewards are paid in UVT and others in USDT, so your wallet keeps the two balances separately. The UVT price follows the platform''s published price schedule and can go up or down.'
    UNION ALL SELECT 'FAQ-LC3-HELP-07-VI', 'earnings', 'vi-VN', 70,
      'UVT là gì và vì sao tôi có hai số dư?',
      'UVT là token của nền tảng UVEL. Một số khoản thu nhập và phần thưởng được trả bằng UVT, số khác bằng USDT, nên ví của bạn giữ hai số dư riêng. Giá UVT theo lịch giá do nền tảng công bố và có thể tăng hoặc giảm.'
    UNION ALL SELECT 'FAQ-LC3-HELP-08-EN', 'earnings', 'en-US', 80,
      'How do I exchange UVT for USDT?',
      'When Exchange is available, open My Wallet and tap Exchange. Enter the amount, check the rate and fee on screen, then tap Confirm Exchange. The minimum is 10 UVT, and each user can exchange up to the equivalent of 500 USDT per day. Requests over the daily limit are declined, so try again the next day. If Exchange is not available yet, please check back later.'
    UNION ALL SELECT 'FAQ-LC3-HELP-08-VI', 'earnings', 'vi-VN', 80,
      'Làm sao để quy đổi UVT sang USDT?',
      'Khi tính năng Quy đổi khả dụng, vào Ví của tôi và nhấn Quy đổi. Nhập số lượng, kiểm tra tỷ giá và phí trên màn hình rồi nhấn Xác nhận quy đổi. Tối thiểu 10 UVT, mỗi người dùng có thể quy đổi tối đa tương đương 500 USDT mỗi ngày. Yêu cầu vượt hạn mức ngày sẽ bị từ chối, hãy thử lại vào ngày hôm sau. Nếu Quy đổi chưa khả dụng, vui lòng quay lại sau.'
    UNION ALL SELECT 'FAQ-LC3-HELP-09-EN', 'earnings', 'en-US', 90,
      'How do team rewards work?',
      'Share your code or link from the Team page. Friends who sign up with it join your team. Team rewards are calculated according to the rules shown on the Team page and appear there once they are credited. Signing up alone does not earn a team reward.'
    UNION ALL SELECT 'FAQ-LC3-HELP-09-VI', 'earnings', 'vi-VN', 90,
      'Thưởng đội nhóm hoạt động như thế nào?',
      'Chia sẻ mã hoặc liên kết từ trang Đội nhóm. Bạn bè đăng ký bằng mã đó sẽ vào đội của bạn. Thưởng đội nhóm được tính theo quy tắc hiển thị trên trang Đội nhóm và xuất hiện tại đó khi đã được ghi có. Chỉ đăng ký thì không được tính thưởng đội nhóm.'
    UNION ALL SELECT 'FAQ-LC3-HELP-10-EN', 'hardware', 'en-US', 100,
      'What do I get when I buy a UVEL device?',
      'You get a hosted compute plan. Real GPU hardware is allocated to you and runs in one of our data centers. We install, power and maintain it, and hardware faults during the warranty period are repaired free of charge. The device is not shipped to you and you do not need to set anything up. You can follow its status in Orders.'
    UNION ALL SELECT 'FAQ-LC3-HELP-10-VI', 'hardware', 'vi-VN', 100,
      'Khi mua thiết bị UVEL, tôi nhận được gì?',
      'Bạn nhận một gói điện toán được lưu trữ tại trung tâm dữ liệu. Phần cứng GPU thật được phân bổ cho bạn và chạy tại một trong các trung tâm dữ liệu của chúng tôi. Chúng tôi lắp đặt, cấp điện và bảo trì; lỗi phần cứng trong thời gian bảo hành được sửa miễn phí. Thiết bị không được gửi đến bạn và bạn không cần tự cài đặt. Bạn có thể theo dõi trạng thái trong mục Đơn hàng.'
    UNION ALL SELECT 'FAQ-LC3-HELP-11-EN', 'hardware', 'en-US', 110,
      'What is Cloud Share, and how is it different from a device?',
      'Cloud Share is the entry option. Instead of a whole device, you get a share of a hosted GPU pool, and eligible earnings, including UVT, are credited as tasks are completed. A full device gives you dedicated hosted hardware with maintenance and warranty. Check each product page for its price and estimated earnings.'
    UNION ALL SELECT 'FAQ-LC3-HELP-11-VI', 'hardware', 'vi-VN', 110,
      'Cloud Share là gì và khác gì so với thiết bị?',
      'Cloud Share là lựa chọn khởi đầu. Thay vì cả một thiết bị, bạn nhận một phần trong cụm GPU được lưu trữ tại trung tâm dữ liệu, và thu nhập đủ điều kiện, kể cả UVT, được ghi có khi nhiệm vụ hoàn thành. Thiết bị đầy đủ cho bạn phần cứng riêng được lưu trữ, có bảo trì và bảo hành. Xem trang của từng sản phẩm để biết giá và thu nhập ước tính.'
    UNION ALL SELECT 'FAQ-LC3-HELP-12-EN', 'hardware', 'en-US', 120,
      'How does the free trial work?',
      'Eligible new users can try a UVELBox S1 for 3 days. Spots are limited each day. Trial credits and any purchase offer are shown on the trial page. When the trial ends, you have 7 days to decide whether to buy. Nothing is charged automatically; a purchase only happens if you confirm it.'
    UNION ALL SELECT 'FAQ-LC3-HELP-12-VI', 'hardware', 'vi-VN', 120,
      'Dùng thử miễn phí hoạt động như thế nào?',
      'Người dùng mới đủ điều kiện có thể dùng thử UVELBox S1 trong 3 ngày. Số suất mỗi ngày có hạn. Khoản ghi có khi dùng thử và ưu đãi mua (nếu có) hiển thị trên trang dùng thử. Khi hết thời gian dùng thử, bạn có 7 ngày để quyết định có mua hay không. Không có khoản nào bị trừ tự động; việc mua chỉ diễn ra khi bạn xác nhận.'
    UNION ALL SELECT 'FAQ-LC3-HELP-13-EN', 'hardware', 'en-US', 130,
      'Why does my device''s capacity go down over time?',
      'Each device series follows a capacity schedule that lowers its capacity step by step over time, reflecting normal hardware wear. Capacity is part of how estimated daily earnings are calculated, so the estimate goes down when capacity goes down.'
    UNION ALL SELECT 'FAQ-LC3-HELP-13-VI', 'hardware', 'vi-VN', 130,
      'Vì sao công suất thiết bị của tôi giảm dần theo thời gian?',
      'Mỗi dòng thiết bị có một lịch công suất, giảm dần theo thời gian để phản ánh mức hao mòn thông thường của phần cứng. Công suất là một phần trong cách tính thu nhập ước tính hằng ngày, nên ước tính cũng giảm khi công suất giảm.'
    UNION ALL SELECT 'FAQ-LC3-HELP-14-EN', 'deposit', 'en-US', 140,
      'How do I deposit USDT, and what are the minimum and fee?',
      'Open My Wallet, tap Top-up and choose a network shown on the page. USDT deposits use BEP20 (BNB Smart Chain) or ERC20 (Ethereum) when that network is available; TRC20 is not supported. Always copy the address for the network you selected. The minimum deposit is 10 USDT, and a 1 USDT fee is deducted from each deposit. Funds sent on another network or as another token may not be recoverable.'
    UNION ALL SELECT 'FAQ-LC3-HELP-14-VI', 'deposit', 'vi-VN', 140,
      'Làm sao để nạp USDT, mức tối thiểu và phí là bao nhiêu?',
      'Vào Ví của tôi, nhấn Nạp tiền và chọn mạng hiển thị trên trang. Nạp USDT dùng BEP20 (BNB Smart Chain) hoặc ERC20 (Ethereum) khi mạng đó khả dụng; không hỗ trợ TRC20. Luôn sao chép đúng địa chỉ của mạng đã chọn. Số tiền nạp tối thiểu là 10 USDT, và mỗi lần nạp bị trừ phí 1 USDT. Tiền gửi sai mạng hoặc sai loại token có thể không khôi phục được.'
    UNION ALL SELECT 'FAQ-LC3-HELP-15-EN', 'deposit', 'en-US', 150,
      'My deposit has not arrived. What should I do?',
      'Check that you used the correct network and address, and that the transaction has at least 15 confirmations in a block explorer. After that, the platform verifies the deposit before crediting it. If it still has not arrived, contact support with the transaction hash, amount and network.'
    UNION ALL SELECT 'FAQ-LC3-HELP-15-VI', 'deposit', 'vi-VN', 150,
      'Tiền nạp của tôi chưa về. Phải làm gì?',
      'Hãy kiểm tra bạn đã dùng đúng mạng và địa chỉ, và giao dịch đã có ít nhất 15 xác nhận trên trình khám phá khối. Sau đó nền tảng sẽ xác minh khoản nạp trước khi ghi có. Nếu vẫn chưa về, hãy liên hệ hỗ trợ kèm mã giao dịch (hash), số tiền và mạng.'
    UNION ALL SELECT 'FAQ-LC3-HELP-16-EN', 'withdrawal', 'en-US', 160,
      'How do I withdraw USDT to my wallet?',
      'Open My Wallet, tap Withdraw, choose USDT and then a network shown in the app. The network fee is 1 USDT on BEP20 and 5 USDT on ERC20. The minimum is 20 USDT, you can make up to 2 withdrawals per day, and each withdrawal can be up to 80% of your available balance. You can follow each request on its tracking page.'
    UNION ALL SELECT 'FAQ-LC3-HELP-16-VI', 'withdrawal', 'vi-VN', 160,
      'Làm sao để rút USDT về ví?',
      'Vào Ví của tôi, nhấn Rút tiền, chọn USDT rồi chọn mạng hiển thị trong ứng dụng. Phí mạng là 1 USDT với BEP20 và 5 USDT với ERC20. Tối thiểu 20 USDT, tối đa 2 lần rút mỗi ngày, và mỗi lần rút tối đa 80% số dư khả dụng. Bạn có thể theo dõi từng lệnh trên trang theo dõi của lệnh đó.'
    UNION ALL SELECT 'FAQ-LC3-HELP-17-EN', 'withdrawal', 'en-US', 170,
      'Can I withdraw to a Vietnamese bank account?',
      'When Bank account is available as a withdrawal method, you can withdraw in VND. The fee is 1% of the amount, at least 1 USDT and at most 25 USDT, and each request can be 20 to 5,000 USDT. The VND amount is shown in a quote before you confirm, and the quote is held for 10 minutes. Bank withdrawals have a holding period, currently 3 days. After that, the request is reviewed and, if approved, sent to your bank. Follow its status on the tracking page.'
    UNION ALL SELECT 'FAQ-LC3-HELP-17-VI', 'withdrawal', 'vi-VN', 170,
      'Tôi có thể rút về tài khoản ngân hàng Việt Nam không?',
      'Khi phương thức Tài khoản ngân hàng khả dụng, bạn có thể rút bằng VND. Phí là 1% số tiền, tối thiểu 1 USDT và tối đa 25 USDT, mỗi lệnh từ 20 đến 5.000 USDT. Số tiền VND hiển thị trong báo giá trước khi bạn xác nhận, và báo giá được giữ trong 10 phút. Lệnh rút về ngân hàng có thời gian chờ, hiện là 3 ngày. Sau đó lệnh được xem xét và, nếu được duyệt, sẽ được chuyển đến ngân hàng của bạn. Hãy theo dõi trạng thái trên trang theo dõi.'
    UNION ALL SELECT 'FAQ-LC3-HELP-18-EN', 'withdrawal', 'en-US', 180,
      'Why is my withdrawal under review or unavailable?',
      'Withdrawals of 500 USDT or more are reviewed manually, and new accounts may wait longer. Other security checks can also add time. You do not need to submit again; follow the request on its tracking page. If you cannot choose a withdrawal method or network, read the message on the page: the channel may be temporarily closed, or an earlier request may need to finish first. If a request takes longer than expected, contact support with the withdrawal number.'
    UNION ALL SELECT 'FAQ-LC3-HELP-18-VI', 'withdrawal', 'vi-VN', 180,
      'Vì sao lệnh rút của tôi đang được xem xét hoặc không khả dụng?',
      'Lệnh rút từ 500 USDT trở lên được xem xét thủ công, và tài khoản mới có thể phải chờ lâu hơn. Các bước kiểm tra bảo mật khác cũng có thể làm mất thêm thời gian. Bạn không cần gửi lại; hãy theo dõi lệnh trên trang theo dõi. Nếu bạn không chọn được phương thức hoặc mạng rút, hãy đọc thông báo trên trang: kênh có thể đang tạm đóng, hoặc một lệnh trước đó cần hoàn tất trước. Nếu lệnh lâu hơn dự kiến, hãy liên hệ hỗ trợ kèm mã lệnh rút.'
    UNION ALL SELECT 'FAQ-LC3-HELP-19-EN', 'technical', 'en-US', 190,
      'Where do I download or update the official UVEL app?',
      'Only install UVEL from the official download page or the app store links provided by UVEL. In the mobile app, go to Me > Preferences > About UVEL > App updates to check for a new version. If you use UVEL in a web browser, there is nothing to install or update. Do not install copies sent to you by other people.'
    UNION ALL SELECT 'FAQ-LC3-HELP-19-VI', 'technical', 'vi-VN', 190,
      'Tải hoặc cập nhật ứng dụng UVEL chính thức ở đâu?',
      'Chỉ cài UVEL từ trang tải xuống chính thức hoặc liên kết cửa hàng ứng dụng do UVEL cung cấp. Trong ứng dụng di động, vào Của tôi > Tùy chọn > Giới thiệu về UVEL > Cập nhật ứng dụng để kiểm tra phiên bản mới. Nếu bạn dùng UVEL trên trình duyệt web, bạn không cần cài đặt hay cập nhật. Không cài bản do người khác gửi cho bạn.'
    UNION ALL SELECT 'FAQ-LC3-HELP-20-EN', 'technical', 'en-US', 200,
      'How do I change the app language? Why are some answers in English?',
      'Go to Me > Language and choose a language. Help answers are available in English and Vietnamese. If you use another language, they are shown in English.'
    UNION ALL SELECT 'FAQ-LC3-HELP-20-VI', 'technical', 'vi-VN', 200,
      'Làm sao để đổi ngôn ngữ ứng dụng? Vì sao một số câu trả lời bằng tiếng Anh?',
      'Vào Của tôi > Ngôn ngữ và chọn ngôn ngữ. Câu trả lời trợ giúp có bằng tiếng Anh và tiếng Việt. Nếu bạn dùng ngôn ngữ khác, câu trả lời sẽ hiển thị bằng tiếng Anh.'
  ) AS faq
 WHERE @lc3_help_seed;

-- Record the system origin alongside the articles in the same transaction.
INSERT INTO nx_audit_log
  (service_name, action, resource_type, resource_id, biz_no, actor_type, actor_username,
   result, risk_level, detail_json, created_at, is_deleted)
SELECT 'nexion-backend', 'M4_SUPPORT_FAQ_CREATED', 'SUPPORT_KNOWLEDGE',
       article_code, article_code, 'SYSTEM', 'migration:lc3-help',
       'SUCCESS', 'LOW',
       JSON_OBJECT('category', category, 'surface', surface, 'status', 'PUBLISHED',
                   'language', level, 'translationLanguages', JSON_ARRAY(),
                   'reason', 'Seed the launch Help Center FAQ',
                   'migration', '20261123_lc3_help_center_faq'),
       @lc3_help_seeded_at, 0
  FROM nx_help_article
 WHERE @lc3_help_seed AND format = 'faq' AND article_code LIKE 'FAQ-LC3-HELP-%';

COMMIT;
