package com.opspilot.ai.forecast.learning;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 特征维数变化的数学契约校验，夹具不是行情。 */
public class SoftmaxChecks {
    public static void main(String[] args) {
        for (int size : new int[]{1,16,20,36}) {
            var fit = SoftmaxFit.fit(new double[10][size], new int[]{0,0,1,1,1,2,2,2,2,2});
            require(fit.converged(), "不同维数的截距问题必须收敛");
            double[] p = SoftmaxFit.probabilities(fit.weights(), new double[size]);
            near(p[0],.2,1e-6); near(p[1],.3,1e-6); near(p[2],.5,1e-6);
            List<double[]> xs = new ArrayList<>(); List<Integer> ys = new ArrayList<>();
            int[][] counts = {{8,4,2},{7,7,7},{2,4,8}};
            for (int group=0; group<3; group++) for (int c=0;c<3;c++) for (int i=0;i<counts[group][c];i++) {
                double[] row = new double[size];
                for (int j=0;j<size;j++) row[j]=(group-1)*(j+1.0)/size;
                xs.add(row); ys.add(c);
            }
            double[][] x=xs.toArray(double[][]::new); int[] y=ys.stream().mapToInt(i->i).toArray();
            var known=SoftmaxFit.fit(x,y); require(known.converged(),"完全重复/共线列不应破坏求解");
            double[] first=SoftmaxFit.probabilities(known.weights(),x[0]);
            near(first[0],4.0/7,1e-6);near(first[1],2.0/7,1e-6);near(first[2],1.0/7,1e-6);
            if(size==20) {
                var fixed=NewtonProbe.fit(x,y,100); require(fixed.converged(),"旧参照必须收敛");
                for(double[] row:x) {
                    double[] old=NewtonProbe.probabilities(fixed.weights(),row), now=SoftmaxFit.probabilities(known.weights(),row);
                    for(int c=0;c<3;c++) near(now[c],old[c],1e-10);
                }
            }
            require(Arrays.equals(known.weights(),SoftmaxFit.fit(x,y).weights()),"相同数学输入应复现");
            derivatives(size);
        }
        System.out.println("PASS: 1/16/20/36维截距、共线列、已知概率、20维旧参照对齐和复现");
    }
    static void derivatives(int size) {
        double[][] x=new double[size+11][size];int[] y=new int[x.length];
        for(int i=0;i<x.length;i++){y[i]=i%3;for(int j=0;j<size;j++)x[i][j]=Math.sin((i+1)*(j+1)*.37)+Math.cos((i+2)*(j+3)*.13);}
        var f=new SoftmaxFit.Objective(x,y);double[] w=new double[2*(size+1)];
        for(int i=0;i<w.length;i++)w[i]=.03*Math.sin(i+.5);
        double[] g=f.gradient(w);double[][] h=f.hessian(w);
        for(int j=0;j<w.length;j++){
            double old=w[j],step=1e-5;
            w[j]=old+step;double plus=f.loss(w);double[] pg=f.gradient(w);
            w[j]=old-step;double minus=f.loss(w);double[] mg=f.gradient(w);w[j]=old;
            near(g[j],(plus-minus)/(2*step),1e-7);
            for(int i=0;i<w.length;i++)near(h[i][j],(pg[i]-mg[i])/(2*step),1e-7);
        }
        double[] reference=new double[w.length];reference[0]=1;reference[size+1]=2;
        near(SoftmaxFit.maxGradient(reference),3,0);
    }
    static void near(double actual,double expected,double tolerance) {
        require(Double.isFinite(actual)&&Math.abs(actual-expected)<=tolerance,"概率不符合独立数学期望");
    }
    static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
}
