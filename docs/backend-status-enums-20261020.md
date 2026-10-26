# 后端业务状态枚举化（2026-10-20）

只改变后端状态的代码表达，未调整业务流程、角色、优先级、通知类型或操作类型。未改前端、README、依赖、SQL/数据库结构、真实数据库或 Redis；没有启动、停止、重启任何服务，没有 Git 写操作。

## 状态清单及落点

当前源码没有可复用的业务状态枚举。结构中的状态列仍为 VARCHAR，合法值由现有写入、条件更新和校验共同确认。以下枚举分别位于 `src/main/java/com/flowdesk/enums/`，没有因相同拼写合并不同业务。

| 业务、实体及数据库字段 | 枚举 | 保留的编码 |
| --- | --- | --- |
| Project / project.status | ProjectStatus | PREPARING、IN_PROGRESS、PENDING_ACCEPTANCE、COMPLETED、ARCHIVED、CANCELLED |
| Task / task.status | TaskStatus | TODO、IN_PROGRESS、REVIEW、DONE、CANCELLED |
| ProjectAcceptance / project_acceptance.review_status | ProjectAcceptanceStatus | PENDING、APPROVED、REJECTED |
| TaskSubmission / task_submission.review_status | TaskSubmissionStatus | PENDING、APPROVED、REJECTED |
| ProjectInvitation / project_invitation.status | ProjectInvitationStatus | PENDING、ACCEPTED、REJECTED、CLOSED |
| TaskRequest / task_request.status | TaskRequestStatus | PENDING、APPROVED、REJECTED、CANCELLED、CLOSED |
| MemberLeaveRequest / member_leave_request.status | MemberLeaveRequestStatus | PENDING、APPROVED、REJECTED、CANCELLED、CLOSED |
| ProjectManagerTransfer / project_manager_transfer.status | ProjectManagerTransferStatus | PENDING、ACCEPTED、REJECTED、CANCELLED、CLOSED |
| User / user.status | UserStatus | ACTIVE、DISABLED |
| ProjectMember / project_member.status | ProjectMemberStatus | ACTIVE、INACTIVE |

实体字段、getter/setter 改成相应枚举。Service 状态赋值、比较、集合、LambdaWrapper 及动态 Mapper 参数均作相应适配；登录拦截器也改用 UserStatus。角色、审批命令 APPROVE/REJECT、转让动作 STAY/LEAVE 均保持原字段，不属于本轮状态类型。

## 映射与兼容

- 每个枚举声明独立、稳定的 `String code`，实现当前 MyBatis-Plus 3.5.17 的 `IEnum<String>`；没有 ordinal 存储。
- `MybatisPlusConfig.statusTypeHandlers()` 只为这 10 个类型注册 `EnumTypeHandler`，覆盖 BaseMapper、LambdaWrapper、动态参数及手写实体查询的实际解析路径。
- 当前版本默认 MybatisEnumTypeHandler 对未知编码返回 null，不满足本轮约束。因此显式注册 MyBatis 内置 EnumTypeHandler：按名称写入和查找，当前名称与 code 一致；合法 SQL NULL 返回 null，未知非空编码抛 IllegalArgumentException，由 MyBatis 包装为映射异常。没有 UNKNOWN、默认值、大小写修正或猜测回填。
- `fromCode` 同样严格；null 保留为 null，未知值抛 IllegalArgumentException。聚合投影 TaskStatusCountDTO 保留 String，在 ProjectTaskSummaryService 进入业务统计前转换；非法值和 null 仍返回原有 500“任务状态数据异常”，不会忽略或写缓存。
- VO、请求筛选仍为 String，实体装配 VO 时显式取 code 并保留 null。`ALL` 是查询筛选哨兵，`EXPIRED` 是时间派生展示值，不是实体持久化状态。
- 枚举 JSON 使用独立的 `@JsonValue`/`@JsonCreator`；数据库映射不替代 JSON 配置。当前 HTTP 入参没有改成枚举，原有非法筛选的 400 和规范化行为保留。
- 审计 Map 中的状态显式保存 code 字符串，包括固定前后状态及旧状态变量；没有向 Map 塞入枚举对象。通知字段和统计缓存 VO/JSON 定义未改，缓存失效位置未改。
- Mapper 注解 SQL 的固定编码保留为数据库边界字面量；SQL 条件、FOR UPDATE、有效期和更新条数校验不变。项目中没有 Mapper XML 需要适配。

## 修改文件与原有工作

共修改 51 个现有 Java 文件，新增 10 个枚举、1 个处理器、2 个测试类及本记录。逐文件清单见本地忽略目录 `target/status-enums/edit-files.json`。

- 实体 10 个：表中对应的 model 类。
- Mapper 7 个：MemberLeaveRequestMapper、ProjectAcceptanceMapper、ProjectInvitationMapper、ProjectMapper、TaskMapper、TaskRequestMapper、TaskSubmissionMapper；只变动态状态参数类型，SQL 编码和条件保留。
- 配置/认证：MybatisPlusConfig、LoginInterceptor。
- Service 13 个：AdminUserService、DisabledMemberNotificationService、MemberLeaveRequestService、NotificationService、ProjectAcceptanceService、ProjectInvitationService、ProjectManagerTransferService、ProjectPermissionService、ProjectService、ProjectTaskSummaryService、TaskRequestService、TaskService、UserService。
- 现有测试 19 个：AdminUserServiceTest、ClosedRequestBehaviorTest、DisabledMemberNotificationTest、ListPaginationServiceTest、MemberLifecycleRegressionTest、NotificationServiceTest、PendingRequestAvailabilityTest、ProjectAcceptanceConcurrencyIntegrationTest、ProjectAcceptanceServiceTest、ProjectInvitationServiceTest、ProjectRequestClosureTransactionTest、ProjectServiceTest、ProjectTaskSummaryServiceTest、SecurityInterceptorWebTest、TaskHandoverTest、TaskRequestServiceTest、TaskServiceTest、UserMapperTest、UserServiceTest。外部环境测试只适配夹具以保证编译，没有执行。
- 新增 StatusCodeMappingTest、StatusJsonCompatibilityTest；另在现有测试中补充字符串审计载荷、项目详情 String/null 转换和非法聚合状态回归断言。

