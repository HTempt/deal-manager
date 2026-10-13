# ModelPoolClient 实现报告

已新增：
- `backend/src/main/java/com/dealmanager/ai/AiModelProperties.java`
- `backend/src/main/java/com/dealmanager/ai/ModelPoolClient.java`

实现要点：
- `app.ai` 配置绑定，兼容 activeModel/baseUrl/apiKey/model 根配置。
- 支持 pool 列表，模型项含 id/baseUrl/apiKey/model/enabled（默认 true）。
- activeModel 优先，其余启用且配置完整的模型顺序尝试。
- 429、402、5xx、网络异常、401/403 及无效响应均切换下一个模型；全部失败返回通用中文错误，不泄漏密钥。
- 无有效 key 返回 503。
- 请求带 `enable_thinking=false`、`max_tokens=512`、temperature=0.7；拒绝空响应及 `finish_reason=length`，记录 usage.total_tokens。
- 连接超时 10 秒，读取超时不超过 60 秒，总尝试预算约 65 秒。

测试结果：执行 `mvn test -q`（Java 17）时，项目已有 `UserController.java:31` 编译错误（`YearMonth` 符号找不到），因此未能进入测试阶段；未修改该既有文件，也未修改 AiService/application.yml。
