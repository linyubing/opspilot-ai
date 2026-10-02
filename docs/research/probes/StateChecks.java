package com.opspilot.ai.forecast.learning;

/** 状态审计数学测试，夹具不是市场行情。 */
public class StateChecks {
    public static void main(String[] args){cuts();trend();bits();frequencies();invalid();System.out.println("PASS: 训练分位数/边界/前趋势/互斥状态/频率控制/非法输入");}
    static StateCuts.Input input(double v,double g,double r,double t){return new StateCuts.Input(v,g,r,t);}
    // 使用验证分布或分位数插值会破坏手工期望。
    static void cuts(){
        var x=new StateCuts.Input[]{input(4,4,0,0),input(1,1,0,0),input(3,-3,0,0),input(2,2,0,0)};
        var cuts=StateCuts.fit(x);near(cuts.volatility(),3);near(cuts.gap(),3);
        require(cuts.cell(input(3,-3,0,0))==0,"相等阈值必须在低组");
        require(cuts.cell(input(4,-4,0,0))==6,"超过阈值同时设置波动与缺口位");
        near(StateCuts.fit(new StateCuts.Input[]{input(7,-8,0,0)}).gap(),8);
    }
    static void trend(){
        near(StateCuts.priorTrend(input(1,0,10,21)),10);
        near(StateCuts.priorTrend(input(1,0,-1,8.9)),10);
        require(new StateCuts.Cuts(2,2).cell(input(1,0,-1,8.9))==1,"下跌日与此前上涨趋势冲突");
        require(new StateCuts.Cuts(2,2).cell(input(1,0,0,8.9))==0,"零收益不判冲突");
    }
    static void bits(){
        var cuts=new StateCuts.Cuts(2,2);
        for(int cell=0;cell<8;cell++){
            var in=input((cell&4)==0?1:3,(cell&2)==0?1:-3,(cell&1)==0?1:-1,8.9);
            require(cuts.cell(in)==cell,"三个独立布尔状态位不匹配");
        }
    }
    static void frequencies(){
        var x=new StateCuts.Input[40];int[] y=new int[40];
        // 30条低组恰好满足回退边界，低组类别20/5/5；高组类别0/10/0。
        for(int i=0;i<40;i++){x[i]=input(i<30?1:2,0,0,0);y[i]=i<20?0:i<25?1:i<30?2:1;}
        var prior=StateCuts.train(x,y);double[] low=prior.predict(input(1,0,0,0)),high=prior.predict(input(2,0,0,0));
        near(low[0],2.0/3);near(low[1],1.0/6);near(low[2],1.0/6);
        near(high[0],.5);near(high[1],.375);near(high[2],.125);
        require(prior.counts()[0][0]==20&&prior.counts()[4][1]==10,"训练状态真实计数错误");
        double[] again=prior.predict(input(100,100,1,1));near(again[0],.5);
    }
    static void invalid(){
        rejects(()->StateCuts.fit(new StateCuts.Input[0]));rejects(()->StateCuts.fit(new StateCuts.Input[]{input(-1,0,0,0)}));
        rejects(()->StateCuts.fit(new StateCuts.Input[]{input(1,Double.NaN,0,0)}));
        rejects(()->StateCuts.priorTrend(input(1,0,-100,0)));
        rejects(()->StateCuts.train(new StateCuts.Input[]{input(1,0,0,0)},new int[]{3}));
        rejects(()->StateCuts.train(new StateCuts.Input[]{input(1,0,0,0)},new int[0]));
    }
    static void rejects(Runnable r){boolean rejected=false;try{r.run();}catch(IllegalArgumentException expected){rejected=true;}require(rejected,"非法状态输入必须拒绝");}
    static void near(double a,double b){require(Double.isFinite(a)&&Math.abs(a-b)<1e-10,a+" != "+b);}
    static void require(boolean yes,String message){if(!yes)throw new AssertionError(message);}
}
