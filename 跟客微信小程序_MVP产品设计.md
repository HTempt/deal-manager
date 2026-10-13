# 跟客微信小程序 MVP 产品设计

## 1. 产品概述

| 项目 | 定义 |
|---|---|
| 产品名称 | 跟客 |
| 产品定位 | AI 客户跟进助手（客户跟进管理工具） |
| 一句话定位 | 客户来了以后，不让他被忘记 |
| 产品形态 | 微信小程序 |
| 核心用户 | 个体老板、小微企业销售、服务行业从业者 |
| 核心痛点 | 客户咨询后无人持续跟进，导致客户流失 |
| 核心价值 | 告诉用户“今天应该跟谁”，并帮助用户完成跟进 |
| MVP 核心闭环 | 新增客户 → 设置跟进 → 到期提醒 → 跟进记录 → AI话术 → 成交 |
| 商业模式 | 免费版 + 订阅会员 |

### 核心产品指标

第一版重点关注：

```text
新增客户数
↓
产生跟进任务的客户数
↓
完成跟进任务数
↓
报价客户数
↓
成交客户数
↓
成交金额
```

最终验证：

> 使用跟客以后，用户是否真的跟进了更多客户、更少客户被忘记，成交是否随之增加？

---

# 2. 信息架构

整个小程序严格控制为 5 个一级页面。

```text
跟客
│
├── ① 首页
│   ├── 今日待跟进
│   ├── 高意向客户
│   └── 逾期客户
│
├── ② 客户
│   ├── 客户列表
│   ├── 新增客户
│   └── 客户详情
│       ├── 基本信息
│       ├── 客户状态
│       ├── 跟进记录
│       └── 新增跟进
│
├── ③ 跟进
│   ├── 今日任务
│   ├── 逾期任务
│   └── AI话术
│
├── ④ 数据
│   ├── 新增客户
│   ├── 成交金额
│   └── 渠道来源
│
└── ⑤ 我的
    ├── 行业设置
    ├── AI设置
    ├── 会员
    └── 基础设置
```

---

# 3. 产品 PRD

## 3.1 首页

### 产品目标

用户打开小程序后，3 秒内知道今天要做什么。

### 页面结构

```text
┌──────────────────────────────┐
│  早上好，张老板               │
│  今天有 8 个客户需要处理      │
│                              │
│  ┌──────────┬──────────┐     │
│  │ 今日待跟进 │ 高意向客户 │     │
│  │    8      │    3     │     │
│  └──────────┴──────────┘     │
│                              │
│  ┌────────────────────────┐  │
│  │ ⚠ 逾期客户             │  │
│  │ 2 个客户超过跟进时间    │  │
│  │              查看全部 > │  │
│  └────────────────────────┘  │
│                              │
│  今日优先跟进                │
│                              │
│  ┌────────────────────────┐  │
│  │ 🔥 张先生               │  │
│  │ 装修 / 预算8-10万       │  │
│  │ 报价后 3 天未联系       │  │
│  │ [AI生成话术] [跟进]     │  │
│  └────────────────────────┘  │
│                              │
├──────────────────────────────┤
│ 首页 客户 跟进 数据 我的      │
└──────────────────────────────┘
```

### 今日待跟进

统计条件：

```sql
next_follow_time <= 当前时间
AND status NOT IN ('成交','流失')
```

点击进入「跟进 → 今日任务」。

### 高意向客户

MVP 第一版采用简单规则：

| 分值 | 等级 |
|---:|---|
| 0-39 | 普通 |
| 40-69 | 较高 |
| 70-89 | 高意向 |
| 90-100 | 极高 |

行为加减分：

| 行为 | 分值 |
|---|---:|
| 新增客户 | +10 |
| 主动咨询 | +10 |
| 询问价格 | +15 |
| 索要方案 | +20 |
| 已报价 | +20 |
| 主动回复 | +10 |
| 预约 | +20 |
| 长时间未回复 | -10 |
| 明确拒绝 | -30 |

