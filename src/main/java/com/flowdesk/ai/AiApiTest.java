package com.flowdesk.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;


public class AiApiTest {

    public static void main(String[] args) throws Exception {
        // ===== 1. 获取 API Key =====

        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("没有读取到 API Key");
            return;
        }

        // ===== 2. 构造请求体 =====

        String requestBody = """
                {
                  "model": "qwen3.7-plus",
                  "messages": [
                    {
                      "role": "system",
                      "content": "你是项目管理助手。用户查询具体项目任务时，应先调用工具获取数据，不要编造任务。"
                    },
                    {
                      "role": "user",
                      "content": "帮我查询项目123有哪些任务"
                    }
                  ],
                  "tools": [
                    {
                      "type": "function",
                      "function": {
                        "name": "getProjectTasks",
                        "description": "根据项目ID查询任务列表",
                        "parameters": {
                          "type": "object",
                          "properties": {
                            "projectId": {
                              "type": "integer",
                              "description": "项目ID"
                            }
                          },
                          "required": ["projectId"]
                        }
                      }
                    }
                  ],
                  "tool_choice": "auto"
                }
                """;

        // ===== 3. 构造 HTTP 请求 =====

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

        // ===== 4. 发送请求，获取响应 =====

        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        System.out.println("HTTP状态码：" + response.statusCode());

        if (response.statusCode() != 200) {
            System.out.println(response.body());
            return;
        }

        // ===== 5. 解析大模型返回的 JSON =====

        ObjectMapper objectMapper = new ObjectMapper();

        JsonNode root = objectMapper.readTree(response.body());

        JsonNode toolCalls = root.get("choices").get(0).get("message").get("tool_calls");

        // 模型可能没有选择调用工具
        if (toolCalls == null || toolCalls.size() == 0) {
            System.out.println("模型没有提出工具调用");
            return;
        }

        // 取第一个工具调用
        JsonNode toolCall = toolCalls.get(0);

        // ===== 6. 获取工具名称、参数和调用 ID =====

        String toolName = toolCall.get("function").get("name").asText();

        String arguments = toolCall.get("function").get("arguments").asText();

        String toolCallId = toolCall.get("id").asText();

        // arguments 本身是一段 JSON 字符串
        JsonNode argsNode = objectMapper.readTree(arguments);

        int projectId = argsNode.get("projectId").asInt();

        System.out.println("工具名称：" + toolName);
        System.out.println("项目ID：" + projectId);

        // ===== 7. Java 真正执行工具 =====

        if ("getProjectTasks".equals(toolName)) {
            if (projectId <= 0) {
                System.out.println("项目ID不合法");
                return;
            }

            String toolResult = getProjectTasks(projectId);

            System.out.println("工具执行结果：" + toolResult);

            // ===== 8. 构造工具结果消息 =====

            ObjectNode toolMessage = objectMapper.createObjectNode();

            toolMessage.put("role", "tool");
            toolMessage.put("tool_call_id", toolCallId);
            toolMessage.put("content", toolResult);

            System.out.println("准备发送给大模型的工具结果：");
            System.out.println(toolMessage.toPrettyString());

            // 1. 解析第一次发送的请求体
            JsonNode firstRequest = objectMapper.readTree(requestBody);

            // 2. 创建第二次请求的 JSON 对象
            ObjectNode secondRequest = objectMapper.createObjectNode();

            secondRequest.put("model", "qwen3.7-plus");

            // 3. 在请求对象里创建 messages 数组
            ArrayNode messages = secondRequest.putArray("messages");

            // 4. 把最初的 system 和 user 消息放进去
            for (JsonNode message : firstRequest.get("messages")) {
                messages.add(message);
            }

            // 5. 添加模型之前提出的工具调用
            ObjectNode assistantMessage = objectMapper.createObjectNode();

            assistantMessage.put("role", "assistant");
            assistantMessage.put("content", "");
            assistantMessage.set("tool_calls", toolCalls);

            messages.add(assistantMessage);

            // 6. 添加 Java 的工具执行结果
            messages.add(toolMessage);

            // 7. 把整个 JSON 对象转换成 String
            String secondBody = objectMapper.writeValueAsString(secondRequest);

            System.out.println("第二次请求体：");
            System.out.println(secondRequest.toPrettyString());

            HttpRequest secondHttpRequest = HttpRequest.newBuilder()
                .uri(request.uri())
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(secondBody))
                .build();

            HttpResponse<String> secondResponse = client.send(secondHttpRequest, HttpResponse.BodyHandlers.ofString());

            System.out.println("第二次HTTP状态码：" + secondResponse.statusCode());

            if (secondResponse.statusCode() == 200) {
                JsonNode secondRoot = objectMapper.readTree(secondResponse.body());

                String finalAnswer = secondRoot.get("choices").get(0).get("message").get("content").asText();

                System.out.println("模型最终回答：" + finalAnswer);
            } else {
                System.out.println(secondResponse.body());
            }
        } else {
            System.out.println("不支持的工具：" + toolName);
        }
        //
        //        ObjectMapper objectMapper = new ObjectMapper();
        //
        //        JsonNode root = objectMapper.readTree(response.body());
        //
        //        String content = root
        //                .get("choices")
        //                .get(0)
        //                .get("message")
        //                .get("content")
        //                .asText();
        //
        //        System.out.println(content);
        //        System.out.println();
        //
        //        ProjectRisk projectRisk =
        //                objectMapper.readValue(content, ProjectRisk.class);
        //
        //        System.out.println(projectRisk.getRiskLevel());
        //        System.out.println(projectRisk.getReason());
        //        System.out.println();
        //
        //        JsonNode result = objectMapper.readTree(content);
        //
        //        String riskLevel = result.get("riskLevel").asText();
        //        String reason = result.get("reason").asText();
        //
        //        System.out.println(riskLevel);
        //        System.out.println(reason);
    }

    private static String getProjectTasks(int projectId) {
        return "项目" + projectId + "有3个任务：登录接口、数据库设计、用户管理";
    }
}
