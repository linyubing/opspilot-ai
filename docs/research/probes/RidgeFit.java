package com.opspilot.ai.forecast.learning;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.SingularValueDecomposition;
import java.util.ArrayList;
import java.util.List;

/** 对称 L2 离线求解：抑制特征系数，不惩罚截距，不注册正式模型。 */
final class RidgeFit {
    static SoftmaxFit.Fit fit(double[][] x,int[] y,double lambda) {
        Objective f=new Objective(x,y,lambda);
        if(lambda==0)return SoftmaxFit.fit(x,y);
        double[] w=new double[2*f.base.width];List<SoftmaxFit.Step> trace=new ArrayList<>();
        for(int iteration=0;iteration<=100;iteration++) {
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
            for(int step=0;step<50;step++,alpha*=.5) {
                for(int j=0;j<w.length;j++)next[j]=w[j]-alpha*direction[j];
                double nextLoss=f.loss(next);
                if(Double.isFinite(nextLoss)&&nextLoss<=loss-1e-4*alpha*dot){accepted=true;break;}
            }
            if(!accepted)return new SoftmaxFit.Fit(false,"LINE_SEARCH_FAILED",null,List.copyOf(trace));
            w=next;
        }
        throw new IllegalStateException("正则化求解循环未返回");
    }
    static final class Objective {
        final SoftmaxFit.Objective base;final double lambda;
        Objective(double[][] x,int[] y,double lambda) {
            if(!Double.isFinite(lambda)||lambda<0)throw new IllegalArgumentException("正则强度必须是有限非负数");
            base=new SoftmaxFit.Objective(x,y);this.lambda=lambda;
        }
        double loss(double[] w){
            double loss=base.loss(w);int width=base.width;
            // 排除每类最后一个截距；第三类系数零，惩罚中心化后的三类系数。
            for(int j=0;j<width-1;j++){double a=w[j],b=w[j+width];loss+=lambda/3*(a*a+b*b-a*b);}
            return loss;
        }
        double[] gradient(double[] w){
            double[] g=base.gradient(w);int width=base.width;
            for(int j=0;j<width-1;j++){
                g[j]+=lambda/3*(2*w[j]-w[j+width]);
                g[j+width]+=lambda/3*(2*w[j+width]-w[j]);
            }
            return g;
        }
        double[][] hessian(double[] w){
            double[][] h=base.hessian(w);int width=base.width;
            for(int j=0;j<width-1;j++){
                h[j][j]+=2*lambda/3;h[j+width][j+width]+=2*lambda/3;
                h[j][j+width]-=lambda/3;h[j+width][j]-=lambda/3;
            }
            return h;
        }
    }
}
