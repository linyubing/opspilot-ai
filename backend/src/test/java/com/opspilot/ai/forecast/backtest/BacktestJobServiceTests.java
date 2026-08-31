package com.opspilot.ai.forecast.backtest;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证只有成功取得数据库运行权的请求才会提交后台任务。 */
class BacktestJobServiceTests {

    private static final Instant NOW = Instant.parse("2026-08-28T08:00:00Z");

    @Test
    void submitsOnlyAfterWinningRunRight() {
        UUID id = UUID.randomUUID();
        BacktestRepository repo = mock(BacktestRepository.class);
        BacktestService service = mock(BacktestService.class);
        BacktestRunner runner = mock(BacktestRunner.class);
        RecordingExecutor executor = new RecordingExecutor();
        BacktestTask task = task(id);
        when(repo.start(id, time())).thenReturn(true, false);
        when(service.get(id)).thenReturn(task);
        BacktestJobService jobs = new BacktestJobService(
                repo, service, runner, executor,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        assertThat(jobs.start(id)).isEqualTo(task);
        assertThat(jobs.start(id)).isEqualTo(task);

        assertThat(executor.submitted).isEqualTo(1);
        executor.task.run();
        verify(runner).run(id);
    }

    @Test
    void doesNotSubmitWhenTaskIsAlreadyRunning() {
        UUID id = UUID.randomUUID();
        BacktestRepository repo = mock(BacktestRepository.class);
        BacktestService service = mock(BacktestService.class);
        BacktestRunner runner = mock(BacktestRunner.class);
        RecordingExecutor executor = new RecordingExecutor();
        BacktestTask task = task(id);
        when(repo.start(id, time())).thenReturn(false);
        when(service.get(id)).thenReturn(task);
        BacktestJobService jobs = new BacktestJobService(
                repo, service, runner, executor,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        assertThat(jobs.start(id)).isEqualTo(task);

        assertThat(executor.submitted).isZero();
        verifyNoInteractions(runner);
    }

    @Test
    void resumesRunningTaskOnlyOnceInCurrentProcess() {
        UUID id = UUID.randomUUID();
        BacktestRepository repo = mock(BacktestRepository.class);
        BacktestService service = mock(BacktestService.class);
        BacktestRunner runner = mock(BacktestRunner.class);
        RecordingExecutor executor = new RecordingExecutor();
        BacktestTask task = task(id);
        when(service.get(id)).thenReturn(task);
        BacktestJobService jobs = new BacktestJobService(
                repo, service, runner, executor,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        assertThat(jobs.resume(id)).isEqualTo(task);
        assertThat(jobs.resume(id)).isEqualTo(task);

        assertThat(executor.submitted).isEqualTo(1);
        executor.task.run();
        verify(runner).run(id);
    }

    @Test
    void rejectsResumeWhenTaskIsNotRunning() {
        UUID id = UUID.randomUUID();
        BacktestRepository repo = mock(BacktestRepository.class);
        BacktestService service = mock(BacktestService.class);
        BacktestRunner runner = mock(BacktestRunner.class);
        RecordingExecutor executor = new RecordingExecutor();
        BacktestTask task = task(id, BacktestStatus.COMPLETED);
        when(service.get(id)).thenReturn(task);
        BacktestJobService jobs = new BacktestJobService(
                repo, service, runner, executor,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> jobs.resume(id))
                .isInstanceOf(InvalidBacktestRequestException.class);

        assertThat(executor.submitted).isZero();
        verifyNoInteractions(runner);
    }

    private OffsetDateTime time() {
        return OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    }

    private BacktestTask task(UUID id) {
        return task(id, BacktestStatus.RUNNING);
    }

    private BacktestTask task(UUID id, BacktestStatus status) {
        return new BacktestTask(
                id, LocalDate.parse("2026-08-20"),
                LocalDate.parse("2026-08-20"), 1, "glm-4.7",
                BacktestPromptBuilder.VERSION, "rule-v1",
                status, 0, 0, 0,
                null, time(), time(), null
        );
    }

    private static class RecordingExecutor implements TaskExecutor {
        private int submitted;
        private Runnable task;

        @Override
        public void execute(Runnable task) {
            submitted++;
            this.task = task;
        }
    }
}
