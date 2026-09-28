package com.mindtrace.service;

import com.mindtrace.agent.AgentService;
import com.mindtrace.deepseek.DeepSeekService;
import com.mindtrace.dto.AgentDtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事务边界护栏。
 *
 * <p>下面这三个入口内部都有**真实 AI 调用**（几秒到几十秒）。一旦给它们加上
 * {@code @Transactional}，数据库连接会在整个 AI 调用期间被占住 —— Hikari 默认池只有
 * 10 个连接，十来个并发对话就能把池打满。
 *
 * <p>危险之处在于这种回归**功能测试完全看不出来**：分数、奖励、成就全都照常正确，
 * 只有并发压力下才会暴露。实测对照（桩 AI 固定延迟 5 秒，采样 MySQL 未结束事务数）：
 * 加回 {@code @Transactional} 时 5/5 个样本观测到 1 个横跨 AI 调用的事务，去掉后 0/5。
 *
 * <p>所以用反射把「这几个入口不许加事务」钉死：将来谁无意加回来，这个测试会立刻失败。
 */
class TransactionBoundaryTest {

    @Test
    @DisplayName("含 AI 调用的入口不得带 @Transactional（否则连接会被长期占用）")
    void aiEntryPointsAreNotTransactional() throws Exception {
        assertNotTransactional(ChatService.class,
                ChatService.class.getDeclaredMethod("chat", Long.class, Long.class, AgentDtos.ChatRequest.class));
        assertNotTransactional(ReasoningService.class,
                ReasoningService.class.getDeclaredMethod("analyze", Long.class, Long.class,
                        AgentDtos.ReasoningRequest.class));
        assertNotTransactional(GameService.class,
                GameService.class.getDeclaredMethod("submit", Long.class, Long.class,
                        AgentDtos.SubmitRequest.class));
    }

    @Test
    @DisplayName("AI 层自身不得带 @Transactional（否则调用方会被隐式圈进事务）")
    void aiLayerItselfIsNotTransactional() {
        assertNoTransactionalAtAll(AgentService.class);
        assertNoTransactionalAtAll(DeepSeekService.class);
    }

    @Test
    @DisplayName("GameService 用 TransactionTemplate 显式圈出落库事务，而不是整个方法带事务")
    void gameServiceUsesTransactionTemplate() {
        boolean hasTemplate = Arrays.stream(GameService.class.getDeclaredFields())
                .anyMatch(field -> field.getType().equals(TransactionTemplate.class));
        assertTrue(hasTemplate,
                "GameService 应通过 TransactionTemplate 把落库段（发奖励/统计/成就/排行榜）圈进事务，"
                        + "而不是让整个 submit 带 @Transactional —— 后者会把 AI 调用也圈进去");
    }

    private void assertNotTransactional(Class<?> owner, Method method) {
        assertFalse(method.isAnnotationPresent(Transactional.class),
                owner.getSimpleName() + "." + method.getName() + " 带上了 @Transactional —— "
                        + "AI 调用期间数据库连接会被占住，并发时耗尽连接池");
    }

    /** 类级或任一方法级都不许出现 @Transactional —— 否则调用方会被隐式圈进事务。 */
    private void assertNoTransactionalAtAll(Class<?> owner) {
        assertFalse(owner.isAnnotationPresent(Transactional.class),
                owner.getSimpleName() + " 类上带了 @Transactional —— 它内部的 AI 调用会被圈进事务");
        for (Method method : owner.getDeclaredMethods()) {
            assertFalse(method.isAnnotationPresent(Transactional.class),
                    owner.getSimpleName() + "." + method.getName() + " 带上了 @Transactional —— "
                            + "所有调用方都会被隐式圈进事务");
        }
    }
}
