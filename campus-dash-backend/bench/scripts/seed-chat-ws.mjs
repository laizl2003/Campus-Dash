const token=process.argv[2];
const total=10000;
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
 const ws=new WebSocket('ws://localhost:8088/ws?token='+encodeURIComponent(token));
 let received=0, errors=0, closed=false;
 ws.onmessage=e=>{
   try{const m=JSON.parse(e.data);
     if(m.type==='chat.message'&&m.payload?.senderId==='001'&&m.payload?.msgType==='TEXT'&&/^\d+$/.test(m.payload.content||'')) received++;
     if(m.type==='chat.error'){errors++; if(errors<3) console.error('error',m.payload);}
   }catch{}
 };
 ws.onclose=()=>{closed=true;};
 await new Promise((res,rej)=>{ws.onopen=res;ws.onerror=rej;});
 console.log('connected');
 for(let base=1;base<=total;base+=100){
   for(let n=base;n<Math.min(base+100,total+1);n++){
     ws.send(JSON.stringify({t:'chat.send',clientMsgId:'seed-ws-'+n,content:String(n)}));
   }
   await sleep(20);
 }
 const deadline=Date.now()+120000;
 while(received<total&&!closed&&Date.now()<deadline) await sleep(500);
 console.log(JSON.stringify({received,errors,closed,readyState:ws.readyState}));
 ws.close();
})().catch(e=>{console.error(e);process.exit(1)});