package com.opspilot.ai.forecast.learning;

import java.util.Arrays;

/** 类别权重数学契约，使用标注清楚的数学夹具，不生成假行情。 */
public class WeightChecks {
    static final int[] Y={0,0,1,1,1,2,2,2,2,2};
    public static void main(String[] args){
        frequencies();
        for(int size:new int[]{1,16,20,36}){intercept(size);derivatives(size);}
        identity();penalty();permutation();invalid();
        System.out.println("PASS: 权重均值、加权截距、逆变换、梯度/Hessian、全1参照、置换、复现及非法输入");
    }
    // 漏掉训练频率权重、读取其他数据频率或改变权重归一化必须失败。
    static void frequencies(){
        double[] w=WeightFit.weights(Y,1);near(w[0],5.0/3,1e-12);near(w[1],10.0/9,1e-12);near(w[2],2.0/3,1e-12);
        double sum=0;for(int c:Y)sum+=w[c];near(sum/Y.length,1,1e-12);
        double[] sqrt=WeightFit.weights(Y,.5);near(sqrt[0]/sqrt[2],Math.sqrt(2.5),1e-12);
    }
    static void intercept(int size){
        for(double alpha:new double[]{.5,1}){
            double[] w=WeightFit.weights(Y,alpha);var fit=WeightFit.fit(new double[Y.length][size],Y,.01,w);
            require(fit.converged(),"加权截距必须收敛");double[] p=SoftmaxFit.probabilities(fit.weights(),new double[size]);
            double[] expected=alpha==1?new double[]{1.0/3,1.0/3,1.0/3}:
                    new double[]{Math.sqrt(2),Math.sqrt(3),Math.sqrt(5)};
            if(alpha!=1){double sum=Arrays.stream(expected).sum();for(int c=0;c<3;c++)expected[c]/=sum;}
            for(int c=0;c<3;c++)near(p[c],expected[c],1e-6);
            double[] corrected=WeightFit.correct(p,w);near(corrected[0],.2,1e-6);near(corrected[1],.3,1e-6);near(corrected[2],.5,1e-6);
        }
    }
    static void derivatives(int size){
        double[][] x=new double[13][size];int[] y={0,0,0,1,1,1,1,2,2,2,2,2,2};
        for(int i=0;i<x.length;i++)for(int j=0;j<size;j++)x[i][j]=Math.sin((i+2)*(j+1)*.29);
        var f=new WeightFit.Objective(x,y,.01,WeightFit.weights(y,.5));double[] w=new double[2*(size+1)];
        for(int j=0;j<w.length;j++)w[j]=.04*Math.cos(j+.7);double[] g=f.gradient(w);double[][] h=f.hessian(w);
        for(int j=0;j<w.length;j++){
            double old=w[j],step=1e-5;w[j]=old+step;double plus=f.loss(w);double[] pg=f.gradient(w);
            w[j]=old-step;double minus=f.loss(w);double[] mg=f.gradient(w);w[j]=old;
            near(g[j],(plus-minus)/(2*step),1e-7);for(int i=0;i<w.length;i++)near(h[i][j],(pg[i]-mg[i])/(2*step),1e-7);
        }
    }
    static void identity(){
        double[][] x=new double[Y.length][2];for(int i=0;i<x.length;i++)x[i]=new double[]{Math.sin(i),Math.cos(i)};
        var old=RidgeFit.fit(x,Y,.01);var now=WeightFit.fit(x,Y,.01,new double[]{1,1,1});
        require(old.converged()&&now.converged()&&Arrays.equals(old.weights(),now.weights()),"全1权重必须与原参照一致");
        double[] weights=WeightFit.weights(Y,.5);var fit=WeightFit.fit(x,Y,.01,weights);
        require(fit.converged()&&Arrays.equals(fit.weights(),WeightFit.fit(x,Y,.01,weights).weights()),"加权模型应逐值复现");
        var objective=new WeightFit.Objective(x,Y,.01,new double[]{1,1,1});var base=new RidgeFit.Objective(x,Y,.01);
        near(objective.loss(old.weights()),base.loss(old.weights()),1e-12);
    }
    static void permutation(){
        double[][] x=new double[Y.length][1];for(int i=0;i<x.length;i++)x[i][0]=Math.cos(i*.5);
        int[] shifted=Arrays.stream(Y).map(c->(c+1)%3).toArray();double[] firstWeights=WeightFit.weights(Y,.5),secondWeights=WeightFit.weights(shifted,.5);
        var first=WeightFit.fit(x,Y,.01,firstWeights);var second=WeightFit.fit(x,shifted,.01,secondWeights);
        require(first.converged()&&second.converged(),"置换标签后应收敛");
        for(double[] row:x){double[] a=WeightFit.correct(SoftmaxFit.probabilities(first.weights(),row),firstWeights),b=WeightFit.correct(SoftmaxFit.probabilities(second.weights(),row),secondWeights);
            for(int c=0;c<3;c++)near(a[c],b[(c+1)%3],2e-6);}
    }
    // 特征系数 a=2、b=3 的中心化惩罚为 7*lambda/3；截距不受惩罚。
    static void penalty(){
        double[][] x=new double[Y.length][1];double[] w={2,4,3,5},weights={1,2,3};
        var zero=new WeightFit.Objective(x,Y,0,weights);var penalized=new WeightFit.Objective(x,Y,.03,weights);
        near(penalized.loss(w)-zero.loss(w),.07,1e-12);
        double[] a=zero.gradient(w),b=penalized.gradient(w);near(b[0]-a[0],.01,1e-12);near(b[2]-a[2],.04,1e-12);
        near(b[1]-a[1],0,1e-12);near(b[3]-a[3],0,1e-12);
        double[][] h0=zero.hessian(w),h1=penalized.hessian(w);near(h1[0][0]-h0[0][0],.02,1e-12);near(h1[0][2]-h0[0][2],-.01,1e-12);
    }
    static void invalid(){
        rejects(()->WeightFit.weights(new int[]{0,0,1},1));rejects(()->WeightFit.weights(Y,Double.NaN));rejects(()->WeightFit.weights(Y,-1));
        for(double[] w:new double[][]{{1,0,1},{1,Double.NaN,1},{1,1},{1,Double.POSITIVE_INFINITY,1}})
            rejects(()->new WeightFit.Objective(new double[Y.length][1],Y,.01,w));
        rejects(()->WeightFit.correct(new double[]{.2,.3,.5},new double[]{1,0,1}));
        rejects(()->WeightFit.correct(new double[]{.2,.3,Double.NaN},new double[]{1,1,1}));
    }
    static void rejects(Runnable r){boolean rejected=false;try{r.run();}catch(IllegalArgumentException expected){rejected=true;}require(rejected,"非法权重/缺类别必须拒绝");}
    static void near(double a,double b,double tolerance){require(Double.isFinite(a)&&Math.abs(a-b)<=tolerance,"数学期望不一致: "+a+" != "+b);}
    static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
