# DeepSeek Flash 与 LangChain 模型适配核验

核验日期：2026-10-14。结论：保留 `ChatDeepSeek` 可行，需显式指定新模型和请求参数。文档中的旧模型示例不能证明客户端只支持旧模型。本文的验证是源码与离线协议验证，尚未调用真实 DeepSeek 服务。

## 官方事实与设计含义

| 核验事项 | 证据与结论 |
| --- | --- |
| 当前模型与端点 | DeepSeek 官方首页使用 `deepseek-flash`、`https://api.deepseek.com`，明确提供 OpenAI 兼容接口。[官方首页](https://api-docs.deepseek.com/) |
| LangChain 文档的新旧差异 | 官方集成页仍用 `deepseek-chat` 示例，并有旧 `deepseek-reasoner` 限制说明；不能将这些说明套到 Flash。[LangChain 集成页](https://docs.langchain.com/oss/python/integrations/chat/deepseek) |
| `ChatDeepSeek` 是否限制模型名称 | 固定 `langchain-deepseek==1.1.1` 源码中 `model_name: str = Field(alias="model")`，继承 `BaseChatOpenAI`，创建 OpenAI SDK 的 `chat.completions` 客户端。不存在仅允许旧模型名称的校验；未知 model profile 返回空配置，而非拒绝请求。[固定发布源码](https://github.com/langchain-ai/langchain/blob/langchain-deepseek%3D%3D1.1.1/libs/partners/deepseek/langchain_deepseek/chat_models.py) |
| thinking 开关 | 官方说明默认开启 thinking；OpenAI SDK 使用 `extra_body` 传递 `thinking`。首版明确传 `{"thinking":{"type":"disabled"}}`，无需传回思考内容。[Thinking 指南](https://api-docs.deepseek.com/guides/thinking_mode/) |
| 非流式与结构化 | 官方 API 支持非流式、函数工具及命名 `tool_choice`；命名工具选择要求关闭 thinking。API 的 `response_format` 是 `text/json_object`，不能当作 OpenAI 原生 JSON Schema 保证。[Chat Completions API](https://api-docs.deepseek.com/api/create-chat-completion/) |
| Pydantic 与 strict | `with_structured_output` 支持 `function_calling`，Pydantic 输出在本地校验。首版 `strict=False`，不启用 Beta strict。不得把本地校验描述为服务端保证。[固定发布源码](https://github.com/langchain-ai/langchain/blob/langchain-deepseek%3D%3D1.1.1/libs/partners/deepseek/langchain_deepseek/chat_models.py) |

`ChatOpenAI` 指向 DeepSeek 端点也能构造相同模型请求，是可选适配方法。OpenAI 兼容性并不构成必须更换现有 `ChatDeepSeek` 方案的理由；保持窄模型接口即可。`ChatDeepSeek` 还保留提供方的 reasoning 与缓存用量处理。[固定发布源码](https://github.com/langchain-ai/langchain/blob/langchain-deepseek%3D%3D1.1.1/libs/partners/deepseek/langchain_deepseek/chat_models.py)

## 建议的显式配置

```python
from langchain_deepseek import ChatDeepSeek

model = ChatDeepSeek(
    model="deepseek-flash",
    base_url="https://api.deepseek.com",
    api_key=config.deepseek_api_key,
    streaming=False,
    max_retries=0,
    max_tokens=2048,
    extra_body={"thinking": {"type": "disabled"}},
    disabled_params={"parallel_tool_calls": None},
)

structured = model.with_structured_output(
    Decision,
    method="function_calling",
    strict=False,
    include_raw=True,
)
```

这里的 `Decision` 是本地结构化决策 Schema，不是立即执行 Java 工具。收到结构化结果后，仍由图检查动作并委托执行器。

固定客户端的 `with_structured_output(function_calling)` 默认绑定 `parallel_tool_calls=False`，但当前 DeepSeek 官方 API 参数表未列该参数；本次不假定服务端接受它。离线已验证 `disabled_params` 可移除它。首版由适配器检查 raw 消息恰好含一个合法结构化调用，再做 Pydantic 与图动作校验；不能仅依赖 SDK parser 的 `first_tool_only`，否则可能静默忽略额外调用。这是本项目的串行约束，不是服务端能力声明。[客户端父类固定源码](https://github.com/langchain-ai/langchain/blob/langchain-openai%3D%3D1.7.1/libs/partners/openai/langchain_openai/chat_models/base.py)

## 离线验证记录

独立临时虚拟环境 `/tmp/agent-deepseek-verify-20261014`，未修改项目依赖、代码或配置。实际解析版本：

| 依赖 | 版本 |
| --- | --- |
| `langchain-deepseek` | 1.1.1 |
| `langchain-openai` | 1.7.1 |
| `langchain-core` | 1.6.10 |
| `openai` | 3.31.0 |
| `pydantic` | 2.14.1 |

全部测试使用 dummy key，关闭 tracing 环境并移除 tracing 凭据；替换 socket 连接函数禁止网络连接。仅构造客户端、请求和本地 parser；未对模型执行 `invoke`，未向 HTTP 提交请求，未读取用户密钥。

已通过的断言：

1. `ChatDeepSeek(model="deepseek-flash")` 可构造，payload 保持模型名；`stream=False` 和 `extra_body.thinking.type=disabled` 保持。
2. 函数结构化包装产生命名 `Decision` 的 `tools/tool_choice`，`strict=False`；无 Beta 端点切换。
3. 用本地模拟 `AIMessage` 测试 parser：有效字段得到 Pydantic 对象，非法枚举触发 `ValidationError`。
4. 设置 `disabled_params` 后请求中不含 `parallel_tool_calls`。
5. OpenAI SDK 的离线 `_build_request` 将 extra body 合并为顶层 `thinking`；得到 URL `https://api.deepseek.com/chat/completions`、`model=deepseek-flash`、`stream=false`、`thinking.type=disabled`。仅 build，未 send。
6. `ChatOpenAI` 指向相同 DeepSeek 地址也能离线构造 Flash 请求。

本次未证明：服务端实际返回符合 Schema、真实模型行为质量、Langfuse 真实 generation、线上用量准确性、项目现有依赖与该临时环境完全兼容。实施仍须锁定最终依赖，并按已经约定的真实模型问答、草稿和澄清场景验收。
