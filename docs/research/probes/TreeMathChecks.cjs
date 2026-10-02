// 手工树与已知softmax概率是数学夹具，不是黄金行情。
const assert=require('node:assert/strict'),{predict}=require('./TreeMath.cjs');
const names=Array.from({length:20},(_,i)=>'x'+i),scale={mean:Array(20).fill(0),std:Array(20).fill(1)};
const leaf=value=>({left_children:[-1],right_children:[-1],split_indices:[0],split_conditions:[value],default_left:[0]});
const model={featureOrder:names,labelOrder:['BULLISH','NEUTRAL','BEARISH'],state:{learner:{learner_model_param:{base_score:'5E-1'},gradient_booster:{model:{tree_info:[0,1,2],trees:[
  {left_children:[1,-1,-1],right_children:[2,-1,-1],split_indices:[0,0,0],split_conditions:[.5,1,-1],default_left:[1,0,0]},leaf(0),leaf(-1)]}}}}};
const near=(a,b)=>assert.ok(Math.abs(a-b)<1e-6,`${a} != ${b}`);
const x=Array(20).fill(0);near(predict(model,x,names,scale)[0],.6652409558);
x[0]=.5;near(predict(model,x,names,scale)[1],.5761168848); // 等于阈值走右枝，不用<=。
const changed=structuredClone(model);changed.featureOrder=[names[1],names[0],...names.slice(2)];
near(predict(changed,x,names,scale)[0],.6652409558); // 原生特征ID必须映射回正确字段。
changed.labelOrder=['BEARISH','BULLISH','NEUTRAL'];near(predict(changed,x,names,scale)[2],.6652409558);
console.log('PASS: 树枝严格边界/特征ID映射/类别ID映射/已知softmax');
