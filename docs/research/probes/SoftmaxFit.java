package com.opspilot.ai.forecast.learning;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.SingularValueDecomposition;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 支持不同特征数的离线收敛参照，不接入正式训练器。 */
final class SoftmaxFit {
    record Step(int iteration, double loss, double gradient, int rank, double condition) {}
    record Fit(boolean converged, String status, double[] weights, List<Step> trace) {}
    static Fit fit(double[][] x, int[] y) {
        Objective f = new Objective(x,y);
        double[] w = new double[2*f.width]; List<Step> trace = new ArrayList<>();
        for(int iteration=0;iteration<=100;iteration++) {
            double loss=f.loss(w);double[] g=f.gradient(w);double max=maxGradient(g);
            var svd=new SingularValueDecomposition(new Array2DRowRealMatrix(f.hessian(w),false));
            int rank=svd.getRank();double[] singular=svd.getSingularValues();
            trace.add(new Step(iteration,loss,max,rank,rank==0?0:singular[0]/singular[rank-1]));
            if(max<=1e-6)return new Fit(true,"CONVERGED",w,List.copyOf(trace));
            if(iteration==100)return new Fit(false,"ITERATION_LIMIT",null,List.copyOf(trace));
            // SVD 处理重复列造成的数值秩亏，仍求解原交叉熵，不新增惩罚项。
            double[] direction=svd.getSolver().solve(new ArrayRealVector(g,false)).toArray();
            double dot=0;for(int j=0;j<w.length;j++)dot+=g[j]*direction[j];
            if(!Double.isFinite(dot)||dot<=0)return new Fit(false,"NO_DESCENT",null,List.copyOf(trace));
            double alpha=1;double[] next=new double[w.length];boolean accepted=false;
            for(int step=0;step<50;step++,alpha*=.5) {
                for(int j=0;j<w.length;j++)next[j]=w[j]-alpha*direction[j];
                double nextLoss=f.loss(next);
                if(Double.isFinite(nextLoss)&&nextLoss<=loss-1e-4*alpha*dot){accepted=true;break;}
            }
            if(!accepted)return new Fit(false,"LINE_SEARCH_FAILED",null,List.copyOf(trace));
            w=next;
        }
        throw new IllegalStateException("求解循环未返回");
    }
    static double[] logits(double[] w,double[] x) {
        int width=x.length+1;
        if(w.length!=2*width)throw new IllegalArgumentException("特征维数与权重不匹配");
        double[] z={w[width-1],w[2*width-1],0};
        for(int c=0;c<2;c++)for(int j=0;j<x.length;j++)z[c]+=w[c*width+j]*x[j];
        return z;
    }
    static double[] probabilities(double[] w, double[] x) {
        double[] p=logits(w,x);double max=Math.max(p[0],Math.max(p[1],p[2])),sum=0;
        for(int c=0;c<3;c++){p[c]=Math.exp(p[c]-max);sum+=p[c];}
        for(int c=0;c<3;c++)p[c]/=sum;
        return p;
    }
    static double maxGradient(double[] g) {
        double max=Arrays.stream(g).map(Math::abs).max().orElseThrow();int width=g.length/2;
        for(int j=0;j<width;j++)max=Math.max(max,Math.abs(g[j]+g[j+width]));
        return max;
    }
    static final class Objective {
        final double[][] x;final int[] y;final int width;
        Objective(double[][] x,int[] y) {
            if(x.length==0||x.length!=y.length||x[0].length==0)throw new IllegalArgumentException("训练矩阵不能为空");
            width=x[0].length+1;
            for(int i=0;i<x.length;i++)if(x[i].length!=width-1||y[i]<0||y[i]>2
                    ||Arrays.stream(x[i]).anyMatch(v->!Double.isFinite(v)))throw new IllegalArgumentException("训练矩阵非法");
            this.x=x;this.y=y;
        }
        double loss(double[] w) {
            double loss=0;
            for(int i=0;i<x.length;i++) {
                double[] z=logits(w,x[i]);double max=Math.max(z[0],Math.max(z[1],z[2]));
                loss+=max+Math.log(Math.exp(z[0]-max)+Math.exp(z[1]-max)+Math.exp(z[2]-max))-z[y[i]];
            }
            return loss/x.length;
        }
        double[] gradient(double[] w) {
            double[] g=new double[2*width];
            for(int i=0;i<x.length;i++) {
                double[] p=probabilities(w,x[i]);
                for(int c=0;c<2;c++) {
                    double error=(p[c]-(y[i]==c?1:0))/x.length;
                    for(int j=0;j<width;j++)g[c*width+j]+=error*(j==width-1?1:x[i][j]);
                }
            }
            return g;
        }
        double[][] hessian(double[] w) {
            double[][] h=new double[2*width][2*width];
            for(double[] row:x) {
                double[] p=probabilities(w,row);
                for(int c=0;c<2;c++)for(int d=0;d<2;d++) {
                    double variance=p[c]*((c==d?1:0)-p[d])/x.length;
                    for(int j=0;j<width;j++)for(int k=0;k<width;k++)
                        h[c*width+j][d*width+k]+=variance*(j==width-1?1:row[j])*(k==width-1?1:row[k]);
                }
            }
            return h;
        }
    }
}
