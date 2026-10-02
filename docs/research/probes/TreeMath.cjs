// 独立读取原生JSON树；不调用Java/Tribuo预测器。
function predict(model,x,names,scale){
  const f=Math.fround,l=model.state.learner,m=l.gradient_booster.model;
  const input=model.featureOrder.map(name=>{const j=names.indexOf(name);if(j<0)throw new Error('原生特征ID未知');return f(scale.std[j]===0?0:(x[j]-scale.mean[j])/scale.std[j]);});
  const margin=Array(3).fill(f(Number(l.learner_model_param.base_score)));
  for(let i=0;i<m.trees.length;i++){
    const tree=m.trees[i];let node=0,steps=0;
    while(tree.left_children[node]!==-1){if(++steps>tree.left_children.length)throw new Error('树存在环');
      const value=input[tree.split_indices[node]],left=Number.isNaN(value)?tree.default_left[node]!==0:value<f(tree.split_conditions[node]);
      node=left?tree.left_children[node]:tree.right_children[node];}
    margin[m.tree_info[i]]=f(margin[m.tree_info[i]]+f(tree.split_conditions[node]));
  }
  const max=Math.max(...margin),e=margin.map(v=>f(Math.exp(f(v-max))));let sum=0;for(const v of e)sum=f(sum+v);
  const p=e.map(v=>f(v/sum));return['BULLISH','NEUTRAL','BEARISH'].map(name=>p[model.labelOrder.indexOf(name)]);
}
module.exports={predict};
