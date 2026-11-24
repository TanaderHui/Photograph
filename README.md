# 拾光永恒摄影约拍

开发基线见《终版方案》V1.0。首发范围为同城线下约拍，覆盖手机号登录、摄影师入驻审核、套餐与排班、预约、拍摄互认、线下交片确认和订单完成。Android 供顾客与摄影师使用，运营后台处理入驻申请。

| 方案范围 | 当前实现 |
| --- | --- |
| F01、F02 | 验证码、会话轮换、入驻申请、资料、风格、套餐，以及按昵称、城市、风格的摄影师搜索。作品上传、作品搜索和信誉推荐后续接入。 |
| F08、F09 | 付费和互免分别开启，套餐预约保存价格等快照。付费线下结算，互免服务费为零。支持工作区间、缓冲、档期公开开关、申请、接单、拒单、撤回、超时、取消、改期和订单通知。排班当前按具体时间区间维护。需求卡和协议版本后续接入。 |
| F13、F17 | 双方确认拍摄后进入待交片。摄影师标记已线下交片，顾客核对后确认收片，订单进入已完成。两步均记录时间并通知对方。入驻审核保留操作人、原因和版本。 |

底部入口为首页、社区、消息、约拍助手和我的。通过审核的摄影师看到「摄影师助手」。首页可按昵称、城市、风格筛选开启接单且有上架套餐的摄影师，点入可查看套餐并申请预约。定位由用户在城市选择器主动触发，支持手动选城。消息集中展示私聊与订单通知。订单通知默认只展示最新一条，点击可展开更早的通知，每条通知可进入对应订单。摄影师主页和订单详情都能发起私聊，同一对用户共用会话。我的包含账号、工作台、注销入口，以及部署时配置的隐私、客服和备案信息。

点击首页搜索栏右侧的圆形图标可展开拍摄地图。地图可拖动、缩放，可搜索地点或城市、筛选风景和机位，点地点可查看详情并调用设备的地图应用。开发数据含北京、上海、杭州和纽约的八个地标，例图、帖子地点关联和地点提报待接入。

首发采用线下交付。App 记录交片与收片。社区默认开放现有的文字发帖、搜索、关注和删除自己的帖子。私聊支持发送文字、未读提示、前台同步和历史分页。图文社区、私聊图片、举报与拉黑、私密相册、评价和作品发布仍在后续范围。如需临时关闭社区，后端设置 `PHOTOGRAPH_COMMUNITY_ENABLED=false`，Android 构建时设置 `-PphotographCommunityEnabled=false`。

## 后端与数据库

在项目根目录启动 PostgreSQL/PostGIS。使用 JDK 21 和 Maven 3.9 开发。

```powershell
docker compose up -d db
Set-Location backend
mvn.cmd -B package
java -jar target/photograph-api-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
```

`local` 使用 Compose 中的开发数据库账号，API 监听在 `127.0.0.1:8080`。Flyway 在 `photograph_app` schema 管理版本。首次迁移安装 PostGIS 与 `btree_gist`，数据库角色需要相应权限。

在 `local` 环境发送登录码后，开发服务终端会打印挑战 ID 和六位码。生产环境设置 `PHOTOGRAPH_DB_URL`、`PHOTOGRAPH_DB_USER`、`PHOTOGRAPH_DB_PASSWORD` 和至少 32 字符的随机 `PHOTOGRAPH_OTP_SECRET`，并在 HTTPS 入口后运行。使用 `PHOTOGRAPH_SMS_URL` 与 `PHOTOGRAPH_SMS_TOKEN` 配置短信适配器。适配器以 Bearer 鉴权向该 HTTPS 地址 POST JSON，内容为 `phone`、`challengeId`、`code`、`expiresIn`。由部署方选定真实供应商并验证送达。Android 和运营端当前在运行期间保存会话，应用进程重启或页面重载后需重新登录。

另开一个终端检查健康和词表接口。

```powershell
Invoke-RestMethod http://127.0.0.1:8080/actuator/health/readiness
Invoke-RestMethod http://127.0.0.1:8080/api/v1/styles
```

首次启动成功后，Flyway 历史应有 V1 至 V9 的成功记录。升级已有数据库前先备份。V7 增加账号注销标记，V8 增加交片和收片时间，V9 增加双人会话、聊天消息和已读记录。

```powershell
docker compose exec -T db psql -U photograph -d photograph -c "SELECT version, description, success FROM photograph_app.flyway_schema_history ORDER BY installed_rank;"
```

公开查询只展示已审核的摄影师和已上架套餐。完整的当前接口契约见 [backend/openapi.yaml](backend/openapi.yaml)。

## Android

用 Android Studio 打开 `android/`，选择 JDK 21 为 Gradle JDK，并设置 Android SDK 路径。项目使用 Android API 37.0、Build-Tools 36.0.0 和 AGP 9.1.1，本机可用 Gradle 9.3.1 或本次构建使用的 9.8.2。

```powershell
Set-Location android
gradle :app:assembleDebug
```

标准 Android 模拟器中的调试版使用 `http://10.0.2.2:8080/` 访问宿主机后端，APK 在 `android/app/build/outputs/apk/debug/app-debug.apk`。需要模拟器联调独立后端时，可用 `-PphotographDebugApiBaseUrl=http://10.0.2.2:18088/` 指向备用端口。

