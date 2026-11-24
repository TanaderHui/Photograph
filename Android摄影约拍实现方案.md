# 拾光永恒 · Android 摄影约拍实现方案
**评审稿 v0.1 ｜ 2026-11-21 ｜ 待项目负责人确认**

建议先验证一个问题：客户看到喜欢的作品后，能否找到合适的套餐和时间，得到摄影师的明确答复，并在同一张订单里收到成片。首版按同城、多摄影师、付费套餐、线下结算设计。摄影师接单前，客户看到的是“申请已提交”；接单后才显示“预约成功”。

## 一、GitHub 项目比较

| 项目与许可证 | 已公开的功能 | 架构与技术栈 | 借鉴价值 | 对本项目的局限 |
|---|---|---|---|---|
| Photosec · MIT | 摄影师主页和作品、搜索、发起约拍、接单或拒单、后台审核 | 服务端渲染的 PHP 多页面应用，Apache、MySQL；申请、审批、取消分别有 PHP 入口 | 三方角色和接单流程与本项目最接近，可用来核对页面和权限 | 页面与操作入口绑定，移植到 Android 仍需设计独立 API；README 的初始化需要逐表执行 |
| Easy!Appointments · 代码 GPL-3.0 | 服务与服务者、工作计划、预约规则、日历同步、邮件通知 | 可自托管的 Web 应用，PHP、CodeIgniter、MySQL，提供 REST API 与 Docker Compose 开发环境 | 可参考“服务时长 + 人员工作时间 + 例外日期”的档期模型 | 核心是预约管理，摄影风格发现、作品与套餐关联、订单交片仍需另行设计 |
| Piwigo-Android · GPL-3.0 或更高版本 | 相册导航、全屏看图、上传、多账户、访客浏览 | 原生 Android 客户端连接 Piwigo 图库服务，Gradle、MVVM、Android Data Binding、Dagger 2 | 可参考从缩略图进入大图、浏览整组作品和摄影师上传的交互 | 客户端面向图库服务器，订单参与者权限、改期和履约需要另外实现 |

比较依据为公开仓库目录、README、运行及许可说明，未本地构建或压测。优缺点按本项目需求判断。建议独立实现，只借鉴业务规则和交互。
 
