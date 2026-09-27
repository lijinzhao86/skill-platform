---
name: skillmaster
description: 按需取用已托管的 skill。当任务需要特定领域能力（文档处理、数据分析、
  飞书操作、代码规范等）时，先用它检索可用的 skill，再按检索结果取用。
metadata:
  platform_api_version: "1"
---

# 我的 skill 库

服务地址：`https://<host>`

## 使用协议（按顺序，不要跳步）

1. 检索：`skillmaster search "<关键词>"`
   → 只返回名称与描述。**先看描述判断相关性，不要一次搜很多词。**
2. 详情：`skillmaster show <id>`
   → 返回完整文件清单但**没有内容**。据此判断这个 skill 有没有你要的那部分。
3. 取正文：`skillmaster get <id>`
4. 取文件：正文里引用到的文件才取 `skillmaster get <id> <relpath>`

## 停止条件

- 搜索结果没有相关项 → 直接告诉用户没找到，**不要**逐个试。
- 清单里有文件但正文没引用 → **不要取**。
