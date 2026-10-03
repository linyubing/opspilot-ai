package com.opspilot.ai.forecast.learning;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.SingularValueDecomposition;

/** 两阶段研究用二分类求解器，不注册正式模型。 */
final class BinaryFit {
    record Fit(boolean converged,double[] weights,int steps,double loss,double gradient) {}
    static Fit fit(double[][] x,int[] y,double lambda){
        validate(x,y,lambda);double[] w=new double[x[0].length+1];
        for(int step=0;step<=100;step++){
            double value=loss(x,y,w,lambda),max=0;double[] g=gradient(x,y,w,lambda);
            for(double v:g)max=Math.max(max,Math.abs(v));
            if(!Double.isFinite(value)||!Double.isFinite(max))throw new IllegalStateException("二分类求解数值非法");
            if(max<=1e-6)return new Fit(true,w,step,value,max);
            if(step==100)return new Fit(false,null,step,value,max);
            double[] d=new SingularValueDecomposition(new Array2DRowRealMatrix(hessian(x,y,w,lambda),false))
                    .getSolver().solve(new ArrayRealVector(g,false)).toArray();
            double dot=0;for(int j=0;j<w.length;j++)dot+=g[j]*d[j];
            if(!Double.isFinite(dot)||dot<=0)return new Fit(false,null,step,value,max);
            boolean accepted=false;double alpha=1;double[] next=new double[w.length];
            for(int retry=0;retry<50;retry++,alpha*=.5){
                for(int j=0;j<w.length;j++)next[j]=w[j]-alpha*d[j];
                double nextLoss=loss(x,y,next,lambda);
                if(Double.isFinite(nextLoss)&&nextLoss<=value-1e-4*alpha*dot){accepted=true;break;}
            }
            if(!accepted)return new Fit(false,null,step,value,max);w=next;
        }
        throw new IllegalStateException("二分类循环未返回");
    }
    static double probability(double[] w,double[] x){
        if(w==null||x==null||w.length!=x.length+1)throw new IllegalArgumentException("二分类维数不符");
        double z=w[x.length];
        for(double v:w)if(!Double.isFinite(v))throw new IllegalArgumentException("权重不是有限数");
        for(int j=0;j<x.length;j++){if(!Double.isFinite(x[j]))throw new IllegalArgumentException("特征不是有限数");z+=w[j]*x[j];}
        if(!Double.isFinite(z))throw new IllegalArgumentException("二分类logit溢出");
        return z>=0?1/(1+Math.exp(-z)):Math.exp(z)/(1+Math.exp(z));
    }
    static double loss(double[][] x,int[] y,double[] w,double lambda){
        validate(x,y,lambda);double value=0;
        for(int i=0;i<x.length;i++){
            probability(w,x[i]);double z=w[w.length-1];for(int j=0;j<x[i].length;j++)z+=w[j]*x[i][j];
            value+=Math.max(z,0)+Math.log1p(Math.exp(-Math.abs(z)))-y[i]*z;
        }
        value/=x.length;for(int j=0;j<w.length-1;j++)value+=lambda*w[j]*w[j]/4;
        return value;
    }
    static void validate(double[][] x,int[] y,double lambda){
        if(x==null||y==null||x.length==0||x.length!=y.length||x[0]==null||x[0].length==0||!Double.isFinite(lambda)||lambda<=0)throw new IllegalArgumentException("二分类输入不能为空或正则非法");
        boolean zero=false,one=false;for(int i=0;i<x.length;i++){
            if(x[i]==null||x[i].length!=x[0].length||(y[i]!=0&&y[i]!=1))throw new IllegalArgumentException("二分类矩阵非法");
            for(double v:x[i])if(!Double.isFinite(v))throw new IllegalArgumentException("特征非法");
            zero|=y[i]==0;one|=y[i]==1;
        }
        if(!zero||!one)throw new IllegalArgumentException("二分类训练必须两类齐全");
    }
    static double[] gradient(double[][] x,int[] y,double[] w,double lambda){
        validate(x,y,lambda);double[] g=new double[w.length];
        for(int i=0;i<x.length;i++){double e=(probability(w,x[i])-y[i])/x.length;
            for(int j=0;j<g.length;j++)g[j]+=e*(j==g.length-1?1:x[i][j]);}
        for(int j=0;j<g.length-1;j++)g[j]+=lambda*w[j]/2;
        return g;
    }
    static double[][] hessian(double[][] x,int[] y,double[] w,double lambda){
        validate(x,y,lambda);double[][] h=new double[w.length][w.length];
        for(double[] row:x){double p=probability(w,row),v=p*(1-p)/x.length;
            for(int j=0;j<w.length;j++)for(int k=0;k<w.length;k++)h[j][k]+=v*(j==w.length-1?1:row[j])*(k==w.length-1?1:row[k]);}
        for(int j=0;j<w.length-1;j++)h[j][j]+=lambda/2;return h;
    }
}
