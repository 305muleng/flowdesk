# FlowDesk

FlowDesk 是一个面向研发团队的项目、任务与交付协作平台。

项目以真实研发协作流程为背景，围绕项目生命周期、任务流转、成员协作、交付审核、项目验收、权限控制和操作审计进行设计。

当前已完成 Backend V1，后续将继续扩展前端、容器化部署、CI/CD 和 AI 协作能力。

## 核心亮点

- 基于 JWT + Interceptor 实现身份认证
- 系统级角色与项目级角色分离的 RBAC 权限模型
- 项目、任务、任务申请、项目验收等完整状态机设计
- 使用事务保证关键业务操作与审计日志一致性
- 统一异常处理与 HTTP 状态码规范
- Swagger / OpenAPI 接口文档
- JUnit + Mockito + MockMvc 自动化测试
- 为后续 RAG、Agent Tool Calling 和 AI 权限控制预留业务基础

## 技术栈

Backend

```text
Java 17
Spring Boot 4
Spring MVC
MyBatis-Plus
MySQL
Maven
```

Authentication & Authorization

```text
JWT
BCrypt
HandlerInterceptor
ThreadLocal UserContext
RBAC
```

Engineering

```text
Jakarta Validation
Global Exception Handling
Transaction Management
Operation Audit
Swagger / OpenAPI
```

Testing

```text
JUnit
Mockito
MockMvc
```

## 系统架构

```text
Client
  │
  ▼
Interceptor
  │
  ├── JWT Authentication
  └── System Permission Check
  │
  ▼
Controller
  │
  ▼
Service
  │
  ├── Business Rules
  ├── State Machine
  ├── Project Permission
  ├── Transaction
  └── Operation Audit
  │
  ▼
Mapper
  │
  ▼
MySQL
```

## 权限模型

FlowDesk 将权限拆分为两个层级。

系统角色：

```text
SYSTEM_ADMIN
USER
```

项目角色：

```text
PROJECT_MANAGER
DEVELOPER
```

项目角色属于用户与项目之间的成员关系。

因此，同一个用户可以：

```text
Project A → PROJECT_MANAGER

Project B → DEVELOPER
```

SYSTEM_ADMIN 也不会自动获得所有项目的项目负责人权限，只拥有明确授权的系统级能力。

## 核心业务

### 项目生命周期

```text
PREPARING
    ↓
IN_PROGRESS
    ↓
PENDING_ACCEPTANCE
    ↓
COMPLETED
    ↓
ARCHIVED
```

项目还可以根据业务状态进入：

```text
CANCELLED
```

支持：

- 创建与启动项目
- 成员管理与邀请
- 项目取消
- 项目验收
- 项目归档

### 任务生命周期

```text
TODO
 ↓
IN_PROGRESS
 ↓
REVIEW
 ├── APPROVE → DONE
 └── REJECT  → IN_PROGRESS
```

支持：

- 创建任务
- 分配与重新分配负责人
- 启动任务
- 提交开发成果
- 审核任务
- 取消任务
- 任务评论

Backend V1 中 `DONE` 为终态。

### 任务申请

开发人员可以主动提交任务申请：

```text
PENDING
├── APPROVED
├── REJECTED
└── CANCELLED
```

申请通过后自动生成正式 Task。

### 项目验收

项目负责人可以提交项目验收。

提交前系统会检查：

- 是否存在未完成任务
- 是否存在待处理任务申请
- 当前项目状态是否允许验收

SYSTEM_ADMIN 负责最终审核：

```text
APPROVE
→ COMPLETED

REJECT
→ IN_PROGRESS
```

## JWT 与用户状态

JWT 中仅保存用户 ID。

受保护请求到达系统后：

```text
JWT
 ↓
解析 userId
 ↓
查询当前数据库用户
 ↓
检查用户状态
 ↓
写入 UserContext
```

