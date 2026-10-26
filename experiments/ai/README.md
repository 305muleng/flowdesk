# AI 工具调用实验

此目录保留独立学习代码，不参与 FlowDesk 后端构建，也没有接入项目接口或权限链路。

使用 Java 17，在项目根目录执行：

    mvn -f experiments/ai/pom.xml compile

在 IDE 中将此目录作为独立 Maven 项目打开，设置 DASHSCOPE_API_KEY 后运行 AiApiTest.main。
运行会向模型服务发送请求并产生相应调用费用；普通项目测试不会运行它。

getProjectTasks 当前返回固定示例文本，不访问数据库，不能作为真实项目查询能力展示。
这里只验证编译，不在自动测试中调用模型服务。
