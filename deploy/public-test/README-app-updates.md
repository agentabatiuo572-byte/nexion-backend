# TEST 原生 App 版本产物

后台配置读取受管产物目录 `nx_app_update_artifact`。版本登记及双人审批独立于 APK 构建上传，初始 Android、iOS 策略均为空。运行时环境变量 `NEXION_APPUPDATES_ANDROIDDOWNLOADORIGIN` 对应 Spring 属性 `nexion.app-updates.android-download-origin`。TEST 配置值为 `https://api-test.uvel.ai`。未配该属性时，Android 目录为空。

## 登记整包

1. 从本次 TEST 签名流水线取得 APK、构建回执和已批准的 TEST 签名证书摘要。用 Android SDK 的 `apksigner verify --verbose --print-certs file.apk` 核对签名，用 `aapt dump badging file.apk` 核对包名、实际版本名及数字构建号。
2. 比对本地文件和 TEST 托管文件的 SHA-256。受管目录为 `/srv/uvel-app/artifacts/app-updates/android/`，文件名为小写 SHA-256 加 `.apk`。上传完成后再原子移入该目录。HTTP 入口为 `https://api-test.uvel.ai/app-updates/android/<sha256>.apk`。
3. 经 HTTPS 下载，核对完整文件摘要，然后由 TEST 发布操作者用审核过的产物信息新增目录行。每个构建号只登记一个不可变产物，一次性写入 `artifact_id,platform,version_name,latest_build,download_url,sha256`。运营接口仅接收该目录的 `artifactId`，版本名、下载地址与摘要从目录派生。iOS 仅登记已核实版本对应的 Apple App Store 安装入口。
4. 用 A3 查看目录、编辑草稿并提交。另一名具备 A2 审批权限及 A3 读取权限的管理员审批。由具备 A3 写权限且未审批此候选的管理员发布。撤回或驳回后保留草稿和记录。恢复上一策略同样需要提交、审批、发布；首发后支持恢复为空。发现制品已撤销时，发布操作者将该行的 `available` 设置为 0，客户端之后的查询即停止下发该包。

资源更新需要已部署的兼容性与签名验证流程，本版本的受管目录仅接受整包。公开接口为 `GET /api/app/updates/current?platform=android&build=<实际原生构建号>`。
