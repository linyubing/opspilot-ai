const {test}=require('node:test'),assert=require('node:assert/strict');
const {outcome,auc}=require('./DirectionMath.cjs');
// 仅用手算数学夹具，绝非黄金行情。
test('严格±0.5%分类且幅度边界不会被浮点误差改变',()=>{
  const cases=[['100','100.5','NEUTRAL','WITHIN_0_5'],['100','99.5','NEUTRAL','WITHIN_0_5'],
    ['100','100.500001','BULLISH','EDGE_0_5_0_6'],['100','99.4','BEARISH','EDGE_0_5_0_6'],
    ['100','101','BULLISH','MID_0_6_1'],['100','98','BEARISH','LARGE_1_2'],['100','102.000001','BULLISH','OVER_2']];
  for(const [base,target,label,group]of cases)assert.deepEqual(outcome(base,target),{label,group});
  assert.throws(()=>outcome('0','1'));assert.throws(()=>outcome('100','NaN'));
});
test('AUC按正负例排序计算，相等半分，缺类不能伪造0.5',()=>{
  assert.equal(auc([{positive:true,p:.8},{positive:false,p:.2}]),1);
  assert.equal(auc([{positive:true,p:.2},{positive:false,p:.8}]),0);
  assert.equal(auc([{positive:true,p:.5},{positive:false,p:.5}]),.5);
  assert.equal(auc([{positive:true,p:.9}]),null);
  assert.equal(auc([]),null);
  assert.equal(auc([{positive:true,p:.9},{positive:true,p:.5},{positive:false,p:.5},{positive:false,p:.1}]),.875);
  assert.throws(()=>auc([{positive:true,p:NaN},{positive:false,p:.2}]));
});
