import { useState } from 'react';
import { api, BizError, yuan } from '../api';

export default function Publish() {
  const [title, setTitle] = useState('');
  const [reward, setReward] = useState('15');
  const [type, setType] = useState('DELIVERY');
  const [msg, setMsg] = useState<string | null>(null);

  const submit = async () => {
    try {
      const rewardCents = Math.round(parseFloat(reward) * 100);
      if (!title.trim()) throw new Error('请填写任务标题');
      if (!rewardCents || rewardCents <= 0) throw new Error('悬赏金额必须大于 0');
      const r = await api.publish({ title: title.trim(), rewardCents, slotTotal: 1, type });
      setMsg(`✅ 发布成功，悬赏 ${yuan(rewardCents)} 已从余额托管。任务 #${String(r.errandId).slice(-8)}`);
      setTitle('');
    } catch (e) {
      setMsg(e instanceof BizError ? `❌ ${e.message}（${e.code}）` : `❌ ${(e as Error).message}`);
    }
  };

  return (
    <div className="publish-page">
      <h2>发布任务</h2>
      <p className="muted">
        发布即托管：悬赏金额在一个数据库事务内完成"余额扣减 → 复式记账 → 托管单 → 任务上架"，
        余额不足会整体回滚，不会留下半发布状态的任务。
      </p>
      <div className="panel form-panel">
        <label>任务标题
          <input value={title} onChange={(e) => setTitle(e.target.value)}
                 placeholder="例如：帮我从三食堂带一份饭到 6 号楼" />
        </label>
        <label>悬赏金额（元）
          <input type="number" min="1" step="0.5" value={reward}
                 onChange={(e) => setReward(e.target.value)} />
        </label>
        <label>类型
          <select value={type} onChange={(e) => setType(e.target.value)}>
            <option value="DELIVERY">跑腿配送</option>
            <option value="BUY">代购</option>
            <option value="QUEUE">代排队</option>
            <option value="OTHER">其他</option>
          </select>
        </label>
        <button className="btn btn-deliver" onClick={submit}>发布并托管</button>
        {msg && <div className="banner info">{msg}</div>}
      </div>
    </div>
  );
}
