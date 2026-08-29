package com.opspilot.ai.forecast.backtest;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/** 校验两次回测可比性，并计算候选提示词相对基准提示词的变化。 */
@Service
public class BacktestComparisonService {

    private final BacktestService service;
    private final BacktestEvaluationService evaluation;

    public BacktestComparisonService(
            BacktestService service,
            BacktestEvaluationService evaluation
    ) {
        this.service = service;
        this.evaluation = evaluation;
    }

    public BacktestComparison compare(UUID baselineId, UUID candidateId) {
        BacktestTask baselineTask = service.get(baselineId);
        BacktestTask candidateTask = service.get(candidateId);
        checkTask(baselineTask, BacktestPromptVersion.BASELINE);
        checkCandidate(candidateTask);

        var baselineDates = service.samples(baselineId);
        var candidateDates = service.samples(candidateId);
        if (!baselineDates.equals(candidateDates)) {
            throw new InvalidBacktestRequestException(
                    "基准任务和候选任务的历史样本日期不一致，不能直接比较"
            );
        }

        BacktestEvaluation baseline = evaluation.evaluate(baselineId);
        BacktestEvaluation candidate = evaluation.evaluate(candidateId);
        return new BacktestComparison(
                baselineId,
                candidateId,
                baselineDates.size(),
                baseline,
                candidate,
                change(candidate.accuracy(), baseline.accuracy()),
                change(
                        candidate.balancedAccuracy(),
                        baseline.balancedAccuracy()
                )
        );
    }

    private void checkCandidate(BacktestTask task) {
        checkCompleted(task);
        boolean supported = BacktestPromptVersion.CANDIDATE.version()
                .equals(task.promptVersion())
                || BacktestPromptVersion.IMPROVED.version()
                .equals(task.promptVersion());
        if (!supported) {
            throw new InvalidBacktestRequestException(
                    "回测任务不是候选提示词版本，编号=" + task.id()
            );
        }
    }

    private void checkTask(
            BacktestTask task,
            BacktestPromptVersion expected
    ) {
        checkCompleted(task);
        if (!expected.version().equals(task.promptVersion())) {
            throw new InvalidBacktestRequestException(
                    "回测任务提示词版本不符合比较要求，编号=" + task.id()
            );
        }
    }

    private void checkCompleted(BacktestTask task) {
        if (task.status() != BacktestStatus.COMPLETED) {
            throw new InvalidBacktestRequestException(
                    "回测任务尚未完成，编号=" + task.id()
            );
        }
    }

    private BigDecimal change(BigDecimal candidate, BigDecimal baseline) {
        if (candidate == null || baseline == null) {
            return null;
        }
        return candidate.subtract(baseline);
    }
}
