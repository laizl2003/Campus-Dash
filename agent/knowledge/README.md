# 知识库目录说明

这个目录下的 Markdown 会被 `app.knowledge.indexer` 自动同步进 MySQL，供 RAG 检索。
改完文件重启 agent 容器即可生效（按 content_hash 增量同步，只重建改过的文档）。

## 文件格式

```markdown
---
id: shop-fatixi          # 全局唯一，重建索引时用它 upsert / 删除
title: 肥西路周边商铺     # 人类可读标题，会拼进每个块的正文
category: shop           # shop / dorm / campus / manual
campus: outside          # west / mid / east / outside / all
tags: [商铺, 餐饮]        # 辅助过滤
updated_at: 2026-11-02   # 增量更新参考
---

# 一级标题（可选，会被剥掉，标题已在 metadata 里）

## 二级标题 = 一个检索块

正文……
```

## 切块规则

- 按 `##` 切分，每个二级标题下的内容是一个块；
- 单块超过 500 字按句子再切，相邻块留 50 字重叠；
- 每块开头自动补 `文档标题 / 节标题：`，保证块离开原文也能看懂。

所以：**一个店铺、一栋楼、一个场馆，各写一个 `##`**。

## 现有内容

| 文件 | 内容 |
| --- | --- |
| `dorms/west.md` | 西区 1-11 号楼 |
| `dorms/mid.md` | 中区 1-6 号楼 |
| `dorms/east.md` | 东区 301-330 号楼 |
| `outside/fatixi.md` | 肥西路商铺 |
| `outside/huangshan.md` | 黄山路商铺 |
| `campus/stores.md` | 校内学专超市 |
| `campus/print.md` | 打印店 |
| `campus/canteens.md` | 12 个食堂餐厅 |
| `campus/parcel.md` | 3 个快递驿站 |
| `campus/buildings-west.md` | 西区教学楼/实验楼 |
| `campus/buildings-east.md` | 东区教学楼/实验楼/校医院 |
| `campus/gates.md` | 7 个校门 |
| `campus/sports.md` | 健身房/场馆/操场 |

## 新增知识

1. 在对应目录建 `.md`，写 front-matter（`id` 必须唯一）；
2. 用 `##` 分隔每一小节；
3. 运行 `python scripts/build_kb.py` 或重启容器。