第一版允许人工修改意向等级。

### 逾期客户

判断：

```text
当前时间 > next_follow_time
AND 客户未成交、未流失
```

展示逾期数量，并提供立即跟进入口。

---

# 4. 客户模块

## 4.1 客户列表

```text
┌──────────────────────────────┐
│ 客户                     ＋  │
│                              │
│ 🔍 搜索客户                   │
│                              │
│ 全部 待跟进 高意向 已成交      │
│                              │
│ ┌──────────────────────────┐ │
│ │ 🔥 张先生                 │ │
│ │ 装修                     │ │
│ │ ¥80,000 · 报价中          │ │
│ │ 3天未跟进                 │ │
│ └──────────────────────────┘ │
│                              │
│ ┌──────────────────────────┐ │
│ │ 🟡 李女士                 │ │
│ │ 婚纱摄影                 │ │
│ │ ¥15,000 · 考虑中          │ │
│ │ 今天跟进                  │ │
│ └──────────────────────────┘ │
└──────────────────────────────┘
```

筛选：

```text
全部
待跟进
高意向
报价中
已成交
已流失
```

---

## 4.2 新增客户

目标：10~20 秒完成。

```text
新增客户

姓名 *
[ 张先生              ]

客户所在城市 *
[ 杭州市              ]

详细地址
[ 西湖区文三路 100 号 ]

手机号
[ 138xxxxxxxx         ]

客户需求 *
[ 100㎡三室两厅装修    ]

预计金额
[ 80000              ]

客户来源
[ 抖音 ▼             ]

客户阶段
[ 新客户 ▼            ]

下次跟进
[ 明天 10:00 ▼        ]

[保存客户]
```

MVP 必填：

- 姓名
- 所在城市
- 需求

详细地址和其他字段选填。

---

## 4.3 客户详情

```text
┌──────────────────────────────┐
│ ← 客户详情             ⋯     │
│                              │
│ 🔥 张先生                    │
│ 装修                         │
│ ¥80,000                      │
│                              │
│ 当前阶段                     │
│ [报价中]                     │
│                              │
│ 下次跟进                     │
│ 今天 15:00                   │
│                              │
│ ─────────────────────────── │
│ 客户信息                     │
│ 手机：138****8888            │
│ 来源：抖音                   │
│ 需求：100㎡三室两厅装修       │
│                              │
│ ─────────────────────────── │
│ 跟进记录                     │
│                              │
│ 09-16                         │
│ 发送第二版报价方案            │
│                              │
│ 09-14                         │
│ 客户觉得价格偏高              │
│                              │
│ 09-12                         │
│ 客户咨询全包价格              │
│                              │
│ [＋记录跟进]                  │
└──────────────────────────────┘
```

---

# 5. 跟进记录

点击「记录跟进」：

```text
本次跟进

跟进方式

○ 微信
○ 电话
○ 面谈
○ 其他

客户反馈

[ 客户觉得报价有点高      ]

客户状态

○ 没有回应
○ 有兴趣
○ 考虑中
○ 准备成交
○ 已成交
○ 已流失

下次跟进

○ 明天
○ 3天后
○ 7天后
○ 自定义

[保存跟进]
```

保存后：

```text
last_follow_time = 当前时间
next_follow_time = 用户选择时间
follow_count += 1
```

---

# 6. 跟进模块

## 6.1 今日任务

```text
跟进

今日待办 8
逾期 2

────────────────

今天

□ 张先生
  报价后跟进
  ¥80,000
  [AI话术] [跟进]

□ 李女士
  确认方案
  ¥15,000
  [AI话术] [跟进]

□ 王先生
  电话回访
  ¥50,000
  [AI话术] [跟进]
```

完成任务：

```text
点击「跟进」
↓
记录跟进
↓
自动完成当前任务
↓
生成下一次跟进任务
```

---

## 6.2 逾期任务

