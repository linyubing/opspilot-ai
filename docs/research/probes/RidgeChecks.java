package com.opspilot.ai.forecast.learning;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 正则化数学契约：仅使用数学夹具，不生成或写入假行情。 */
public class RidgeChecks {
    public static void main(String[] args) {
        penalty();
        for(int size:new int[]{1,16,20,36}) { derivatives(size); intercept(size); }
        shrink(); permutation(); invalid();
        System.out.println("PASS: 对称惩罚、梯度/Hessian、截距、收缩、类别置换、复现和非法强度");
    }
    // 生产变动若漏惩罚、惩罚了截距或遗漏跨类别项，此测试必须失败。
    static void penalty() {
        double[][] x={{0},{0},{0}};int[] y={0,1,2};double lambda=.3;
        var old=new SoftmaxFit.Objective(x,y);var ridge=new RidgeFit.Objective(x,y,lambda);
        double[] w={2,3,-1,-4};
        near(ridge.loss(w)-old.loss(w),7*lambda/3,1e-12);
        double[] a=old.gradient(w),b=ridge.gradient(w);
        near(b[0]-a[0],5*lambda/3,1e-12);near(b[2]-a[2],-4*lambda/3,1e-12);
        near(b[1]-a[1],0,1e-12);near(b[3]-a[3],0,1e-12);
        double[][] h=ridge.hessian(w),o=old.hessian(w);
        near(h[0][0]-o[0][0],2*lambda/3,1e-12);near(h[0][2]-o[0][2],-lambda/3,1e-12);
        near(h[1][1]-o[1][1],0,1e-12);
    }
    static void derivatives(int size) {
        double[][] x=new double[size+9][size];int[] y=new int[x.length];
        for(int i=0;i<x.length;i++){y[i]=i%3;for(int j=0;j<size;j++)x[i][j]=Math.sin((i+1)*(j+2)*.31);}
        var f=new RidgeFit.Objective(x,y,.1);double[] w=new double[2*(size+1)];
        for(int i=0;i<w.length;i++)w[i]=.07*Math.cos(i+.3);
        double[] g=f.gradient(w);double[][] h=f.hessian(w);
        for(int j=0;j<w.length;j++){
            double old=w[j],step=1e-5;w[j]=old+step;double plus=f.loss(w);double[] pg=f.gradient(w);
            w[j]=old-step;double minus=f.loss(w);double[] mg=f.gradient(w);w[j]=old;
            near(g[j],(plus-minus)/(2*step),1e-7);
            for(int i=0;i<w.length;i++)near(h[i][j],(pg[i]-mg[i])/(2*step),1e-7);
        }
    }
    static void intercept(int size) {
        var fit=RidgeFit.fit(new double[10][size],new int[]{0,0,1,1,1,2,2,2,2,2},1);
        require(fit.converged(),"截距不应受正则惩罚");
        double[] p=SoftmaxFit.probabilities(fit.weights(),new double[size]);
        near(p[0],.2,1e-6);near(p[1],.3,1e-6);near(p[2],.5,1e-6);
    }
    static double[][] x;static int[] y;
    static void shrink() {
        List<double[]> xs=new ArrayList<>();List<Integer> ys=new ArrayList<>();int[][] counts={{8,4,2},{7,7,7},{2,4,8}};
        for(int group=0;group<3;group++)for(int c=0;c<3;c++)for(int i=0;i<counts[group][c];i++){xs.add(new double[]{group-1});ys.add(c);}
        x=xs.toArray(double[][]::new);y=ys.stream().mapToInt(i->i).toArray();
        var old=SoftmaxFit.fit(x,y);var zero=RidgeFit.fit(x,y,0);var weak=RidgeFit.fit(x,y,.1);var strong=RidgeFit.fit(x,y,1);
        require(old.converged()&&zero.converged()&&weak.converged()&&strong.converged(),"强度对照必须收敛");
        require(Arrays.equals(old.weights(),zero.weights()),"lambda=0 必须与旧求解器一致");
        require(norm(strong.weights())<norm(weak.weights())&&norm(weak.weights())<norm(old.weights()),"增强惩罚应收缩中心化系数");
        require(Arrays.equals(weak.weights(),RidgeFit.fit(x,y,.1).weights()),"固定输入应逐值复现");
    }
    static double norm(double[] w){return w[0]*w[0]+w[2]*w[2]-w[0]*w[2];}
    static void permutation() {
        int[] shifted=Arrays.stream(y).map(c->(c+1)%3).toArray();
        var first=RidgeFit.fit(x,y,.1);var second=RidgeFit.fit(x,shifted,.1);
        require(first.converged()&&second.converged(),"类别置换后仍应收敛");
        for(double[] row:x){double[] a=SoftmaxFit.probabilities(first.weights(),row),b=SoftmaxFit.probabilities(second.weights(),row);
            for(int c=0;c<3;c++)near(a[c],b[(c+1)%3],1e-6);}
    }
    static void invalid() {
        for(double v:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY}) {
            boolean rejected=false;try{new RidgeFit.Objective(new double[][]{{0}},new int[]{0},v);}catch(IllegalArgumentException expected){rejected=true;}
            require(rejected,"非法正则强度必须拒绝");
        }
    }
    static void near(double actual,double expected,double tolerance){require(Double.isFinite(actual)&&Math.abs(actual-expected)<=tolerance,"数学期望不一致: "+actual+" != "+expected);}
    static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
