package io.agentscope.core;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.model.OllamaChatModel;
import io.agentscope.core.model.ollama.OllamaOptions;
import io.agentscope.core.model.ollama.ThinkOption;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.method.MethodTool;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

public class SimpleOllamaCaseTest {
    @Test
    public void test() {
        // 准备工具
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new MethodTool());
        OllamaOptions options =
                OllamaOptions.builder()
                        .numCtx(4096)
                        .temperature(0.8)
                        .topK(40)
                        .topP(0.9)
                        .repeatPenalty(1.1)
                        .thinkOption(ThinkOption.ThinkBoolean.ENABLED)
                        .build();
        // 可通过环境变量 OLLAMA_MODEL 指定模型
        String modelName = System.getenv().getOrDefault("OLLAMA_MODEL", "qwen3.5:latest");
        String baseurl = System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://localhost:11434");
        OllamaChatModel model =
                OllamaChatModel.builder()
                        .modelName(modelName)
                        .baseUrl(baseurl)
                        .defaultOptions(options)
                        .build();
        // ANSI 颜色代码
        final String RESET = "\u001B[0m";
        final String CYAN = "\u001B[36m"; // 思考过程颜色
        final String GREEN = "\u001B[32m"; // 回复内容颜色
        final String YELLOW = "\u001B[33m"; // 工具调用颜色
        final String DIM = "\u001B[2m"; // 暗淡效果
        // 创建流式输出 Hook - 实时显示 thinking 减少用户感知的卡顿
        Hook streamingHook =
                new Hook() {
                    private boolean inThinking = false;
                    private boolean thinkingHeaderPrinted = false;
                    private boolean hasThinking = false; // 标记是否有 thinking 内容

                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof ReasoningChunkEvent e) {
                            Msg chunkMsg = e.getIncrementalChunk();
                            ContentBlock block = chunkMsg.getFirstContentBlock();
                            // 处理思考块（ThinkingBlock）- 流式显示思考过程
                            if (block instanceof ThinkingBlock tb) {
                                String thinking = tb.getThinking();
                                if (thinking != null && !thinking.isBlank()) {
                                    hasThinking = true;
                                    if (!thinkingHeaderPrinted) {
                                        System.out.println(
                                                CYAN
                                                        + "╭─ 思考过程 ─────────────────────────╮"
                                                        + RESET);
                                        thinkingHeaderPrinted = true;
                                    }
                                    inThinking = true;
                                    // 直接打印思考内容，让用户实时看到进展
                                    System.out.print(DIM + thinking + RESET);
                                }
                            }
                            // 处理文本块（TextBlock）
                            else if (block instanceof TextBlock tb) {
                                String content = tb.getText();
                                if (content != null && !content.isBlank()) {
                                    if (inThinking) {
                                        System.out.println(
                                                CYAN
                                                        + "╰────────────────────────────────────╯"
                                                        + RESET);
                                        System.out.println();
                                        inThinking = false;
                                    }
                                    System.out.print(GREEN + content + RESET);
                                }
                            }
                            // 处理工具调用块 - 显示"正在思考..."提示
                            else if (block instanceof io.agentscope.core.message.ToolUseBlock) {
                                if (!hasThinking && !thinkingHeaderPrinted) {
                                    // 模型不支持 thinking 时，显示简单提示
                                    System.out.println(DIM + "⏳ 正在处理..." + RESET);
                                    thinkingHeaderPrinted = true;
                                }
                            }
                        } else if (event instanceof PreActingEvent e) {
                            // 工具调用开始
                            System.out.println(
                                    YELLOW + "\n🔧 调用工具: " + e.getToolUse().getName() + RESET);
                        } else if (event instanceof PostActingEvent e) {
                            // 工具调用结束
                            String resultText =
                                    e.getToolResult().getOutput().stream()
                                            .filter(block -> block instanceof TextBlock)
                                            .map(block -> ((TextBlock) block).getText())
                                            .findFirst()
                                            .orElse("");
                            System.out.println(YELLOW + "📤 工具结果: " + resultText + RESET + "\n");
                        }
                        return Mono.just(event);
                    }

                    @Override
                    public int priority() {
                        return 50;
                    }
                };
        ReActAgent agent =
                ReActAgent.builder()
                        .name("Assistant")
                        .model(model)
                        .sysPrompt("现在你是一名非常有用的助手。是全能知识的，东莞本地地头蛇。")
                        .toolkit(toolkit)
                        .hook(streamingHook)
                        .build();
        // 发送消息
        Msg msg = Msg.builder().textContent("现在几点了？").build();
        // 使用流式输出
        System.out.println("=== 开始流式输出 ===\n");
        // 方式1: 使用 call() 方法，Hook 会自动处理流式输出
        Msg response = agent.call(msg).block();
        System.out.println("\n\n=== 流式输出完成 ===");
    }
}