```text
⚠️ 逾期 2 个

张先生
原定：9月14日
已逾期：2天

[立即跟进]

────────────────

王女士
原定：9月15日
已逾期：1天

[立即跟进]
```

排序建议：

```text
意向度
↓
预计金额
↓
逾期时间
```

---

# 7. AI 话术

AI 不做独立聊天机器人，而是嵌入客户跟进场景。

例如：

```text
张先生

客户阶段：报价中
预算：8-10万
最后反馈：觉得价格高
逾期：2天

请选择：

[首次跟进]

[报价后跟进]

[客户嫌贵]

[客户不回复]

[促成成交]

[唤醒客户]
```

AI 输出：

```text
张哥，理解，装修预算确实需要好好控制。

您之前比较关注整体预算，我这边可以再帮您
看看哪些地方可以优化一下，在不影响整体效果
的情况下尽量控制成本。

如果您方便，我重新给您调整一版方案。
```

操作：

```text
[复制]
[重新生成]
[更简短]
[更自然]
```

---

## 7.1 AI 请求参数

```json
{
  "customer_name": "张先生",
  "industry": "装修",
  "customer_stage": "报价中",
  "budget": 80000,
  "need": "100㎡三室两厅装修",
  "last_feedback": "觉得价格偏高",
  "days_without_contact": 2,
  "scenario": "客户嫌贵"
}
```

AI 输出：

```json
{
  "content": "......",
  "reason": "客户对价格存在疑虑，建议先降低价格压力，再提供方案优化。",
  "next_action": "3天后再次跟进"
}
```

---

# 8. 数据模块

第一版不要做复杂 BI。

## 8.1 数据首页

```text
数据

本月 ▼

────────────────

新增客户

128

较上月 +18%

────────────────

成交金额

¥486,000

────────────────

成交客户

17

────────────────

客户来源

抖音       42
微信       31
小红书     27
转介绍     18
其他       10
```

---

## 8.2 渠道来源

```text
渠道       新增客户    成交客户    成交金额
────────────────────────────────
抖音          42          7       ¥186,000
微信          31          4       ¥102,000
小红书        27          3        ¥72,000
转介绍        18          3        ¥86,000
其他          10          0             -
```

核心目的是让用户知道：

> 哪个渠道真正带来了客户和成交。

---

# 9. 成交记录

客户状态变为「已成交」时：

```text
确认成交

成交金额
[ 80000 ]

成交日期
[ 2026-09-16 ]

产品/服务
[ 全屋装修 ]

[确认成交]
```

系统自动更新：

```text
客户状态
成交金额
成交日期
数据统计
渠道成交金额
```

---

# 10. 我的

```text
我的

┌────────────────────────┐
│ 跟客                    │
│ 免费版                  │
│                        │
│ 已使用客户 68 / 100     │
│ [升级会员]              │
└────────────────────────┘

功能设置

行业设置              >
AI设置                >
会员中心              >

其他

通知设置              >
账号设置              >
关于我们              >
```

---

# 11. 行业设置

```text
选择你的行业

○ 装修
○ 婚庆
○ 摄影
○ 家政
○ 教培
○ 健身
○ 汽车销售
○ 房产
○ 家居
○ 美容
○ 其他
```

行业会影响：

- 客户阶段
- AI话术
- AI跟进场景
- 默认字段

例如装修：

```text
需求沟通
→ 方案中
→ 报价中
→ 谈判中
→ 成交
→ 流失
```

婚庆：

```text
婚期咨询
→ 套餐沟通
→ 档期确认
→ 报价
→ 定金
→ 成交
```

---

# 12. AI 设置

```text
AI设置

回复风格

● 自然亲切
○ 专业正式
○ 简洁直接
○ 销售型

称呼方式

● 哥/姐
○ 您
○ 老板
○ 自动判断

回复长度

○ 简短
● 适中
○ 详细
```

用户还可以填写：

```text
我的业务介绍

我的优势

我的常用表达
```

