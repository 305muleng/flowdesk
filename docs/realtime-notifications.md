# 通知实时推送与 Nginx 部署

## 通知如何到达页面

通知先保存到 MySQL。普通业务通知和到期提醒都发布 NotificationCreatedEvent，在数据库事务提交后交给独立线程池推送；事务回滚不会推送。没有外层事务时，通知单条写入提交后再发布事件。

WebSocket 只提示通知变化，数据库记录及 HTTP 查询仍是最终依据。推送队列拥塞、网络故障和离线都可能错过弹窗，连接恢复或主动刷新时重新查询。这里没有持久化的实时推送补偿队列，不承诺每个弹窗必达。

接收人由后端连接身份确定。相同账号的多个连接都会收到通知，不能通过客户端指定用户编号订阅他人的通知。

前端收到消息后重新查询未读数；通知中心在第一页时自动刷新，在历史页时保留页码并提示有更新。点击弹窗进入未读通知列表。连接恢复时也会查询最新数据，不将错过的每一条通知重新弹窗。

## 连接身份与生命周期

1. 携带现有 Authorization: Bearer 凭证调用 POST /notifications/ws-ticket。
2. 服务校验登录及当前账号状态，生成随机票据。票据保存在 Redis，最多有效 60 秒，且不超过 JWT 剩余有效期。
3. 浏览器连接 /ws/notifications?ticket=票据。握手检查允许的 Origin，使用原子的获取并删除操作消费票据，并再次核对账号状态。
4. 已验证的用户编号及 JWT 过期时间保存在服务端连接属性中，握手不接受客户端指定的用户身份。

握手完成后发送 ready，后续通知格式为：

```json
{"type":"notification.created","notificationId":123,"title":"任务分配"}
```

默认每 20 秒复查连接及发送协议 Ping，浏览器自动回应 Pong。登录过期或账号不可用时关闭连接，禁用账号的提交后事件也会主动关闭相关连接。发送通知前仍检查账号与登录有效期。

4001 表示登录过期，4003 表示账号不可用；前端停止重连并返回登录页。其他断线按 1、2、4 秒逐步退避，最长 30 秒，重连时申请新票据。退出登录和页面卸载会取消待重连任务，并忽略旧连接及旧票据请求的晚到结果。同一浏览器其他页面退出登录时，也会关闭当前页面的连接。

JWT 本身仍沿用现有失效策略，没有新增全局会话吊销。前端退出会关闭对应浏览器连接，不代表所有其他客户端持有的 JWT 都被服务器撤销。

连接列表保存在单个应用进程内。目前部署为单实例，不宣称已实现跨实例广播。Redis 不可用时暂时不能签发新票据，通知查询仍可通过 HTTP 使用。

## 本地运行

开发模式仍可使用前端 Vite 服务，/api 和 /ws 都代理到后端。默认允许以下浏览器来源：

```text
http://localhost:5173
http://127.0.0.1:5173
http://localhost:8088
http://127.0.0.1:8088
```

改变前端访问域名、协议或端口时，在启动后端前设置 FLOWDESK_WS_ALLOWED_ORIGINS，多个完整来源用逗号分隔，不填写路径。生产前端使用页面当前来源建立连接，HTTPS 页面自动使用 wss。

## Nginx 统一入口

Compose 只运行 Nginx，后端、MySQL、Redis 沿用现有启动方式，不自动创建或清空数据库。先按 README 初始化数据库、提供环境变量并启动后端，再构建前端。

在 frontend 目录执行：

```powershell
npm ci
npm run build
```

回到项目根目录执行：

```powershell
docker compose -f deploy/compose.yaml up -d --build
```

浏览器访问 http://127.0.0.1:8088/。Nginx 承载前端生产构建文件，支持前端深层路由刷新；/api/ 转发至后端并移除 /api 前缀，/ws/notifications 保留路径并设置连接升级请求头。

后端默认地址为容器可访问的 http://host.docker.internal:8080。后端使用其他地址时，在启动 Compose 前设置 FLOWDESK_BACKEND。Docker Desktop 和 Linux 主机的网络环境可能不同，目标地址必须能从 Nginx 容器访问。

外部入口默认仅绑定本机 127.0.0.1:8088，端口通过 FLOWDESK_WEB_PORT 调整。当前配置提供本地 HTTP 部署，没有直接配置公网域名和 TLS。

Nginx 访问日志只记录路径，不记录查询参数中的连接票据。WebSocket 代理读取超时为 75 秒，正常连接通过服务端 Ping 保持活跃。镜像固定到已验证的官方摘要。

停止 Nginx：

```powershell
docker compose -f deploy/compose.yaml down
```

## 验证范围

- NotificationTicketServiceTest 验证票据生命周期、原子消费、账号状态及 Redis 失败分支。
- NotificationWebSocketHandlerTest 验证接收人隔离、多连接、禁用关闭及推送队列失败。
- NotificationWebSocketIntegrationTest 使用真实 HTTP、WebSocket、MySQL 和 Redis，验证提交后推送、回滚不推送、票据重放与并发消费、过期及来源校验、账号禁用、JWT 到期、断线查询和到期提醒接入。
- realtime-notifications.test.cjs 验证前端重连、退出、旧响应隔离、消息去重、未读数竞争及历史分页。

2026-10-27 本机浏览器经 Nginx 验收：登录和深层路由正常；真实任务分配触发弹窗、未读数及第一页更新；历史页保留位置；同账号两个页面同步接收并同步退出。中断 Nginx 时保存通知，再实际重启后端与 Nginx，浏览器自动重连并查到离线记录。Nginx 日志确认连接升级为 101，访问日志未出现 ticket 查询参数。

以上重启验证针对通知 WebSocket 的恢复，不代替 Redis Streams 到期提醒的真实进程重启验收。测试数据和临时服务在结束后清理。
