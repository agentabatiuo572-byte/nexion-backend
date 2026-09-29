-- #350: newly registered users receive the published Nova welcome title.
-- Retire only the canonical old-brand titles; preserve edited copy, other
-- channels, and notifications already delivered to users.
UPDATE nx_nova_template
   SET title_zh = IF(BINARY title_zh IN ('欢迎来到 Nexion', '欢迎来到 NexGrid'),
                     '欢迎来到 UVEL', title_zh),
       title_vi = IF(BINARY title_vi IN ('Chào mừng đến Nexion', 'Chào mừng đến NexGrid'),
                     'Chào mừng đến UVEL', title_vi),
       title_en = IF(BINARY title_en IN ('Welcome to Nexion', 'Welcome to NexGrid'),
                     'Welcome to UVEL', title_en),
       updated_at = NOW()
 WHERE channel_key = 'welcome'
   AND is_deleted = 0
   AND (BINARY title_zh IN ('欢迎来到 Nexion', '欢迎来到 NexGrid')
     OR BINARY title_vi IN ('Chào mừng đến Nexion', 'Chào mừng đến NexGrid')
     OR BINARY title_en IN ('Welcome to Nexion', 'Welcome to NexGrid'));