这些内容作为 AI Prompt 的长期上下文。

---

# 13. 会员

```text
免费版

客户数量
68 / 100

AI次数
18 / 30

历史数据
√

────────────────

专业版

¥29/月

不限客户
500次 AI
高级数据
沉睡客户
AI跟进计划

[立即开通]
```

后续可以增加团队版。

---

# 14. 数据库设计

推荐：

```text
MySQL + Redis
```

第一版数据库控制在 8 张核心表。

---

## 14.1 user 用户表

```sql
CREATE TABLE user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    openid VARCHAR(64) NOT NULL UNIQUE,
    nickname VARCHAR(100),
    avatar_url VARCHAR(500),
    phone VARCHAR(20),
    industry VARCHAR(50),
    membership_type VARCHAR(20) DEFAULT 'FREE',
    membership_expire_time DATETIME,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);
```

---

## 14.2 customer 客户表

```sql
CREATE TABLE customer (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,

    name VARCHAR(100) NOT NULL,
    phone VARCHAR(20),

    need TEXT,
    budget DECIMAL(12,2),

    source VARCHAR(50),

    stage VARCHAR(30) DEFAULT 'NEW',

    intention_score INT DEFAULT 10,

    last_follow_time DATETIME,
    next_follow_time DATETIME,

    deal_amount DECIMAL(12,2) DEFAULT 0,
    deal_time DATETIME,

    status VARCHAR(20) DEFAULT 'ACTIVE',

    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,

    INDEX idx_user_id(user_id),
    INDEX idx_next_follow_time(next_follow_time),
    INDEX idx_intention_score(intention_score)
);
```

---

## 14.3 follow_record 跟进记录

```sql
CREATE TABLE follow_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,

    customer_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,

    follow_type VARCHAR(20),
    content TEXT,

    customer_feedback VARCHAR(50),

    intention_change INT DEFAULT 0,

    next_follow_time DATETIME,

    created_at DATETIME NOT NULL,

    INDEX idx_customer_id(customer_id),
    INDEX idx_user_id(user_id)
);
```

---

## 14.4 follow_task 跟进任务

```sql
CREATE TABLE follow_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,

    user_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,

    task_type VARCHAR(30),

    title VARCHAR(200),

    plan_time DATETIME,

    completed_time DATETIME,

    status VARCHAR(20) DEFAULT 'PENDING',

    created_at DATETIME NOT NULL,

    INDEX idx_user_task(user_id, status),
    INDEX idx_plan_time(plan_time)
);
```

状态：

```text
PENDING
COMPLETED
CANCELLED
```

---

## 14.5 ai_record AI调用记录

```sql
CREATE TABLE ai_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,

    user_id BIGINT NOT NULL,
    customer_id BIGINT,

    scenario VARCHAR(50),

    prompt TEXT,
    response TEXT,

    model VARCHAR(50),

    token_usage INT,

    created_at DATETIME NOT NULL
);
```

用途：

- AI 调用统计
- 会员额度控制
- 后期分析 AI 使用场景

---

## 14.6 deal 成交表

```sql
CREATE TABLE deal (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,

    user_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,

    amount DECIMAL(12,2) NOT NULL,

    product_name VARCHAR(200),

    deal_time DATETIME NOT NULL,

    source VARCHAR(50),

    created_at DATETIME NOT NULL,

    INDEX idx_user_time(user_id, deal_time)
);
```

单独建立成交表，是为了支持：

```text
一个客户
→ 多次成交
```

---

## 14.7 industry_config 行业配置

```sql
CREATE TABLE industry_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,

    industry_code VARCHAR(50) NOT NULL,
    industry_name VARCHAR(100) NOT NULL,

    stages JSON,
    ai_scenarios JSON,

    created_at DATETIME NOT NULL
);
```

示例：

```json
{
  "industry": "装修",
  "stages": [
    "新客户",
    "需求沟通",
    "方案中",
    "报价中",
    "谈判中",
    "成交",
    "流失"
  ]
}
```