拍摄地图通过 MapLibre Native 渲染，默认使用 OpenFreeMap 的 Liberty 在线底图。可用 `-PphotographMapStyleUrl=https://实际域名/style.json` 替换底图。标点由本地 API 提供，客户端查询范围按 WGS84 的西、南、东、北顺序传入。

### 首发构建

构建前准备已部署的 HTTPS API、隐私政策公开页和可实际受理售后与投诉的 HTTPS 客服页。隐私政策应反映手机号、可选定位、地图与短信供应商的实际处理，以及注销、私聊、社区内容、历史订单和审核运营记录的保留规则。首次启动展示政策入口与同意选项，在用户同意后才进入首页。政策变更时更新政策 URL 以重新征求同意。

```powershell
gradle :app:assembleRelease -PphotographApiBaseUrl=https://api.example.com/ -PphotographPrivacyUrl=https://example.com/privacy -PphotographSupportUrl=https://example.com/support
```

将上面的地址换成实际地址。取得备案号后追加 `-PphotographFilingNumber=实际备案号`，应用会在「我的」展示备案号和工信部查询入口。若通过 Android Studio 的 **Generate Signed App Bundle or APK** 构建签名包，先把同名参数写入用户级 Gradle 属性，再使用发行密钥签名。也可以按团队签名流程签署上述命令生成的未签名 APK。保管签名密钥和备份。

真实服务使用独立数据库、安全密钥以及短信服务，运营前端构建结果通过 HTTPS 发布，并在同源入口代理 `/api/v1` 与 `/actuator/health/readiness`。送审前在真机用顾客、摄影师和后台运营三个角色验收。至少确认真实短信、套餐与档期、预约接单、改期取消、双方拍摄确认、交片收片通知、已完成分组、客服和账号注销可用。

### GitHub 版本更新

更新来源是 [TanaderHui/Photograph Releases](https://github.com/TanaderHui/Photograph/releases)，后端默认查询该仓库。需要更换发布仓库时，设置 `PHOTOGRAPH_GITHUB_RELEASE_REPOSITORY=owner/repo`。用户可在「我的 → 检查更新」查看当前版本并下载 APK。App 在前台自动检查，用户也可在更新窗口开启系统提醒。后台联网检查的间隔设为 15 分钟，实际时间由 Android 调度。只有正式 Release 上传可识别的 APK 后，才会显示更新。

仓库的 `Android release` 工作流在推送 `v主版本.次版本.修订版本` 标签后构建、签名并发布 APK。发布前在 GitHub 仓库的 `Settings → Secrets and variables → Actions` 配置以下项。

| 类型 | 名称 |
| --- | --- |
| Variables | `ANDROID_API_BASE_URL`、`ANDROID_PRIVACY_URL`、`ANDROID_SUPPORT_URL`，以及可选的 `ANDROID_FILING_NUMBER` |
| Secrets | `ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` |

`ANDROID_KEYSTORE_BASE64` 保存正式签名文件的 Base64。后续覆盖安装要沿用同一个包名与签名密钥。密钥和密码保管在 GitHub Secrets 和自己的备份里。

工作流将 `v0.1.1` 编译为 `versionName=0.1.1`、`versionCode=1001`，上传 `photograph-1001.apk`。后续版本按 `主版本 × 1000000 + 次版本 × 1000 + 修订版本` 计算。配置妥当后，从要发布的提交创建并推送标签即可。

```powershell
git tag v0.1.1
git push origin v0.1.1
```

也可在本机构建并签署，再把 APK 命名为 `photograph-<versionCode>.apk` 上传到对应的正式 Release，并设为最新版本。单纯推送源码会更新仓库。发布标签及对应的正式安装包供用户升级。

## 运营端

在另一个终端使用 Node.js 22.12+ 或更新的受支持版本。Vite 将词表和就绪检查代理到本地后端。

```powershell
Set-Location admin-web
npm.cmd ci
npm.cmd run build
npm.cmd run dev
```

打开 `http://127.0.0.1:5173`。首次创建运营账号时，先用该手机号通过验证码登录一次，再由数据库管理员按手机号授予权限。下面的 `+8613800000000` 是占位号码。

```powershell
docker compose exec -T db psql -U photograph -d photograph -c "UPDATE photograph_app.accounts SET is_operator = true WHERE phone = '+8613800000000';"
```

让摄影师在 Android 工作台提交入驻申请。运营登录后填写理由并通过或驳回，摄影师刷新工作台即可查看结果。审核通过后，摄影师可上架套餐、开启合作方式、设置工作区间。另一个账号可在发现页搜索并提交预约，在订单页查看状态。

## 验证状态

可用下方脚本检查预约回归、交片权限与幂等、订单通知与分组、摄影师筛选、文字社区、会话访问权限、重复发送、已读状态以及注销后撤销会话和下架服务。真实短信和正式签名真机验收依赖部署配置。

可用隔离数据库重跑预约回归。先在根目录执行 `docker compose exec -T db createdb -U photograph photograph_check_booking`。在单独终端以 `local` 模式运行后端，将 `PHOTOGRAPH_DB_URL` 指向 `jdbc:postgresql://127.0.0.1:5432/photograph_check_booking`，并将标准输出重定向到 `booking-dev.log`。再运行以下命令。脚本会创建测试账号、预约和通知，并修改测试预约时间以触发到期和拍摄确认。回归默认检查社区可用。后端关闭社区时，在命令中追加 `--community-disabled`。

```powershell
python backend/tests/booking_smoke.py --database photograph_check_booking --dev-log booking-dev.log
```
