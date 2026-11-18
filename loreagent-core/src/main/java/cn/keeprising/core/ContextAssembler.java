package cn.keeprising.core;

import cn.keeprising.spi.Message;
import cn.keeprising.spi.Tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 上下文窗口组装与裁剪器。
 * 严格按已定策略组装：先扣工具 schema 占用 → 保留系统提示词 → 保底当前轮 →
 * 从最新历史轮往回装，并保证 assistant(tool_calls) 与对应的 tool 结果成对保留、绝不拆散。
 */
public class ContextAssembler {

    private final TokenEstimator estimator;

    public ContextAssembler(TokenEstimator estimator) {
        this.estimator = Objects.requireNonNull(estimator, "estimator 不能为空");
    }

    public List<Message> assemble(int maxPromptTokens, List<Message> messages, List<Tool> tools) {
        if (maxPromptTokens <= 0) {
            throw new IllegalArgumentException("maxPromptTokens 必须大于 0");
        }
        if (messages == null || messages.isEmpty()) {
            throw new Failure(Failure.ErrorCode.INVALID_ARGUMENT, "对话消息不能为空");
        }

        // 1. 先扣掉工具 schema 占用，不够直接报错说清超了多少
        int toolTokens = estimator.estimateTools(tools);
        if (toolTokens >= maxPromptTokens) {
            int over = toolTokens - maxPromptTokens + 1;
            throw overflow(over, toolTokens, maxPromptTokens, "减少挂载工具数量或调大上下文预算");
        }

        // 2. 分离前导 system 消息，system 是规则底座必须保留
        int firstNonSystem = 0;
        while (firstNonSystem < messages.size() && "system".equals(messages.get(firstNonSystem).role())) {
            firstNonSystem++;
        }
        List<Message> systemMsgs = messages.subList(0, firstNonSystem);
        List<Message> chatMsgs = messages.subList(firstNonSystem, messages.size());
        if (chatMsgs.isEmpty()) {
            throw new Failure(Failure.ErrorCode.INVALID_ARGUMENT, "缺少 user 对话消息");
        }

        int systemTokens = sumTokens(systemMsgs);
        int fixedTokens = toolTokens + systemTokens;
        if (fixedTokens >= maxPromptTokens) {
            int over = fixedTokens - maxPromptTokens + 1;
            throw overflow(over, fixedTokens, maxPromptTokens, "精简系统提示词或减少工具数量");
        }

        // 3. 把当前轮（最后一个 user 及之后产生的 tool_calls/tool 链）切出来，它必须完整装下
        int currentTurnStart = findLastUserIndex(chatMsgs);
        List<Message> currentTurn = chatMsgs.subList(currentTurnStart, chatMsgs.size());
        int currentTurnTokens = sumTokens(currentTurn);
        int neededNow = fixedTokens + currentTurnTokens;
        if (neededNow > maxPromptTokens) {
            int over = neededNow - maxPromptTokens;
            throw overflow(over, neededNow, maxPromptTokens, "缩短当前提问、开启新会话或调大上下文预算");
        }

        // 4. 剩下的预算留给历史轮次：先按“tool_call + tool_result 成对”切块，再从新往旧装
        int remaining = maxPromptTokens - neededNow;
        List<List<Message>> historyBlocks = groupAtomicBlocks(chatMsgs.subList(0, currentTurnStart));
        List<List<Message>> keptReversed = new ArrayList<>();
        for (int i = historyBlocks.size() - 1; i >= 0; i--) {
            List<Message> block = historyBlocks.get(i);
            int cost = sumTokens(block);
            if (cost > remaining) {
                // 遇到装不下的较早一轮就立刻停，防止跳过中间轮次造成上下文断层
                break;
            }
            keptReversed.add(block);
            remaining -= cost;
        }
        Collections.reverse(keptReversed);

        List<Message> assembled = new ArrayList<>(messages.size());
        assembled.addAll(systemMsgs);
        for (List<Message> block : keptReversed) {
            assembled.addAll(block);
        }
        assembled.addAll(currentTurn);
        return List.copyOf(assembled);
    }

    private int findLastUserIndex(List<Message> chatMsgs) {
        for (int i = chatMsgs.size() - 1; i >= 0; i--) {
            if ("user".equals(chatMsgs.get(i).role())) {
                return i;
            }
        }
        return 0;
    }

    // 把 assistant(tool_calls) 和紧跟它的 tool 结果绑成一块，裁剪时要么整块留下要么整块丢掉
    private List<List<Message>> groupAtomicBlocks(List<Message> history) {
        List<List<Message>> blocks = new ArrayList<>();
        int i = 0;
        while (i < history.size()) {
            Message msg = history.get(i);
            if ("assistant".equals(msg.role()) && !msg.toolCalls().isEmpty()) {
                List<Message> pair = new ArrayList<>();
                pair.add(msg);
                int j = i + 1;
                while (j < history.size() && "tool".equals(history.get(j).role())) {
                    pair.add(history.get(j));
                    j++;
                }
                blocks.add(pair);
                i = j;
            } else {
                blocks.add(List.of(msg));
                i++;
            }
        }
        return blocks;
    }

    private int sumTokens(List<Message> msgs) {
        int total = 0;
        for (Message msg : msgs) {
            total += estimator.estimateMessage(msg);
        }
        return total;
    }

    private Failure overflow(int over, int needed, int limit, String advice) {
        return new Failure(Failure.ErrorCode.CONTEXT_OVERFLOW,
                "上下文超出上限 " + over + " tokens（当前需求 " + needed + "，上限 " + limit + "）。建议：" + advice);
    }
}