---

## 14.8 user_ai_setting 用户 AI 设置

```sql
CREATE TABLE user_ai_setting (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,

    user_id BIGINT NOT NULL,

    reply_style VARCHAR(30),
    reply_length VARCHAR(20),
    preferred_address VARCHAR(30),

    business_intro TEXT,
    business_advantage TEXT,
    common_expression TEXT,

    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);
```

---

## 14.9 membership_order 会员订单

虽然核心业务表控制在 8 张左右，但商业化上线需要增加会员订单表：

```sql
CREATE TABLE membership_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,

    user_id BIGINT NOT NULL,

    order_no VARCHAR(64) UNIQUE NOT NULL,

    plan VARCHAR(30),
    amount DECIMAL(10,2),

    status VARCHAR(20),

    pay_time DATETIME,

    expire_time DATETIME,

    created_at DATETIME NOT NULL
);
```

---

# 15. 数据库关系

```text
                    user
                     │
       ┌─────────────┼─────────────┐
       ↓             ↓             ↓
   customer      ai_setting    membership
       │
       ├──────────────┐
       ↓              ↓
 follow_record    follow_task
       │
       ↓
      deal

customer
   │
   ↓
ai_record
```

---

# 16. 微信小程序技术架构

推荐：

```text
微信小程序
    │
    │ HTTPS
    ↓
API Gateway / Nginx
    │
    ↓
Spring Boot
    │
 ┌──┼──────────────┐
 ↓  ↓              ↓
MySQL Redis       AI Service
                    │
                    ↓
               LLM API
```

---

# 17. 前端技术栈

推荐：

```text
微信原生小程序
TypeScript
```

第一版没有必要引入复杂跨端方案。

目录：

```text
miniprogram/

├── pages/
│   ├── home/
│   ├── customer/
│   ├── customer-detail/
│   ├── follow/
│   ├── ai/
│   ├── data/
│   └── mine/
│
├── components/
│   ├── customer-card/
│   ├── follow-task/
│   ├── stat-card/
│   ├── empty-state/
│   └── ai-dialog/
│
├── services/
│   ├── user.ts
│   ├── customer.ts
│   ├── follow.ts
│   ├── data.ts
│   └── ai.ts
│
├── utils/
│   ├── request.ts
│   ├── date.ts
│   └── format.ts
│
└── app.ts
```

---

# 18. 后端技术栈

推荐：

```text
Java 21
Spring Boot 3.x
MyBatis-Plus
MySQL 8
Redis
JWT / 微信登录态
```

第一版：

```text
Spring Boot
+
MySQL
+
Redis
```

即可。

---

# 19. AI 架构

不要让小程序直接调用 AI API。

错误架构：

```text
小程序
 ↓
AI API
```

正确架构：

```text
小程序
 ↓
Spring Boot
 ↓
AI Service
 ↓
AI模型
```

原因：

1. API Key 不暴露
2. 可以控制 AI 使用额度
3. 可以更换 AI 模型
4. 可以统一 Prompt
5. 可以记录 AI 使用情况

---

# 20. 核心 API

## 用户

```http
POST /api/auth/wx-login
GET  /api/user/profile
PUT  /api/user/profile
```

## 客户

```http
GET    /api/customers
POST   /api/customers
GET    /api/customers/{id}
PUT    /api/customers/{id}
DELETE /api/customers/{id}
```

## 跟进

```http
GET  /api/follows/today
GET  /api/follows/overdue
POST /api/follows
POST /api/follows/{id}/complete
```

## AI

```http
POST /api/ai/generate-script
POST /api/ai/regenerate-script
```

## 数据

```http
GET /api/data/overview
GET /api/data/source
GET /api/data/deals
```

## 设置

```http
GET /api/settings/industry
PUT /api/settings/industry

GET /api/settings/ai
PUT /api/settings/ai
```

---

# 21. 首页 API

