# SPEC-04/05 前置源码定位（未实施）

Root定向阅读当前App候选后的接线提示；03通过后才开始端实现。

- `src/api/auth-api.ts` 的 createAuthApi 集中五类签发路径；当前仅surface header、cookie mode，OTP send不携签发上下文。需按01最终固定上下文保存一次主动认证的attempt/recovery proof，并跨密码→2FA或OTP挑战保持，未知先恢复原尝试。
- `src/api/contracts.ts`、`src/api/session-vault.ts`、`src/api/api-client.ts` 需保留服务端会话组、安装、版本、结束原因及恢复结论；不得让refresh重建丢失或把其他App结束当普通离线重试。
- `src/auth/complete-sign-in.ts` 当前先本地session.claim，条款后调用phoneLogin；后端接管已前移，此处只能消费事实。条款后异步回调当前主要检查userId，须按原会话链/版本或vault revision fencing，防同账号B迟到回调覆盖C。不得依赖本地session registry赋手机权限。
- `src/pages/session/kicked.vue` 当前只区分logged-out/其他session-ended，新增被接管业务提示并显式重登录，不自动夺回。
- `src/api/onboarding-calibration-api.ts` 激活仍只有deviceId/revision/key，须扩绑定预期、策略预期、显式确认、特批及原结果恢复；binding-status保简单，完整资格另读。
- `src/pages/onboarding/connect.vue`、`src/pages/me/devices.vue` 共用换机资格与确认/协助组件，拒绝/返回不自动激活；校准后还要明确替换确认。成功回执与当前状态分开，未找到原操作不当失败。
- App/H5共享改动按sync脚本候选树/repin；H5只读状态，不获native权限，受控首次DEFERRED的404/无手机/同账号epoch/revision0保持。

实际验收仍需03接口冻结后，三语明暗/窄屏/四态/真实dev与native分别取证。此定位不是04或05通过证据。

## SPEC-06 消费合同

以 BACKEND-CONTRACT-NOTES 的SPEC03章节和当前服务端DTO为准。E6手机四参数单独提交，携预期版本进入现有A2，不与电脑配置混批。A2批准后以approve原键查询PPO；展示originalResult与currentResource差异。C5先选择服务端申请，再明确核实关联客服记录获得evidenceRef，才可确认签发；无权者只读，不自动发送消息。历史按SUCCESS/REQUEST/GRANT分列，未知额度不显示0；分页消费hasMore。发出不可确定的命令保留原key，继续原操作查询。服务端冻结账号允许撤销/关闭/恢复但不允许新签发，UI按真实结果展示。
