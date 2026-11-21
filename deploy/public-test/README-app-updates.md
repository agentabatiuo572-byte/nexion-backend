# TEST 原生 App 版本产物

后台配置读取受管产物目录 `nx_app_update_artifact`。版本登记及双人审批独立于 APK 构建上传，初始 Android、iOS 策略均为空。运行时环境变量 `NEXION_APPUPDATES_ANDROIDDOWNLOADORIGIN` 对应 Spring 属性 `nexion.app-updates.android-download-origin`。TEST 配置值为 `https://api-test.uvel.ai`。未配该属性时，Android 目录为空。

## 登记整包

1. 从本次 TEST 签名流水线取得 APK、构建回执和已批准的 TEST 签名证书摘要。用 Android SDK 的 `apksigner verify --verbose --print-certs file.apk` 核对签名，用 `aapt dump badging file.apk` 核对包名、实际版本名及数字构建号。
2. 比对本地文件和 TEST 托管文件的 SHA-256。受管目录为 `/srv/uvel-app/artifacts/app-updates/android/`，文件名为小写 SHA-256 加 `.apk`。上传完成后再原子移入该目录。HTTP 入口为 `https://api-test.uvel.ai/app-updates/android/<sha256>.apk`。
3. 经 HTTPS 下载，核对完整文件摘要，然后由 TEST 发布操作者用审核过的产物信息新增目录行。每个构建号只登记一个不可变产物，一次性写入 `artifact_id,platform,version_name,latest_build,download_url,sha256`。运营接口仅接收该目录的 `artifactId`，版本名、下载地址与摘要从目录派生。iOS 仅登记已核实版本对应的 Apple App Store 安装入口。
4. 用 A3 查看目录、编辑草稿并提交。另一名具备 A2 审批权限及 A3 读取权限的管理员审批。由具备 A3 写权限且未审批此候选的管理员发布。撤回或驳回后保留草稿和记录。恢复上一策略同样需要提交、审批、发布；首发后支持恢复为空。发现制品已撤销时，发布操作者将该行的 `available` 设置为 0，客户端之后的查询即停止下发该包。

登记任一 APK/WGT 后，执行 TEST 宿主上的 `sudo /usr/local/sbin/uvel-sync-app-update-downloads`。该工具从可用目录重建精确到 SHA-256 文件的 GET/HEAD 例外，并在重载前验证 Nginx 配置。运营审批前用手机 GET 实际下载并核对文件摘要。撤销时先确认没有生效或待审批的引用，将目录行停用，立即执行同一工具，然后移走文件。未登记路径继续沿用 TEST 原有网络白名单。运维安装时从经审核的 backend test 提交取得同目录 `sync_app_update_downloads.py`，以 root:root、0755 安装至上述路径。

TEST 下载统一发送 `Cache-Control: private, no-store`。对先前允许长期缓存的制品，撤销后若公网仍返回 Cloudflare HIT，使用经授权的 TEST 控制台清除该文件的精确 HTTPS URL，并复核旧 URL 的响应。

## 登记 TEST 资源包

签发方核对 WGT 内的 DCloud appId、资源构建号及发行说明，记录适用的原生宿主构建范围。运行时通过 `NEXION_APPUPDATES_RESOURCEPUBLICKEY` 为服务配置同一枚 TEST P-256 验签公钥（X.509 SubjectPublicKeyInfo DER 的 Base64）。客户端固定这枚公钥。私钥保留在签发方。

签名输入是以下九项以单个 LF 连接的 UTF-8 字节，最后一行无换行。各项依次为 `uvel-test-resource-v1`、`platform`、`appId`、`versionName`、十进制 `latestBuild`、`minNativeBuild`、`maxNativeBuild`、`sizeBytes` 和小写 `sha256`。算法为 ECDSA P-256/SHA-256，签名采用 IEEE P1363 编码，存储 64 字节签名的小写十六进制文本。

签发后把文件放在 `/srv/uvel-app/artifacts/app-updates/<platform>/<sha256>.wgt`，从相应 TEST HTTPS 入口回读并核对签名清单、字节长度及完整摘要，再向独立目录 `nx_app_update_resource` 登记这些属性及不可变下载地址。资源和整包使用各自的构建序列、共享审批流程，恢复申请恢复上次发布前的整包与资源组合。公开检查传入 `GET /api/app/updates/current?platform=android&build=<原生构建号>&resourceBuild=<当前资源构建号>`，先判断整包最低门槛与推荐，再对处于兼容范围的宿主返回日常资源推荐。

资源查询须同时发送运行时 `appId` 与 `hostPackage`。Android 产品 App 使用 `__UNI__G77FFD9D` 与 `ai.uvel.app`。三星 TEST 标准基座使用单独签发的资源，标识为 `HBuilder` 与 `io.dcloud.HBuilder`，适配范围起止均为 1532。客户端安装前再次核对这些值。iOS 在核实实机 Bundle ID 后才配置 `NEXION_APPUPDATES_IOSRESOURCEBUNDLEID`，未配置时使用原整包策略。缺失或不匹配的宿主身份同样按整包策略处理。
