'use strict';
const {collect,publish}=require('./RiskArchive.cjs');
const fs=require('node:fs'),path=require('node:path');
// 凭据只用于请求；外部错误不输出URL、原始异常或服务器正文。
async function main(){
  const key=process.env.FRED_API_KEY,out=process.argv[2];
  if(!key||!out||process.argv.length!==3)throw Error('请配置FRED_API_KEY并指定新的本地归档目录');
  const target=fs.realpathSync(path.resolve(__dirname,'../../../backend/target')),rel=path.relative(target,path.resolve(out));
  if(!rel||rel.startsWith('..')||path.isAbsolute(rel)||fs.existsSync(out))throw Error('归档必须使用target内不存在的新目录');
  let requests=0;
  const archive=await collect(async(route,params)=>{
    const url=new URL('https://api.stlouisfed.org/fred/'+route);for(const [k,v]of Object.entries({series_id:'VIXCLS',file_type:'json',...params,api_key:key}))url.searchParams.set(k,String(v));
    let response;try{response=await fetch(url,{signal:AbortSignal.timeout(45000)});}catch{throw Error('FRED网络请求失败，未发布归档');}
    if(!response.ok)throw Error('FRED接口失败，HTTP='+response.status);
    let data;try{data=await response.json();}catch{throw Error('FRED返回非JSON响应');}
    requests++;console.error('VIX采集进度：已完成请求'+requests);return data;
  });
  publish(out,archive);const manifest=JSON.parse(fs.readFileSync(path.join(out,'manifest.json'),'utf8'));
  console.log(JSON.stringify({...manifest,firstAvailableVintage:archive.firstAvailableVintage,chunks:archive.chunks,requests}));
}
main().catch(error=>{console.error(error.name==='AssertionError'?'FRED历史归档结构校验失败，未发布':error.message);process.exitCode=1;});
