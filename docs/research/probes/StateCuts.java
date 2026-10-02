package com.opspilot.ai.forecast.learning;

import java.util.Arrays;

/** 训练内固定价格状态与频率控制；只做离线错误审计。 */
final class StateCuts {
    record Input(double volatility,double gap,double return1,double return20) {
        Input {
            if(!Double.isFinite(volatility)||volatility<0||!Double.isFinite(gap)
                    ||!Double.isFinite(return1)||return1<=-100||!Double.isFinite(return20)||return20<=-100)
                throw new IllegalArgumentException("当时价格状态输入非法");
        }
    }
    record Cuts(double volatility,double gap) {
        Cuts {if(!Double.isFinite(volatility)||volatility<0||!Double.isFinite(gap)||gap<0)throw new IllegalArgumentException("状态分界非法");}
        int cell(Input input){
            double trend=priorTrend(input);
            boolean conflict=input.return1()!=0&&trend!=0&&Math.signum(input.return1())!=Math.signum(trend);
            return (input.volatility()>volatility?4:0)+(Math.abs(input.gap())>gap?2:0)+(conflict?1:0);
        }
    }
    record Prior(Cuts cuts,int[][] counts,int[] global) {
        double[] predict(Input input){
            int[] selected=counts[cuts.cell(input)];int sum=Arrays.stream(selected).sum();
            // 这是训练概率估计的明确小样本策略，不是补造缺失行情或样本。
            if(sum<30){selected=global;sum=Arrays.stream(selected).sum();}
            double[] p=new double[3];for(int c=0;c<3;c++)p[c]=(double)selected[c]/sum;return p;
        }
    }
    static Cuts fit(Input[] input){
        if(input==null||input.length==0||Arrays.stream(input).anyMatch(i->i==null))throw new IllegalArgumentException("训练状态不能为空");
        int index=(int)Math.ceil(input.length*.75)-1;
        double[] volatility=Arrays.stream(input).mapToDouble(Input::volatility).sorted().toArray();
        double[] gap=Arrays.stream(input).mapToDouble(i->Math.abs(i.gap())).sorted().toArray();
        return new Cuts(volatility[index],gap[index]);
    }
    static Prior train(Input[] input,int[] labels){
        var cuts=fit(input);
        if(labels==null||input.length!=labels.length||Arrays.stream(labels).anyMatch(c->c<0||c>2))throw new IllegalArgumentException("训练状态标签非法");
        int[][] counts=new int[8][3];int[] global=new int[3];
        for(int i=0;i<input.length;i++){counts[cuts.cell(input[i])][labels[i]]++;global[labels[i]]++;}
        return new Prior(cuts,counts,global);
    }
    // 去掉当日涨跌，得到 D-20 到 D-1 的19个交易间隔趋势。
    static double priorTrend(Input input){return ((1+input.return20()/100)/(1+input.return1()/100)-1)*100;}
}