项目来源：[Photosec](https://github.com/parth-cse/photosec_a_market_place_for_photographers_web_technology_php) · [Easy!Appointments](https://github.com/alextselegidis/easyappointments) · [Piwigo-Android](https://github.com/Piwigo/Piwigo-Android)

## 二、首版功能及角色

| 入口 | 首版范围 | 验收示例 |
|---|---|---|
| 客户 · 发现与预约 | 按城市、风格、价格筛选；作品详情关联摄影师和套餐；查看时长、精修张数、交片天数和总价；选择可用时间并填写拍摄需求 | 从一组喜欢的作品进入预约时保留作品和套餐；提交前能看懂约定内容 |
| 摄影师 · 工作台 | 同账号申请入驻，审核通过后发布作品和套餐；维护每周工作时段、休息日与外拍缓冲；处理申请和改期 | 临时闭店后相应时间停止接受新单；已有预约需单独处理 |
| 双方 · 订单 | 查看进度、订单留言、申请改期或取消、交片提醒；摄影师标记拍摄完成并交片，客户确认收片和评价 | 订单留存成交时套餐快照；之后调价不会改变历史约定 |
| 客户 · 私密交付 | 查看本单成片、按张下载；订单相册与公开作品独立 | 另一客户即使知道订单号也无法获得下载链接；公开展示另行征得客户同意 |
| 运营 · Web 后台 | 入驻审核、作品举报与下架、纠纷处理、操作日志 | 审核有操作者和理由；运营查询默认只展示履约所需信息 |

首版使用订单内异步留言和站内通知。二期候选包括在线定金与退款、互免招募、选片返修、即时聊天及系统推送。若首发走互免招募，需要增加需求发布、报名、筛选和双向确认；若首发收定金，需要增加支付状态、付款超时释放、退款和对账。

## 三、核心规则

- **档期**：按摄影师和当地时区计算。30 分钟为建议起约步长，忙碌区间包含套餐时长及摄影师设置的缓冲。申请待确认时允许同档多个申请，提交前告知客户。摄影师确认时数据库以摄影师 ID 和左闭右开时间区间执行排斥约束，同一事务写入有效占用、更新订单、关闭重叠申请并生成通知。拒绝或过期不占档。建议申请最迟在提交后 24 小时或开拍时到期，取较早者。
- **改期与取消**：双方均可在开拍前发起改期，对方同意后在事务中置换档期。新时间冲突则保留原预约；取消或改期失败都向双方显示结果。待确认申请可撤回，已确认订单开拍前可取消并记录原因。临近开拍的取消、爽约和交付纠纷进入运营处理。
- **状态**：主流程为待确认 → 已确认 → 待交片 → 已交片 → 已完成。分支为已拒绝、已撤回、已过期、已取消、档期冲突关闭。改期申请单独记录，处理期间原订单有效。摄影师在拍摄结束后标记待交片，发布至少一张处理成功的照片后转已交片；客户确认后才可评价，每单限一次。
- **作品与交付**：上传时区分公开作品和订单成片。对象存储采用私有桶；审核通过的公开作品生成去定位元数据的展示图。私密成片先校验订单归属再签发短时下载地址。失败文件可单独重传；订单内显示已成功张数与约定张数，未达约定数量时禁止最终交付。

## 四、架构与技术栈

| 层 | 首选技术 | 边界与理由 |
|---|---|---|
| Android | Kotlin、Jetpack Compose、ViewModel、StateFlow、Retrofit/OkHttp、Coil、Room、WorkManager | 单 App 切换客户与摄影师工作台；按发现、工作台、订单、相册划分功能。Room 缓存列表和草稿，上传任务可恢复；下单与接单以服务端回执为准 |
| 业务服务 | Java 21、Spring Boot、Spring Security、Flyway | 模块化单体，一个部署单元内划分账号、作品套餐、排班、订单、媒体和运营模块。档期和订单共用数据库事务，减少跨服务一致性成本 |
| 数据与任务 | PostgreSQL、事务事件表、后台任务进程 | 保存用户、摄影师、作品、套餐及其快照、排班例外、档期占用、订单、改期、留言、交付文件与审核日志。事件随订单一起提交，通知失败可重试并按事件 ID 去重 |
| 图片 | S3 兼容对象存储、缩略图任务、公开展示图 CDN | Android 凭短时签名直传，业务服务负责授权、校验和记录归属；作品流取缩略图，大图按需加载 |
| 运营与部署 | Vue 3、TypeScript、Nginx、Docker Compose | 一个运营后台和一套 HTTPS API；测试、生产分别配置数据库和私有存储。上线前验证数据库恢复、订单追踪和失败任务告警 |

## 五、接口标准草案

采用 HTTPS、REST、UTF-8 JSON 和 OpenAPI 3.1。统一前缀 `/api/v1`。ID 为不透明字符串；时间为带时区偏移的 RFC 3339，数据库按 UTC 存储，排班保留 IANA 时区；人民币金额用整数分并带 `currency: CNY`。分页使用 `cursor`、`limit` 和 `nextCursor`，默认 20、上限 50。公开查询可匿名，受限资源使用 Bearer access token，刷新令牌轮换，退出时撤销刷新会话。

| 接口（省略统一前缀） | 权限与行为 |
|---|---|
| POST /auth/otp/challenges；POST /auth/sessions；POST /auth/sessions/refresh | 发送短信验证码、验证登录、刷新会话；验证码限频且一次使用 |
| GET /works；GET /photographers/{id}；GET /photographers/{id}/packages | 公开内容查询；作品列表支持 city、style、minPriceMinor、maxPriceMinor、cursor |
| GET /photographers/{id}/availability?packageId=…&date=… | 按套餐时长返回可申请的开始与结束时间、排班时区；结果只代表查询时刻 |
| PUT /me/photographer/availability；POST /me/photographer/packages；POST /me/photographer/works | 经审核摄影师维护本人排班、套餐和作品 |
| POST /bookings；GET /me/bookings；GET /bookings/{id} | 客户提交申请、参与者查询；服务端推导摄影师、时长和价格并保存快照 |
| POST /bookings/{id}/actions | action 可为 accept、reject、withdraw、cancel、finishShoot、confirmReceipt。服务端逐项校验角色、状态、版本及时间 |
| POST /bookings/{id}/reschedules；POST /bookings/{id}/reschedules/{rid}/decision | 双方可提议新时间，仅对方可接受或拒绝；数据库同时校验新档期 |
| POST、GET /bookings/{id}/messages；GET /me/notifications | 仅订单双方读写留言；通知按登录账号隔离 |
| POST /media/uploads；POST /media/uploads/{id}/complete | 签发单文件上传授权；完成后校验类型、大小与归属，异步生成预览图 |
| POST、GET /bookings/{id}/deliveries；POST /bookings/{id}/deliveries/{did}/files/{fid}/download-url | 摄影师发布交付；参与者读取，逐文件签发短时下载地址 |
| POST /bookings/{id}/reviews；POST /reports；POST /admin/photographers/{id}/decision | 完单客户评价、用户举报、运营审核；后台写操作留痕 |

**创建预约示例**（以下 ID 为示例）。请求为 POST /api/v1/bookings，携带 Authorization 和 Idempotency-Key。

```json
{"packageId":"pkg_01","packageVersion":3,"sourceWorkId":"work_08","startsAt":"2026-12-06T14:00:00+08:00","meetingArea":"杭州西湖区","note":"两人情侣写真，偏自然光"}
```

成功返回 HTTP 201 和 Location 响应头。

```json
{"id":"bk_01","status":"PENDING","version":1,"startsAt":"2026-12-06T14:00:00+08:00","endsAt":"2026-12-06T15:30:00+08:00","packageSnapshot":{"durationMinutes":90,"retouchedCount":9,"deliveryDays":7,"priceMinor":69900,"currency":"CNY"}}
```

**一致性与错误**：创建预约、订单动作、改期决定和交付发布均要求 Idempotency-Key；同一账号、接口和键重复提交相同请求，返回原结果，不同请求返回 409。订单动作带 expectedVersion，更新时校验。套餐已改价或改时长也返回 409，客户端展示新条件请用户重新确认。统一错误包含 code、message、requestId 和可选 fieldErrors。400 表示格式错误，401 表示登录失效，403 表示角色不允许，对无权读取的私密对象统一返回 404，409 表示档期或版本冲突，422 表示业务字段无效，429 表示限频。冲突示例：

```json
{"code":"SLOT_CONFLICT","message":"该摄影师在所选时间已有预约，请重新选择。","requestId":"req_01"}
```

## 六、实施顺序与验收

| 阶段 | 交付物 | 放行条件 |
|---|---|---|
| 1 · 原型与契约 | 从作品进入预约、摄影师接单、私密交付三条可点击流程；数据模型与 OpenAPI | 确认套餐信息、状态文案、取消和交片规则 |
| 2 · 预约主流程 | Android 双角色页面、审核后台、排班和订单 API | 两人申请重叠档期仅一单可确认；断网重试同一申请只生成一张订单；改期失败保留旧时间 |
| 3 · 履约与内测 | 订单留言、站内通知、图片上传下载、评价和举报 | 另一账号无法读取私密成片；杀进程后可恢复上传；订单历史保留原价格；在目标真机跑通从发现到收片 |

**请负责人确认三个产品决定**：① 首发采用同城付费套餐、摄影师确认接单，互免招募放二期；② 首版线下结算，在线定金与退款放二期；③ 私密交片、订单留言进入首版，逐张选片和即时聊天放二期。以上为建议，确认后再进入原型和开发。
