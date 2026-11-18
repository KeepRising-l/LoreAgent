package cn.keeprising.spi;

/**
 * 工具扩展契约：所有内置工具、联网工具、MCP 外部工具都实现这个接口，做到“加工具不改内核”。
 * <p>
 * <strong>线程安全约定</strong>：ToolExecutor 在同一步可能会并发调用多个工具，
 * 所有 Tool 实现必须无状态且线程安全，禁止在实例字段中保存单次请求的临时状态。
 */
public interface Tool {

    // 工具唯一标识名（例如 calc），供模型发起调用时引用
    String name();

    // 工具能力说明，发给模型让它看懂什么时候该用这个工具
    String description();

    // 参数 JSON Schema（只用最小子集：type / required / properties / enum / items）
    String parametersSchemaJson();

    // 真正干活的方法：输入参数 JSON，返回纯文本结果（不碰 toolCallId，由外层 ToolExecutor 统一包装）
    String execute(String argumentsJson);
}
