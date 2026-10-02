package com.opspilot.ai.forecast.learning;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.SingularValueDecomposition;

/** 全维数值差分校验；使用数学矩阵，不读取或模拟市场行情。 */
public class NewtonDenseChecks {
    public static void main(String[] args) {
        double[][] x = new double[31][20]; int[] y = new int[31];
        double[][] design = new double[31][21];
        for (int i = 0; i < x.length; i++) {
            y[i] = i % 3;
            for (int j = 0; j < 20; j++) {
                x[i][j] = Math.sin((i+1)*(j+1)*.37) + Math.cos((i+2)*(j+3)*.13);
                design[i][j] = x[i][j];
            }
            design[i][20] = 1;
        }
        if (new SingularValueDecomposition(new Array2DRowRealMatrix(design)).getRank() != 21) {
            throw new AssertionError("全维夹具必须满秩");
        }
        var f = new NewtonProbe.Objective(x,y);
        double[] w = new double[42];
        for (int i = 0; i < w.length; i++) w[i] = .03 * Math.sin(i+.5);
        double[] gradient = f.gradient(w); double[][] hessian = f.hessian(w);
        double gradientError = 0, hessianError = 0;
        for (int j = 0; j < w.length; j++) {
            double old = w[j], step = 1e-5;
            w[j] = old + step; double plus = f.loss(w); double[] plusG = f.gradient(w);
            w[j] = old - step; double minus = f.loss(w); double[] minusG = f.gradient(w);
            w[j] = old;
            gradientError = Math.max(gradientError, Math.abs(gradient[j] - (plus-minus)/(2*step)));
            for (int i = 0; i < w.length; i++) {
                hessianError = Math.max(hessianError, Math.abs(hessian[i][j] - (plusG[i]-minusG[i])/(2*step)));
            }
        }
        if (!Double.isFinite(gradientError) || !Double.isFinite(hessianError) || gradientError > 1e-7 || hessianError > 1e-7) {
            throw new AssertionError("全维解析导数不符合独立差分");
        }
        double[] g = new double[42]; g[0] = 1; g[21] = 2;
        if (NewtonProbe.fullGradient(g) != 3) throw new AssertionError("停止标准漏掉参考类别梯度");
        System.out.println("PASS: 20维非零满秩夹具，42个梯度及1764个Hessian元素；gradientError="
                + gradientError + ", hessianError=" + hessianError);
    }
}
