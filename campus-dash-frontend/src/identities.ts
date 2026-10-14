export const IDENTITIES = [
  { userId: 1001, name: '发单人', role: '发单同学', desc: '发布委托，省下时间做喜欢的事', icon: 'package' },
  { userId: 2001, name: '泡', role: '跑腿同学', desc: '顺路帮个忙，让每一程都有收获', icon: 'bag' },
  { userId: 2002, name: '跑腿同学 B', role: '跑腿同学', desc: '另一位接单身份，体验多人协作', icon: 'users' },
  { userId: 9001, name: '平台仲裁员', role: '仲裁员', desc: '查看争议，协助处理资金归属', icon: 'shield' },
];

// 昵称仅用于展示；用字符串匹配，避免影响后端用户 ID 的精度与身份判断。
export function displayUserName(userId: string | number) {
  const id = String(userId);
  return IDENTITIES.find(identity => String(identity.userId) === id)?.name ?? `同学 #${id.slice(-6)}`;
}
