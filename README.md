# 口腔医院预约挂号系统 · 第一阶段

IDEA 打开 `backend` 作为 Maven 工程；前端位于 `frontend`。本机使用 Java 17、Spring Boot 2.7.18、Tomcat 9.0.124、MySQL 8.0.46。Spring Boot 2.7 与 Tomcat 9 匹配。

## 已实现

- Vue 3 + Vue Router 4 + Element Plus + Axios 前端：患者挂号/账单/记录，医生叫号/接诊/记录，管理员科室/诊室/医生/排班/号量/账号/价目/基础统计。ECharts 显示预约统计。
- JWT：HS256 签名，`iss`、`aud`、`sub`、`jti`、`iat`、`exp` 校验；数据库会话用于退出和账号停用后的即时撤销。首次创建的管理员与医生须改密。
- MySQL Flyway V1/V2、模拟支付与退款、签到与接诊、诊疗账单、基础统计、管理员操作审计。
- 独立 MySQL 上的并发验收测试：重叠排班、最后号源、同患者重叠时段、幂等键、支付截止与超时、重复取消退款、记录重复提交、数据归属、账号禁用和号量下限。

第二阶段的改约、停诊调度、AI、评价等功能仍按 [开发文档.md](开发文档.md) 的 P1 计划执行。

## 构建与运行

1. 在 MySQL 执行 [create_database.sql](database/create_database.sql)，得到 `dental_hospital` 空库。首次启动由 Flyway 自动建表。
2. 在 `frontend` 运行 `npm ci`、`npm run build`，将 `dist` 内容复制到 `backend/src/main/resources/static`。可直接运行 [build-war.ps1](scripts/build-war.ps1) 完成前后端构建。
3. 设置运行进程的 `DB_USER`、`DB_PASSWORD`、`JWT_SECRET`；JWT 密钥至少 32 字节。首次启动空库还须设置 `ADMIN_USERNAME`、`ADMIN_PHONE`、`ADMIN_PASSWORD`。不要把这些值写入仓库。首次初始化后管理员账户存于数据库。
4. 将 `backend/target/hospital.war` 复制到 Tomcat 9 的 `webapps`，启动 Tomcat。当前本机安装路径为 `D:\workspaces\apache-tomcat-9.0.124`；访问 [http://localhost:8080/hospital/](http://localhost:8080/hospital/)。前端仅在页面内存中保留令牌，刷新页面需重新登录。

重新生成 `JWT_SECRET` 会使旧 JWT 失效。Tomcat 由命令行启动时会继承该命令行进程的环境变量。开发环境可在 `frontend` 运行 `npm run dev`，Vite 将 `/api` 转发到本机 Tomcat。

## 验证

在 `backend` 运行 `mvnw.cmd verify`。真实 MySQL 并发测试默认跳过，避免误用日常数据库；先创建独立的 `dental_hospital_test` 空库，再设置 `RUN_DB_TESTS=true`、`TEST_DB_PASSWORD`（以及可选的 `TEST_DB_URL`、`TEST_DB_USER`），运行 `mvnw.cmd test`。测试会在独立库执行 Flyway 并写入虚构数据。

本机已经在 MySQL 8.0.46 上通过并发验收，并在 Tomcat 9.0.124 上完成 WAR 启动和 Vue 页面、JWT 登录验证。首次演示账号的初始化密码请在登录后立即修改。
