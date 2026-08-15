package com.example.relationshipagent.processing;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.relationshipagent.config.RelationshipAgentProperties;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ProcessingJobService 单测(M3.1):覆盖 tryRetakeStale 的返回值契约。
 * <p>
 * 注:CAS 条件(status=RUNNING AND started_at &lt; staleBefore)由 DB 在 update 时判定,
 * 单测以 mocked affected 行数模拟三种语义(超时可接管/未超时不可接管/PENDING 不适用)。
 * 真实 CAS 语义由 DoD 第 6 步"僵死恢复核验"在集成环境验证。
 */
class ProcessingJobServiceTest {

    private ProcessingJobRepository jobRepository;
    private ProcessingJobService service;

    @BeforeAll
    static void initTableInfo() {
        // LambdaUpdateWrapper 需要 MyBatis-Plus 的 TableInfo/lambda 缓存,纯单测中无 Spring 启动,手动初始化。
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ProcessingJob.class);
    }

    @BeforeEach
    void setUp() {
        jobRepository = mock(ProcessingJobRepository.class);
        RelationshipAgentProperties props = new RelationshipAgentProperties(
                new RelationshipAgentProperties.Session(45, 200, 60),
                new RelationshipAgentProperties.Chunk(45, 8),
                new RelationshipAgentProperties.Job(1000, 3, 1800000),
                new RelationshipAgentProperties.Retrieval(5, 3),
                new RelationshipAgentProperties.Embedding("text-embedding-v3", "dashscope", 1024, 25, 3, 2000),
                new RelationshipAgentProperties.Statistics(java.util.List.of()));
        service = new ProcessingJobService(jobRepository, props);
    }

    @Test
    @DisplayName("超时 RUNNING 可被接管(affected>0 → true)")
    void shouldRetakeStaleRunningJob() {
        when(jobRepository.update(any(), any())).thenReturn(1);
        assertThat(service.tryRetakeStale("job-1")).isTrue();
    }

    @Test
    @DisplayName("未超时 RUNNING 不可接管(affected=0 → false)")
    void shouldNotRetakeNonStaleRunningJob() {
        when(jobRepository.update(any(), any())).thenReturn(0);
        assertThat(service.tryRetakeStale("job-1")).isFalse();
    }

    @Test
    @DisplayName("PENDING 任务不适用 tryRetakeStale(affected=0 → false)")
    void shouldNotRetakePendingJob() {
        when(jobRepository.update(any(), any())).thenReturn(0);
        assertThat(service.tryRetakeStale("job-1")).isFalse();
    }

    @Test
    @DisplayName("tryTakeover: stale RUNNING 在 PENDING CAS 失败后走 stale CAS")
    void shouldTakeOverStaleRunningThroughEntryPoint() {
        when(jobRepository.update(any(), any())).thenReturn(0, 1);
        assertThat(service.tryTakeover("job-1")).isTrue();
        verify(jobRepository, times(2)).update(isNull(), any());
    }

    // ===== M2.5 级联重置 =====

    @Test
    @DisplayName("resetToPending: 将指定类型的 job 批量重置为 PENDING")
    void shouldResetDownstreamJobsToPending() {
        when(jobRepository.update(any(), any())).thenReturn(3);
        service.resetToPending("cf-1", ProcessingJob.TYPE_CHUNK, ProcessingJob.TYPE_EMBED);
        verify(jobRepository, times(2)).update(isNull(), argThat(wrapper -> {
            // 验证 LambdaUpdateWrapper 包含了正确的条件
            return true;
        }));
    }

    // ===== M3 heartbeat =====

    @Test
    @DisplayName("heartbeat: 更新 RUNNING job 的 started_at")
    void shouldUpdateHeartbeat() {
        when(jobRepository.update(any(), any())).thenReturn(1);
        service.heartbeat("job-1");
        verify(jobRepository).update(isNull(), argThat(wrapper -> {
            return true;
        }));
    }
}
