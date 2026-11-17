# 口腔医院预约挂号系统

IDEA 打开 `backend` 作为 Maven 工程；前端位于 `frontend`。本机使用 Java 17、Spring Boot 2.7.18、Tomcat 9.0.124、MySQL
8.0.46。Spring Boot 2.7 与 Tomcat 9 匹配。

## 已实现

- Vue 3 + Vue Router 4 + Element Plus + Axios 前端：患者挂号/账单/记录/个人资料/档案/收藏/评价/反馈/知识库问答/推荐，医生资料/叫号/接诊/授权档案查看/记录草稿/修订/请假/评价回复与申诉，管理员科室/诊室/医生/排班/号量/账号/价目/停诊调度/请假审核/反馈/评价申诉/知识库/业务参数/统计。ECharts
  显示预约统计。
- JWT：HS256 签名，`iss`、`aud`、`sub`、`jti`、`iat`、`exp` 校验；数据库会话用于退出和账号停用后的即时撤销。首次创建的管理员与医生须改密。
- MySQL Flyway V1–V12、模拟支付与退款、签到与接诊、诊疗账单、管理员操作审计。
- 独立 MySQL 上的并发验收测试：重叠排班、最后号源、同患者重叠时段、幂等键、支付截止与超时、重复取消退款、记录重复提交、数据归属、账号禁用和号量下限。

## 第二阶段进度

- 患者在「我的预约」为符合条件的已支付预约选择同价时段，在「站内消息」查看预约确认、停诊、改约和退款通知。
- 管理员在「排班与签到」提交停诊原因，并在「停诊调度」刷新队列，为待处理预约改约或取消退款。后台默认每 5 秒扫描停诊排班。
- 患者健康档案采用版本校验防覆盖；收藏操作幂等；完成就诊且结清账单后可评价。匿名医生视图不包含患者 ID、预约编号或精确就诊时间。反馈回复会生成站内消息。
- 医生请假与管理员审核互斥于重叠的已发布排班。每日 18:00（医院时区）生成次日就诊和复诊提醒，重复执行去重，取消与改约撤销未读旧提醒。
- 知识库只检索管理员发布的版本；未命中时给人工咨询指引。当前本地演示未接入可选模型，来源为 FAQ、安全提示或降级回答。推荐按匹配、可约号源、评价和历史偏好计算，满号医生退出候选集。
- 就诊记录支持草稿、正式提交及修订快照。统计接口返回预约趋势、科室分布、利用率、取消率、爽约率、医生工作量、时段热度和复诊人数。
- 医生可以对评价发起一次申诉，管理员审核批准后隐藏评价并从公开评分中移除。患者与医生可更新本人资料；接诊医生只在签到或接诊中读取该患者必要的健康档案。
- 管理员可维护预约窗口、每日上限、待支付保留时长、取消与签到时间窗、排班时段长度；更新使用版本校验并记录审计，新建预约和后续发布排班读取最新值。
- 密码找回和患者更换手机号使用 8 位、5 分钟、单次验证码；验证码摘要经服务端密钥 HMAC 计算，最多尝试 5 次，并限制申请频率。当前只配置本机私有文件投递且请求来源须为回环地址；上线前须接入真实短信服务。验证码和密钥不写入 Git。
- 患者和医生可上传 JPEG、PNG、WebP 头像。服务端解码检查格式、尺寸和大小，统一重编码为 JPEG，存于应用目录之外；头像仅本人可取。医生、号源、患者预约/账单/病历和医生预约支持常用筛选。
- 部署时 Flyway 自动应用 V11–V12。范围见 [开发文档.md](开发文档.md)，验收见 [验收记录.md](验收记录.md)。

## 构建与运行

1. 在 MySQL 执行 [create_database.sql](database/create_database.sql)，得到 `dental_hospital` 空库。首次启动由 Flyway 自动建表。
2. 在 `frontend` 运行 `npm ci`、`npm run build`，将 `dist` 内容复制到 `backend/src/main/resources/static`
   。可直接运行 [build-war.ps1](scripts/build-war.ps1) 完成前后端构建。
3. 设置运行进程的 `DB_USER`、`DB_PASSWORD`、`JWT_SECRET`；JWT 密钥至少 32 字节。首次启动空库还须设置 `ADMIN_USERNAME`、
   `ADMIN_PHONE`、`ADMIN_PASSWORD`。不要把这些值写入仓库。首次初始化后管理员账户存于数据库。
4. 将 `backend/target/hospital.war` 复制到 Tomcat 9 的 `webapps`，启动 Tomcat。当前本机安装路径为
   `D:\workspaces\apache-tomcat-9.0.124`；访问 [http://localhost:8080/hospital/](http://localhost:8080/hospital/)
   。前端仅在页面内存中保留令牌，刷新页面需重新登录。

本机演示可用 `scripts/start-local-tomcat.ps1` 启动 Tomcat。首次运行时先在当前 PowerShell 会话设置 `DB_PASSWORD`，再执行 `scripts/start-local-tomcat.ps1 -Initialize`；脚本将数据库密码、JWT 密钥和验证码密钥用当前 Windows 用户的 DPAPI 加密存到 `%LOCALAPPDATA%\DentalHospital\secrets`。后续直接运行脚本。验证码写入 `%LOCALAPPDATA%\DentalHospital\verification-outbox`，头像写入相邻的 `avatars` 目录；请仅用虚构手机号测试。该本机投递模式只允许回环地址访问验证码接口，不能用于公网短信发送。

重新生成 `JWT_SECRET` 会使旧 JWT 失效。Tomcat 由命令行启动时会继承该命令行进程的环境变量。开发环境可在 `frontend` 运行
`npm run dev`，Vite 将 `/api` 转发到本机 Tomcat。

## 验证

在 `backend` 运行 `mvnw.cmd verify`。真实 MySQL 并发测试默认跳过，避免误用日常数据库；先创建独立的 `dental_hospital_test`
空库，再设置 `RUN_DB_TESTS=true`、`TEST_DB_PASSWORD`（以及可选的 `TEST_DB_URL`、`TEST_DB_USER`），运行 `mvnw.cmd test`
。测试会在独立库执行 Flyway 并写入虚构数据。

本机已经在 MySQL 8.0.46 上通过 33 项并发与功能验收，并在 Tomcat 9.0.124 上完成 WAR 启动和 Vue 页面、JWT 登录验证。首次演示账号的初始化密码请在登录后立即修改。
