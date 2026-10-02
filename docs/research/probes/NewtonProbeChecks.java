package com.opspilot.ai.forecast.learning;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 纯数学测试夹具，不是假行情，不接入数据库或产品模型。 */
public class NewtonProbeChecks {
    public static void main(String[] args) {
        var intercept = NewtonProbe.fit(new double[10][20], new int[]{0,0,1,1,1,2,2,2,2,2}, 100);
        require(intercept.converged(), "只有截距的模型必须收敛到实际类别频率");
        double[] p = NewtonProbe.probabilities(intercept.weights(), new double[20]);
        near(p[0], .2, 1e-6); near(p[1], .3, 1e-6); near(p[2], .5, 1e-6);
        var empty = NewtonProbe.fit(new double[10][20], new int[]{0,0,1,1,1,2,2,2,2,2}, 0);
        require(!empty.converged(), "达到迭代上限不能伪称收敛");

        List<double[]> inputs = new ArrayList<>(); List<Integer> labels = new ArrayList<>();
        int[][] counts = {{8,4,2},{7,7,7},{2,4,8}};
        for (int group = 0; group < 3; group++) for (int c = 0; c < 3; c++) {
            for (int i = 0; i < counts[group][c]; i++) {
                double[] x = new double[20]; x[0] = group - 1; x[1] = 2 * x[0];
                inputs.add(x); labels.add(c);
            }
        }
        double[][] x = inputs.toArray(double[][]::new);
        int[] y = labels.stream().mapToInt(i -> i).toArray();
        var fit = NewtonProbe.fit(x, y, 100);
        require(fit.converged(), "完全共线的输入也必须得到正确概率");
        var negative = NewtonProbe.probabilities(fit.weights(), x[0]);
        near(negative[0], 4.0/7, 1e-6); near(negative[1], 2.0/7, 1e-6); near(negative[2], 1.0/7, 1e-6);
        var positive = NewtonProbe.probabilities(fit.weights(), x[x.length-1]);
        near(positive[0], 1.0/7, 1e-6); near(positive[1], 2.0/7, 1e-6); near(positive[2], 4.0/7, 1e-6);
        for (int i = 1; i < fit.trace().size(); i++) {
            require(fit.trace().get(i).loss() <= fit.trace().get(i-1).loss() + 1e-12, "线搜索不得接受更大损失");
        }
        var repeat = NewtonProbe.fit(x, y, 100);
        require(Arrays.equals(fit.weights(), repeat.weights()), "相同输入重复训练应复现");

        // 中心差分独立校验一阶和二阶导数，不能让错误梯度与错误 Hessian 互相通过。
        var objective = new NewtonProbe.Objective(x, y);
        double[] w = new double[42];
        for (int i = 0; i < w.length; i++) w[i] = .02 * Math.sin(i);
        double[] gradient = objective.gradient(w); double[][] hessian = objective.hessian(w);
        for (int j = 0; j < w.length; j++) {
            double before = w[j]; double step = 1e-5;
            w[j] = before + step; double plus = objective.loss(w); double[] plusG = objective.gradient(w);
            w[j] = before - step; double minus = objective.loss(w); double[] minusG = objective.gradient(w);
            w[j] = before;
            near(gradient[j], (plus - minus) / (2 * step), 1e-7);
            for (int i = 0; i < w.length; i++) near(hessian[i][j], (plusG[i] - minusG[i]) / (2 * step), 1e-7);
        }
        double[] huge = new double[42]; huge[20] = 1000; huge[41] = -1000;
        double[] stable = NewtonProbe.probabilities(huge, new double[20]);
        require(Arrays.stream(stable).allMatch(v -> Double.isFinite(v) && v >= 0 && v <= 1), "极端分数仍需合法概率");
        near(Arrays.stream(stable).sum(), 1, 1e-12);
        // 总数 49：0 类 17 条损失 0，1 类 15 条损失 2000，2 类 17 条损失 1000。
        near(objective.loss(huge), 47000.0 / 49, 1e-8);
        System.out.println("PASS: 类别频率、已知多项式逻辑概率、共线输入、损失单调、固定输入复现、梯度/Hessian 差分、数值稳定、迭代上限");
    }
    static void near(double actual, double expected, double tolerance) {
        require(Double.isFinite(actual) && Math.abs(actual - expected) <= tolerance,
                "数学断言失败 expected=" + expected + " actual=" + actual);
    }
    static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