首页建议只提供一个聚合接口：

```http
GET /api/home/overview
```

返回：

```json
{
  "todayFollowCount": 8,
  "highIntentCount": 3,
  "overdueCount": 2,

  "todayTasks": [
    {
      "customerId": 10001,
      "customerName": "张先生",
      "stage": "报价中",
      "budget": 80000,
      "intentionScore": 85,
      "daysWithoutFollow": 3
    }
  ]
}
```

避免首页请求大量接口。

---

# 22. AI 话术接口

```http
POST /api/ai/generate-script
```

Request：

```json
{
  "customerId": 10001,
  "scenario": "PRICE_TOO_HIGH"
}
```

后端自动获取：

```text
客户信息
+
历史跟进
+
行业
+
AI设置
+
客户阶段
```

然后生成 Prompt 并调用模型。

---

# 23. 跟进任务自动生成

核心业务逻辑：

```text
新增客户
    ↓
next_follow_time = 明天
    ↓
生成 follow_task
    ↓
到了明天
    ↓
进入今日任务
    ↓
用户完成跟进
    ↓
选择3天后
    ↓
生成下一条 follow_task
```

逾期判断：

```text
now > plan_time
AND status = PENDING
```

任务进入逾期状态。

---

# 24. MVP 开发清单

## Sprint 1：项目基础

```text
□ 微信小程序注册/配置
□ Spring Boot 项目
□ MySQL
□ Redis
□ 微信登录
□ 用户表
□ 登录态
□ 基础请求封装
```

## Sprint 2：客户

```text
□ 客户列表
□ 新增客户
□ 客户详情
□ 修改客户
□ 删除客户
□ 搜索
□ 状态筛选
□ 意向等级
```

## Sprint 3：跟进

```text
□ 跟进记录
□ 跟进任务
□ 今日任务
□ 逾期任务
□ 下次跟进
□ 完成任务
□ 自动生成下一任务
```

## Sprint 4：首页

```text
□ 今日待跟进
□ 高意向客户
□ 逾期客户
□ 今日优先客户
□ 首页统计
□ 快速跟进
```

## Sprint 5：AI

```text
□ AI接口
□ Prompt模板
□ 行业Prompt
□ 客户嫌贵
□ 客户不回复
□ 报价后跟进
□ 首次跟进
□ 促成成交
□ 复制话术
□ 重新生成
```

## Sprint 6：数据

```text
□ 新增客户统计
□ 成交金额
□ 成交客户
□ 渠道来源
□ 时间筛选
□ 月度统计
```

## Sprint 7：设置

```text
□ 行业设置
□ AI风格
□ AI长度
□ 企业介绍
□ 企业优势
□ 会员页面
□ 使用额度
```

## Sprint 8：测试上线

```text
□ 权限测试
□ 登录测试
□ 数据隔离测试
□ AI额度测试
□ 异常处理
□ 网络异常
□ 空数据页面
□ 加载状态
□ 防重复提交
□ 微信支付
□ 隐私协议
□ 用户协议
□ 小程序审核
```

---

# 25. MVP 第一版明确不做

第一版坚决不做：

```text
❌ 企业微信深度集成
❌ 群管理
❌ 自动群发
❌ 自动营销
❌ 复杂 BI
❌ 多组织架构
❌ 多级权限
❌ 销售排行榜
❌ OA
❌ ERP
❌ 库存
❌ 财务
❌ CRM 导入导出
❌ PC 后台
❌ 客户端商城
❌ 积分商城
❌ 裂变
```

目标是把项目控制在一个可快速验证的范围内。

---

# 26. MVP 核心流程

