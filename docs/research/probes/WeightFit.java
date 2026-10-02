package com.opspilot.ai.forecast.learning;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.SingularValueDecomposition;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 训练类别成本的离线对照；权重只来自训练标签，不注册正式模型。 */
final class WeightFit {
    static double[] weights(int[] y,double alpha){
        if(y==null||y.length==0||!Double.isFinite(alpha)||alpha<0||alpha>1)throw new IllegalArgumentException("训练标签和权重指数非法");
        int[] counts=new int[3];for(int c:y){if(c<0||c>2)throw new IllegalArgumentException("训练标签非法");counts[c]++;}
        double[] weights=new double[3];double mean=0;
        for(int c=0;c<3;c++){
            if(counts[c]==0)throw new IllegalArgumentException("训练类别缺失，停止加权实验");
            weights[c]=Math.pow((double)y.length/(3*counts[c]),alpha);mean+=counts[c]*weights[c]/y.length;
        }
        for(int c=0;c<3;c++)weights[c]/=mean;return weights;
    }
    static void validate(double[] weights){
        if(weights==null||weights.length!=3||Arrays.stream(weights).anyMatch(v->!Double.isFinite(v)||v<=0))
            throw new IllegalArgumentException("三类权重必须为有限正数");
    }
    // 理想加权后验的逆变换；不是用验证标签拟合的校准，也不保证真实概率校准。
    static double[] correct(double[] p,double[] weights){
        validate(weights);
        if(p==null||p.length!=3||Arrays.stream(p).anyMatch(v->!Double.isFinite(v)||v<0||v>1)||Math.abs(Arrays.stream(p).sum()-1)>1e-10)
            throw new IllegalArgumentException("三类概率非法");
        double[] result=new double[3];double sum=0;
        for(int c=0;c<3;c++){result[c]=p[c]/weights[c];sum+=result[c];}
        if(!Double.isFinite(sum)||sum<=0)throw new IllegalArgumentException("概率逆变换数值非法");
        for(int c=0;c<3;c++)result[c]/=sum;return result;
    }
    static SoftmaxFit.Fit fit(double[][] x,int[] y,double lambda,double[] weights){
        Objective f=new Objective(x,y,lambda,weights);
        if(Arrays.stream(weights).allMatch(v->v==1))return RidgeFit.fit(x,y,lambda);
        double[] w=new double[2*f.base.width];List<SoftmaxFit.Step> trace=new ArrayList<>();
        for(int iteration=0;iteration<=100;iteration++){
            double loss=f.loss(w);double[] g=f.gradient(w);double max=SoftmaxFit.maxGradient(g);
            var svd=new SingularValueDecomposition(new Array2DRowRealMatrix(f.hessian(w),false));
            int rank=svd.getRank();double[] singular=svd.getSingularValues();
            trace.add(new SoftmaxFit.Step(iteration,loss,max,rank,rank==0?0:singular[0]/singular[rank-1]));
            if(max<=1e-6)return new SoftmaxFit.Fit(true,"CONVERGED",w,List.copyOf(trace));
            if(iteration==100)return new SoftmaxFit.Fit(false,"ITERATION_LIMIT",null,List.copyOf(trace));
            double[] direction=svd.getSolver().solve(new ArrayRealVector(g,false)).toArray();
            double dot=0;for(int j=0;j<w.length;j++)dot+=g[j]*direction[j];
            if(!Double.isFinite(dot)||dot<=0)return new SoftmaxFit.Fit(false,"NO_DESCENT",null,List.copyOf(trace));
            double alpha=1;double[] next=new double[w.length];boolean accepted=false;
            for(int step=0;step<50;step++,alpha*=.5){
                for(int j=0;j<w.length;j++)next[j]=w[j]-alpha*direction[j];
                double nextLoss=f.loss(next);
                if(Double.isFinite(nextLoss)&&nextLoss<=loss-1e-4*alpha*dot){accepted=true;break;}
            }
            if(!accepted)return new SoftmaxFit.Fit(false,"LINE_SEARCH_FAILED",null,List.copyOf(trace));w=next;
        }
        throw new IllegalStateException("加权求解循环未返回");
    }
    static final class Objective {
        final SoftmaxFit.Objective base;final double lambda;final double[] weights;final double total;
        Objective(double[][] x,int[] y,double lambda,double[] weights){
            validate(weights);if(!Double.isFinite(lambda)||lambda<0)throw new IllegalArgumentException("正则强度非法");
            base=new SoftmaxFit.Objective(x,y);this.lambda=lambda;this.weights=weights.clone();
            double sum=0;for(int c:y)sum+=weights[c];total=sum;
            if(!Double.isFinite(total)||total<=0)throw new IllegalArgumentException("总样本权重非法");
        }
        double loss(double[] w){
            double loss=0;int width=base.width;
            for(int i=0;i<base.x.length;i++){
                double[] z=SoftmaxFit.logits(w,base.x[i]);double max=Math.max(z[0],Math.max(z[1],z[2]));
                loss+=weights[base.y[i]]/total*(max+Math.log(Math.exp(z[0]-max)+Math.exp(z[1]-max)+Math.exp(z[2]-max))-z[base.y[i]]);
            }
            // 与上轮完全相同：中心化三类特征系数，排除两类存储截距。
            for(int j=0;j<width-1;j++){double a=w[j],b=w[j+width];loss+=lambda/3*(a*a+b*b-a*b);}return loss;
        }
        double[] gradient(double[] w){
            int width=base.width;double[] g=new double[2*width];
            for(int i=0;i<base.x.length;i++){
                double[] p=SoftmaxFit.probabilities(w,base.x[i]);
                for(int c=0;c<2;c++){
                    double error=weights[base.y[i]]/total*(p[c]-(base.y[i]==c?1:0));
                    for(int j=0;j<width;j++)g[c*width+j]+=error*(j==width-1?1:base.x[i][j]);
                }
            }
            for(int j=0;j<width-1;j++){g[j]+=lambda/3*(2*w[j]-w[j+width]);g[j+width]+=lambda/3*(2*w[j+width]-w[j]);}return g;
        }
        double[][] hessian(double[] w){
            int width=base.width;double[][] h=new double[2*width][2*width];
            for(int i=0;i<base.x.length;i++){
                double[] row=base.x[i],p=SoftmaxFit.probabilities(w,row);
                for(int c=0;c<2;c++)for(int d=0;d<2;d++){
                    double variance=weights[base.y[i]]/total*p[c]*((c==d?1:0)-p[d]);
                    for(int j=0;j<width;j++)for(int k=0;k<width;k++)h[c*width+j][d*width+k]+=variance*(j==width-1?1:row[j])*(k==width-1?1:row[k]);
                }
            }
            for(int j=0;j<width-1;j++){
                h[j][j]+=2*lambda/3;h[j+width][j+width]+=2*lambda/3;h[j][j+width]-=lambda/3;h[j+width][j]-=lambda/3;
            }
            return h;
        }
    }
}
