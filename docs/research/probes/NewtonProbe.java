package com.opspilot.ai.forecast.learning;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.SingularValueDecomposition;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 收敛诊断的独立牛顿参照；仅供离线研究，不注册为正式模型。 */
public class NewtonProbe {
    static final int WIDTH = 21;
    static final double TOL = 1e-6;
    record Step(int iteration, double loss, double gradient, int rank, double condition, double alpha) {}
    record Fit(boolean converged, String status, double[] weights, List<Step> trace) {}

    static Fit fit(double[][] x, int[] y, int maxIterations) {
        Objective f = new Objective(x, y);
        double[] w = new double[2 * WIDTH];
        List<Step> trace = new ArrayList<>();
        double previousAlpha = 0;
        for (int iteration = 0; iteration <= maxIterations; iteration++) {
            double loss = f.loss(w); double[] g = f.gradient(w);
            double max = fullGradient(g);
            var svd = new SingularValueDecomposition(new Array2DRowRealMatrix(f.hessian(w), false));
            int rank = svd.getRank(); double[] singular = svd.getSingularValues();
            double condition = rank == 0 ? 0 : singular[0] / singular[rank - 1];
            trace.add(new Step(iteration, loss, max, rank, condition, previousAlpha));
            if (max <= TOL) return new Fit(true, "CONVERGED", w, List.copyOf(trace));
            if (iteration == maxIterations) return new Fit(false, "ITERATION_LIMIT", null, List.copyOf(trace));
            // 参考类别固定为零，消除 softmax 平移冗余；SVD 只处理数值秩亏，不加正则惩罚。
            double[] direction = svd.getSolver().solve(new ArrayRealVector(g, false)).toArray();
            double dot = 0;
            for (int j = 0; j < w.length; j++) dot += g[j] * direction[j];
            if (!Double.isFinite(dot) || dot <= 0) return new Fit(false, "NO_DESCENT", null, List.copyOf(trace));
            double alpha = 1; double[] next = new double[w.length]; boolean accepted = false;
            for (int step = 0; step < 50; step++, alpha *= .5) {
                for (int j = 0; j < w.length; j++) next[j] = w[j] - alpha * direction[j];
                double nextLoss = f.loss(next);
                if (Double.isFinite(nextLoss) && nextLoss <= loss - 1e-4 * alpha * dot) { accepted = true; break; }
            }
            if (!accepted) return new Fit(false, "LINE_SEARCH_FAILED", null, List.copyOf(trace));
            w = next; previousAlpha = alpha;
        }
        throw new IllegalArgumentException("最大迭代数不能为负");
    }
    static double[] logits(double[] w, double[] x) {
        double[] z = {w[WIDTH - 1], w[2 * WIDTH - 1], 0};
        for (int c = 0; c < 2; c++) for (int j = 0; j < WIDTH - 1; j++) z[c] += w[c * WIDTH + j] * x[j];
        return z;
    }
    static double[] probabilities(double[] w, double[] x) {
        double[] p = logits(w, x); double max = Math.max(p[0], Math.max(p[1], p[2])), sum = 0;
        for (int c = 0; c < 3; c++) { p[c] = Math.exp(p[c] - max); sum += p[c]; }
        for (int c = 0; c < 3; c++) p[c] /= sum;
        return p;
    }
    // 补回参考类别的梯度，保持与此前三组完整参数相同的最大梯度停止条件。
    static double fullGradient(double[] g) {
        double max = Arrays.stream(g).map(Math::abs).max().orElseThrow();
        for (int j = 0; j < WIDTH; j++) max = Math.max(max, Math.abs(g[j] + g[j + WIDTH]));
        return max;
    }
    static class Objective {
        final double[][] x; final int[] y;
        Objective(double[][] x, int[] y) {
            if (x.length == 0 || x.length != y.length) throw new IllegalArgumentException("训练行数无效");
            for (int i = 0; i < x.length; i++) if (x[i].length != WIDTH - 1 || y[i] < 0 || y[i] > 2
                    || Arrays.stream(x[i]).anyMatch(v -> !Double.isFinite(v))) throw new IllegalArgumentException("训练值非法");
            this.x = x; this.y = y;
        }
        double loss(double[] w) {
            double loss = 0;
            for (int i = 0; i < x.length; i++) {
                double[] z = logits(w, x[i]); double max = Math.max(z[0], Math.max(z[1], z[2]));
                loss += max + Math.log(Math.exp(z[0] - max) + Math.exp(z[1] - max) + Math.exp(z[2] - max)) - z[y[i]];
            }
            return loss / x.length;
        }
        double[] gradient(double[] w) {
            double[] g = new double[2 * WIDTH];
            for (int i = 0; i < x.length; i++) {
                double[] p = probabilities(w, x[i]);
                for (int c = 0; c < 2; c++) {
                    double error = (p[c] - (y[i] == c ? 1 : 0)) / x.length;
                    for (int j = 0; j < WIDTH; j++) g[c * WIDTH + j] += error * (j == WIDTH - 1 ? 1 : x[i][j]);
                }
            }
            return g;
        }
        double[][] hessian(double[] w) {
            double[][] h = new double[2 * WIDTH][2 * WIDTH];
            for (double[] row : x) {
                double[] p = probabilities(w, row);
                for (int c = 0; c < 2; c++) for (int d = 0; d < 2; d++) {
                    double variance = p[c] * ((c == d ? 1 : 0) - p[d]) / x.length;
                    for (int j = 0; j < WIDTH; j++) for (int k = 0; k < WIDTH; k++) {
                        h[c * WIDTH + j][d * WIDTH + k] += variance * (j == WIDTH - 1 ? 1 : row[j]) * (k == WIDTH - 1 ? 1 : row[k]);
                    }
                }
            }
            return h;
        }
    }
}
