package cn.keeprising.spi;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 大模型调用适配器 SPI（内核不直接写死 OpenAI HTTP 请求，全靠这个接口隔离）。
 * 单测时直接用几行 lambda 假实现就能跑通 AgentLoop，不用真的联网调模型。
 */
public interface LlmAdapter {

    /**
     * 发起一次流式对话请求。
     *
     * @param model     目标模型名称
     * @param messages  发给模型的完整上下文消息列表
     * @param tools     本次允许模型调用的工具列表（无工具时传空列表）
     * @param onChunk   模型每吐出一小块 StreamChunk 就调一次这个回调
     * @param cancelled 取消探针（用标准库 BooleanSupplier 避免 spi 反向依赖 core 的 Cancellation），返回 true 时立刻中断
     */
    void streamChat(
            String model,
            List<Message> messages,
            List<Tool> tools,
            Consumer<StreamChunk> onChunk,
            BooleanSupplier cancelled);
}