因此，即使 JWT 本身尚未过期，账号被禁用后也会在下一次请求中立即失去访问权限。

## 操作审计

系统通过 `operation_log` 记录关键业务行为，例如：

```text
CREATE_PROJECT
START_PROJECT
CREATE_TASK
ASSIGN_TASK
START_TASK
SUBMIT_TASK
APPROVE_TASK
REJECT_TASK
SUBMIT_PROJECT_ACCEPTANCE
APPROVE_PROJECT_ACCEPTANCE
CANCEL_PROJECT
ARCHIVE_PROJECT
```

日志包含：

- 操作用户
- 目标对象
- 操作类型
- 修改前数据
- 修改后数据
- 操作时间

关键日志与业务修改在同一事务中执行。

## HTTP 错误规范

| Status | 含义 |
| --- | --- |
| 400 | 请求参数或格式不合法 |
| 401 | 身份认证失败 |
| 403 | 已认证，但无操作权限 |
| 404 | 目标资源不存在 |
| 409 | 请求与当前业务状态冲突 |
| 500 | 服务端内部异常 |

例如：

```text
非任务负责人启动任务
→ 403 Forbidden

DONE 任务再次提交
→ 409 Conflict

指定非有效项目成员为任务负责人
→ 409 Conflict
```

## 自动化测试

Backend V1 已建立 Service 与 Web 两层测试。

覆盖内容包括：

- 项目状态流转
- 任务状态流转
- 任务申请
- 项目验收
- JWT
- GlobalExceptionHandler
- LoginInterceptor
- SystemAdminInterceptor
- HTTP 状态码
- 权限边界
- 关键异常场景

部分典型场景：

```text
无 JWT → 401

无效 JWT → 401

账号被禁用 → 403

普通用户访问管理员接口 → 403

非任务负责人启动任务 → 403

DONE 任务再次提交 → 409

非项目有效成员被指定为负责人 → 409

开发人员审核任务申请 → 403

存在未完成任务时提交验收 → 409
```

Backend V1 当前全量自动化测试通过。

## API 文档

项目集成 Swagger / OpenAPI。

启动项目后访问：

```text
http://localhost:8080/swagger-ui.html
```

支持 Bearer JWT Authorization，可直接调试受保护接口。

## 项目结构

```text
src/main/java/com/flowdesk

├── common
├── config
├── context
├── controller
├── dto
├── exception
├── interceptor
├── mapper
├── model
├── service
├── util
└── vo
```

## 当前版本

### v1.0-backend

Backend V1 已完成并冻结。

已实现：

```text
用户认证
项目管理
成员邀请
项目级 RBAC
任务生命周期
任务申请
任务提交与审核
项目验收
操作审计
Swagger
自动化测试
统一异常处理
```

Git Tag：

```text
v1.0-backend
```

## Roadmap

### Frontend

使用轻量级 Web UI 展示完整业务流程：

```text
登录
项目管理
成员管理
任务管理
任务审核
项目验收
操作日志
```

### Cloud Native

计划加入：

```text
Dockerfile
Docker Compose
MySQL 容器化
Linux 部署
GitHub Actions CI
```

目标是实现可重复构建、测试和部署。

### AI Engineering

AI 能力将在现有真实业务系统上进行扩展，而不是作为独立 Demo。

计划包括：

```text
任务内容总结
项目进展总结
智能分类
风险分析
RAG
带引用的项目问答
Agent Tool Calling
RBAC 控制的 AI 工具权限
Agent 操作审计
异步 AI 任务
AI 结果评测与失败降级
```

## 项目目标

FlowDesk 不以单纯堆砌技术栈为目标。

项目主要关注：

```text
业务建模
+
状态机设计
+
权限边界
+
异常规范
+
数据一致性
+
自动化测试
+
工程交付
+
AI 应用集成
```

目标是通过一个持续演进的真实项目，实践从业务建模、后端开发、测试、部署到 AI 应用集成的完整软件工程流程。