开始时仅 ProjectService.java、ProjectServiceTest.java 有已跟踪未提交修改，另有未跟踪 AI 实验目录。原取消修复完整保留：普通负责人权限检查 → 项目 FOR UPDATE → 当前项目成员关系 FOR UPDATE 后复核负责人权限。成员关系锁不是用户账号锁。

原 `cancelProjectMustRecheckManagerAfterLock`、`nonManagerCannotProbeProjectExistence` 均保留并通过：只把实体夹具及对应状态断言改成 ProjectStatus，保留 403、锁顺序及拒绝后零写入/零后续查询断言。未删除测试或退回旧实现。

## 验证结果与限制

使用本机已有 Maven 和依赖缓存，离线命令前缀为：

```text
mvn.cmd -o -s target/offline-settings.xml -Dmaven.repo.local=D:\mvn_repo
```

1. `-DskipTests test-compile`：生产代码及全部测试代码编译通过，含未执行的外部环境集成测试。
2. `-Dtest=<target/status-enums/unit-classes.txt 中明确列出的 31 个无外部环境类> test`：357 测试通过，失败/错误/跳过均为 0；没有运行完整 mvn test。
3. StatusCodeMappingTest：实际注册处理器、BaseMapper insert/updateById、BaseMapper/手写 SELECT 的实际结果处理器、LambdaWrapper 与任务条件更新参数绑定。10 类全部编码、NULL、未知编码、名称/序号/CallableStatement 读取均覆盖；JDBC 对象是 Mockito 替身，没有执行数据库 SQL。
4. StatusJsonCompatibilityTest：实际 Jackson 3 序列化/读取，10 类原编码和非法编码；13 个 VO 的 Result JSON 属性、字符串及 null；MockMvc 验证任务输出状态字符串及真实筛选校验的 400。MockMvc 不是已启动服务的接口运行验证。
5. 既有请求关闭、事务代理、成员历史、禁用通知、交接、分页、权限和统计测试通过；这些模拟测试不能证明真实 InnoDB 回滚或竞争。
6. 只整理本轮 64 个 Java 文件，使用已有本地 Java 格式工具；最终重复格式检查无变化、token 差异为空。首次格式化只调整空白及冗余括号，验收公式运算顺序不变。`git diff --check` 通过。
7. 残留审查：SQL 固定编码、API 筛选集合/ALL、EXPIRED 派生值、VO 字符串、聚合 DTO 原始字符串、审计快照字符串为有意保留的边界；没有留下 String.equals(entityEnum)、Set<String>.contains(entityEnum) 或实体字符串断言。

实施过程中曾有编译失败（测试夹具/Matcher 尚未适配）、12 个旧字符串断言失败，及新增 updateById 测试对“构建阶段已经是具体处理器”的错误假设。均修正并重新通过；updateById 的 Map 参数实际先使用 UnknownTypeHandler，运行绑定时委派给注册的枚举处理器，测试现明确断言这一机制及最终 setString 编码，没有跳过失败用例。

真实数据库、Redis、启动服务后的接口和浏览器验证均未执行；未启动、重启或停止服务。若实际数据库已有非法状态编码，实体读取会明确失败，本轮没有访问真实数据或进行修复。枚举只能限制表示范围；合法迁移、权限和并发仍依赖保留的 Service 校验、事务、锁及条件 SQL。

结束检查：HEAD、暂存 diff 哈希与开始时一致；清单外已跟踪文件哈希未变，前端、README、依赖和资源配置未变；数据库结构无 Git 差异；AI 目录未读取或修改。没有创建分支、提交或执行其他 Git 写操作。

## 2026-10-21 最新范围调整

枚举化及原取消权限修复已恢复，JwtUtilTest保留真实签名/解析断言并解除无关Spring依赖。曾在临时专用MySQL/Redis环境实际执行366项全部通过（含截图9项），这是该次运行的历史证据。用户随后明确只要求代码修改、由其自行运行，已撤销新增Compose、测试脚本、共享IDEA配置、README环境说明及test profile连接默认值，恢复原有配置。临时环境验证不证明用户原有环境现已通过；后续以用户运行输出定位代码问题，不再自行扩建或调整环境。

## 2026-10-26：使用框架内置转换器

保留业务枚举，删除自定义转换器，为原有十种状态显式注册 MyBatis 的 EnumTypeHandler。它按枚举名称读写，现有名称与持久化编码一致；后续不得随意重命名这些枚举常量。JSON 编码约定保持不变。未知数据库编码仍会报错，空值仍返回 null。不要使用序号映射，也不要仅依赖 MyBatis-Plus 的 IEnum 自动映射替代这份严格注册。

映射回归测试同时检查名称与编码一致、合法/非法/空值读取，以及有无显式 JDBC 类型时的参数绑定。测试使用真实 MyBatis 配置和 JDBC 替身，不代表真实 MySQL 集成验证。
