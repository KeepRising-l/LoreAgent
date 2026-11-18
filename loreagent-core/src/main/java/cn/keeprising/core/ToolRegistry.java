package cn.keeprising.core;

import cn.keeprising.spi.Tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 工具通讯录：统一登记所有可用工具，按名字查找或导出完整列表给大模型。
 */
public class ToolRegistry {

    // 用 LinkedHashMap 保持注册顺序，配合同步锁保证注册和导出时线程安全
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public synchronized void register(Tool tool) {
        Objects.requireNonNull(tool, "tool 不能为空");
        String name = tool.name();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool.name 不能为空");
        }
        if (tools.containsKey(name)) {
            throw new IllegalArgumentException("工具名重复注册: " + name);
        }
        tools.put(name, tool);
    }

    // 找不到工具时直接按 INVALID_ARGUMENT 报错（常见于模型幻觉编了个不存在的工具名）
    public synchronized Tool get(String name) {
        Tool tool = tools.get(name);
        if (tool == null) {
            throw new Failure(Failure.ErrorCode.INVALID_ARGUMENT, "未知工具: " + name);
        }
        return tool;
    }

    public synchronized List<Tool> all() {
        return List.copyOf(tools.values());
    }

    public synchronized boolean isEmpty() {
        return tools.isEmpty();
    }
}
