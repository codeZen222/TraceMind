package org.example.tracemind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.tracemind.aiclient.PythonClient;
import org.example.tracemind.common.BizException;
import org.example.tracemind.diagnosis.DiagnosisEventPipeline;
import org.example.tracemind.es.EsLogRepository;
import org.example.tracemind.mapper.AgentEventMapper;
import org.example.tracemind.mapper.AgentSessionMapper;
import org.example.tracemind.mapper.ChatMessageMapper;
import org.example.tracemind.mapper.ProjectMapper;
import org.example.tracemind.pojo.dto.ChatRequestDTO;
import org.example.tracemind.pojo.entity.AgentSession;
import org.example.tracemind.pojo.entity.ChatMessage;
import org.example.tracemind.pojo.entity.Project;
import org.example.tracemind.service.OwnershipGuard;
import org.example.tracemind.service.StreamSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 追问流式链路 —— R-4.3 的收益证明。
 *
 * <p><b>改造前这类测试根本写不出来。</b>{@code chat} 返回 {@code SseEmitter}，
 * 要断言"发了哪些帧、什么时候终止"就得构造真的 Web 层对象并窥其内部状态；
 * 而 service 里那些真正要紧的业务规则（过程帧不落库、[DONE] 不重复终止、
 * 提交失败必须通知 sink）反而最难测。解耦后它们变成对一个普通接口的记录式断言。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DiagnosisChatStreamTest {

    private static final String SESSION_ID = "sess-4n7p1w6z";
    private static final String PROJECT_ID = "p-2jma61br";

    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private AgentSessionMapper agentSessionMapper;
    @Mock
    private AgentEventMapper agentEventMapper;
    @Mock
    private ChatMessageMapper chatMessageMapper;
    @Mock
    private EsLogRepository esLogRepository;
    @Mock
    private PythonClient pythonClient;
    @Mock
    private DiagnosisEventPipeline eventPipeline;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private OwnershipGuard ownershipGuard;

    /** 同步执行的假线程池：让"异步"逻辑在当前线程跑完，测试才是确定的 */
    private DiagnosisServiceImpl service;

    /** 记录式 sink：把三类事件收进列表，用普通集合断言 */
    private static final class RecordingSink implements StreamSink {
        final List<String> frames = new ArrayList<>();
        int doneCount;
        int failCount;
        Throwable cause;

        @Override
        public void emit(String frame) { frames.add(frame); }
        @Override
        public void done() { doneCount++; }
        @Override
        public void fail(Throwable t) { failCount++; cause = t; }

        boolean terminatedOnce() { return doneCount + failCount == 1; }
    }

    @BeforeEach
    void setUp() {
        /*
         * 归属校验会走 CurrentUser.id() → SecurityContextHolder，没有认证就抛 401。
         * ⚠️ 这正是审计里「静态 ThreadLocal 让 service 难以测试」那条的实证：每个用例都得先搭这个 fixture。
         * 这里手工塞而不用 @WithMockUser，是为了不给项目多加一个 spring-security-test 依赖。
         */
        var auth = new UsernamePasswordAuthenticationToken("u-001", "n/a", List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        // 归属校验链：agent_session 存在 → 其 projectId 对应的项目属于当前用户
        AgentSession session = new AgentSession();
        session.setSessionId(SESSION_ID);
        session.setProjectId(PROJECT_ID);
        when(agentSessionMapper.selectById(SESSION_ID)).thenReturn(session);
        Project project = new Project();
        project.setId(PROJECT_ID);
        when(projectMapper.selectByIdAndOwner(eq(PROJECT_ID), anyString())).thenReturn(project);

        service = new DiagnosisServiceImpl(
                projectMapper, agentSessionMapper, agentEventMapper, chatMessageMapper,
                esLogRepository, pythonClient, eventPipeline, redis,
                new ObjectMapper(), new DirectExecutor(), ownershipGuard);
    }

    @AfterEach
    void clearSecurityContext() {
        // ThreadLocal 不清会污染后续测试类（同 JVM 内共享）
        SecurityContextHolder.clearContext();
    }

    /**
     * 让 execute() 在调用线程内立即执行，避免等待与竞态。
     *
     * <p>⚠️ 只需实现 {@link java.util.concurrent.Executor} 即可 —— service 依赖的是抽象而非
     * {@code ThreadPoolTaskExecutor}（后者还得先 initialize()）。这正是 R-4.4
     * 「线程池类型统一」那条带来的便利。</p>
     */
    private static final class DirectExecutor implements java.util.concurrent.Executor {
        @Override
        public void execute(Runnable task) {
            task.run();
        }
    }

    private ChatRequestDTO ask(String question) {
        ChatRequestDTO dto = new ChatRequestDTO();
        dto.setQuestion(question);
        dto.setHistory(List.of());
        return dto;
    }

    private void stubPython(Flux<String> stream) {
        when(pythonClient.chat(anyString(), anyString(), anyList())).thenReturn(stream);
    }

    @Test
    @DisplayName("★ 帧序原样透传：前导空格不许被 trim（英文分词靠它）")
    void passesFramesThroughUntrimmed() {
        stubPython(Flux.just(" the", " order", " is not found", "[DONE]"));

        RecordingSink sink = new RecordingSink();
        service.chat(SESSION_ID, ask("根因是什么？"), sink);

        assertThat(sink.frames).containsExactly(" the", " order", " is not found");
        assertThat(sink.doneCount).isEqualTo(1);
        assertThat(sink.failCount).isZero();
    }

    @Test
    @DisplayName("★ [DONE] 不外发原文，且终止只发生一次")
    void doneMarkerNotEmittedAndTerminatesOnce() {
        stubPython(Flux.just("答案", "[DONE]", "不该出现的后续"));

        RecordingSink sink = new RecordingSink();
        service.chat(SESSION_ID, ask("q"), sink);

        assertThat(sink.frames).containsExactly("答案");     // [DONE] 没进 emit
        assertThat(sink.doneCount)
                .as("循环内 done() 之后不能再走循环后的 done()")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("★ Python 流自然耗尽（没有 [DONE]）也要终止一次，不能漏也不能重")
    void terminatesOnceWhenStreamEndsWithoutDoneMarker() {
        stubPython(Flux.just("只有", "两帧"));

        RecordingSink sink = new RecordingSink();
        service.chat(SESSION_ID, ask("q"), sink);

        assertThat(sink.terminatedOnce()).isTrue();
        assertThat(sink.doneCount).isEqualTo(1);
    }

    @Test
    @DisplayName("落库只拼文本帧：tool_call / tool_result 过程帧送前端但不入库")
    void persistsOnlyTextFramesIntoHistory() {
        stubPython(Flux.just(
                "{\"type\":\"text\",\"content\":\"根因是\"}",
                "{\"type\":\"tool_call\",\"tool\":\"search_logs\"}",
                "{\"type\":\"text\",\"content\":\"空指针\"}",
                "[DONE]"));

        RecordingSink sink = new RecordingSink();
        service.chat(SESSION_ID, ask("q"), sink);

        // 过程帧照样送出去（前端要显示"正在查日志…"）
        assertThat(sink.frames).hasSize(3);

        // 但落库的 assistant 回复只含正文
        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageMapper, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        List<ChatMessage> all = captor.getAllValues();
        ChatMessage reply = all.get(all.size() - 1);
        assertThat(reply.getRole()).isEqualTo("assistant");
        assertThat(reply.getContent()).isEqualTo("根因是空指针");
    }

    @Test
    @DisplayName("用户这句话<b>先</b>落库，assistant 回复在流结束后才落库")
    void persistsUserQuestionBeforeStreaming() {
        stubPython(Flux.just("答", "[DONE]"));

        service.chat(SESSION_ID, ask("为什么超时？"), new RecordingSink());

        // insert 共两次（user / assistant），所以不能用 verify(mapper).insert() —— 那要求恰好一次。
        // 这里要验的是<b>顺序</b>：user 必须在任何帧发出之前就落库。
        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageMapper, org.mockito.Mockito.times(2)).insert(captor.capture());

        List<String> rolesInOrder = captor.getAllValues().stream()
                .map(ChatMessage::getRole).toList();
        assertThat(rolesInOrder).containsExactly("user", "assistant");
        assertThat(captor.getAllValues().get(0).getContent()).isEqualTo("为什么超时？");
    }

    @Test
    @DisplayName("★ Python 抛异常 → 走 fail()，绝不静默挂住客户端")
    void routesStreamFailureToFail() {
        stubPython(Flux.error(new RuntimeException("connection refused")));

        RecordingSink sink = new RecordingSink();
        service.chat(SESSION_ID, ask("q"), sink);

        assertThat(sink.failCount).isEqualTo(1);
        assertThat(sink.doneCount).isZero();
        assertThat(sink.cause).hasMessageContaining("connection refused");
        assertThat(sink.terminatedOnce()).isTrue();
    }

    @Test
    @DisplayName("★ 线程池拒绝提交 → 也必须 fail()（否则那条 SSE 连接会挂到超时）")
    void notifiesSinkWhenTaskSubmissionRejected() {
        ThreadPoolTaskExecutor rejecting = mock(ThreadPoolTaskExecutor.class);
        doThrow(new RejectedExecutionException("pool exhausted"))
                .when(rejecting).execute(any(Runnable.class));
        DiagnosisServiceImpl svc = new DiagnosisServiceImpl(
                projectMapper, agentSessionMapper, agentEventMapper, chatMessageMapper,
                esLogRepository, pythonClient, eventPipeline, redis,
                new ObjectMapper(), rejecting, ownershipGuard);

        RecordingSink sink = new RecordingSink();
        svc.chat(SESSION_ID, ask("q"), sink);

        assertThat(sink.failCount).as("提交失败不能悄悄什么都不做").isEqualTo(1);
        assertThat(sink.doneCount).isZero();
    }

    @Test
    @DisplayName("归属校验不过时直接抛出，不产生任何帧")
    void rejectsBeforeTouchingSinkWhenNotOwner() {
        // 覆盖 setUp 的桩：让项目不属于当前用户 → requireOwnedSession 抛 404
        when(projectMapper.selectByIdAndOwner(eq(PROJECT_ID), anyString())).thenReturn(null);

        RecordingSink sink = new RecordingSink();
        assertThatThrownBy(() -> service.chat(SESSION_ID, ask("q"), sink))
                .isInstanceOf(BizException.class);

        assertThat(sink.frames).isEmpty();
        assertThat(sink.doneCount).isZero();
        assertThat(sink.failCount).isZero();
        verify(pythonClient, org.mockito.Mockito.never()).chat(anyString(), anyString(), anyList());
    }

    @Test
    @DisplayName("空问题 → 40000，且不落库不打扰 Python")
    void rejectsBlankQuestion() {
        RecordingSink sink = new RecordingSink();
        assertThatThrownBy(() -> service.chat(SESSION_ID, ask("   "), sink))
                .isInstanceOf(BizException.class);

        assertThat(sink.terminatedOnce()).isFalse();
        verify(pythonClient, org.mockito.Mockito.never()).chat(anyString(), anyString(), anyList());
    }
}