```text
用户登录
 ↓
选择行业：装修
 ↓
新增客户：张先生
 ↓
填写需求：100㎡装修
 ↓
预算：80000
 ↓
来源：抖音
 ↓
下次跟进：明天
 ↓
系统生成任务
 ↓
第二天首页显示
「今日待跟进：1」
 ↓
进入张先生
 ↓
点击「AI话术」
 ↓
选择「报价后跟进」
 ↓
生成话术
 ↓
用户复制微信发送
 ↓
回来记录跟进
 ↓
选择「3天后跟进」
 ↓
自动生成下一任务
 ↓
最终成交
 ↓
录入80000元
 ↓
数据页面：
抖音新增1
成交1
成交金额80000
```

这个闭环跑通，MVP 即成立。

---

# 27. 开发优先级

| 优先级 | 功能 | 重要程度 |
|---|---|---|
| P0 | 客户 | ⭐⭐⭐⭐⭐ |
| P0 | 跟进任务 | ⭐⭐⭐⭐⭐ |
| P0 | 首页待跟进 | ⭐⭐⭐⭐⭐ |
| P0 | 客户详情 | ⭐⭐⭐⭐⭐ |
| P0 | AI话术 | ⭐⭐⭐⭐⭐ |
| P1 | 数据统计 | ⭐⭐⭐⭐ |
| P1 | 行业设置 | ⭐⭐⭐⭐ |
| P1 | AI设置 | ⭐⭐⭐ |
| P1 | 会员 | ⭐⭐⭐ |
| P2 | 渠道分析 | ⭐⭐⭐ |
| P2 | 高级AI分析 | ⭐⭐ |
| P2 | 团队协作 | ⭐ |

---

# 28. 产品版本规划

## V1.0：客户 + 跟进 + AI

```text
客户
 ↓
跟进任务
 ↓
AI话术
 ↓
完成跟进
```

## V1.1：数据

```text
新增客户
成交金额
渠道来源
```

## V1.2：商业化

```text
免费额度
会员
微信支付
```

## V2.0：行业化

```text
装修版
婚庆版
摄影版
家政版
汽车版
...
```

## V3.0：团队化

```text
老板
 ↓
销售团队
 ↓
客户分配
 ↓
业绩
 ↓
团队数据
```

---

# 29. 产品核心定位

跟客不是传统 CRM，而是 AI 客户跟进助手。

传统 CRM 的核心是：

> 管理客户。

跟客的核心是：

> **客户来了以后，不让他被忘记。**

系统负责告诉老板现在应该做什么，并帮助老板把这件事做掉。成交统计只记录结果，不承担“别漏掉客户”的职责。

整个产品围绕：

```text
获客
 ↓
新客户
 ↓
记录需求
 ↓
AI判断阶段
 ↓
自动提醒跟进
 ↓
AI生成跟进话术
 ↓
报价
 ↓
跟进
 ↓
成交
 ↓
老客复购
 ↓
转介绍
 ↓
新客户
 ↺
```

每一个功能都应该服务于：

> **获客、跟进、成交、复购。**

---

# 30. 最终 MVP 页面清单

### 一级页面：5 个

```text
① 首页
② 客户
③ 跟进
④ 数据
⑤ 我的
```

### 二级页面/弹窗

```text
客户详情
新增客户
记录跟进
AI话术
行业设置
AI设置
会员
```

总页面控制在约 12 个。

---

# 31. 开发验收标准

MVP 上线前至少完成以下闭环：

- 用户可以微信登录
- 用户可以选择行业
- 用户可以新增客户
- 客户可以设置下次跟进时间
- 系统自动生成跟进任务
- 首页可以看到今日待跟进
- 首页可以看到逾期客户
- 用户可以记录跟进
- 完成跟进后可以生成下一次任务
- 用户可以从客户详情调用 AI 话术
- AI 话术可以复制
- 客户可以标记成交
- 系统可以统计成交金额
- 系统可以统计客户来源
- 不同用户之间的数据严格隔离
- 免费用户 AI 调用次数受到限制
- 会员用户可以获得更高额度
- 网络异常、空数据、重复提交等场景有明确处理

---

# 32. 一句话产品定位

> **跟客：客户跟进管理工具。客户来了以后，不让他被忘记。**
