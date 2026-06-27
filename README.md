# 🍽️ 点评 (dianping)

基于 Spring Boot + Redis 的点评类 Web 应用，重点学习并解决各种 Redis 缓存问题。

## 📋 项目简介

一个类似大众点评的本地生活服务平台后端，提供商户查询、探店笔记、用户关注、秒杀优惠券等功能。项目重点聚焦 **Redis 缓存实战**，系统性地覆盖了缓存穿透、缓存击穿、缓存雪崩等核心问题的解决方案。

## 🛠️ 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 2.3.12.RELEASE | 基础框架 |
| MyBatis-Plus | 3.4.3 | ORM 框架 |
| MySQL | 5.x | 关系型数据库 |
| Redis + Lettuce | 6.1.6 | 缓存、分布式锁、GEO |
| JWT | 0.9.1 | 用户认证 |
| Lombok | 1.18.36 | 简化代码 |
| Hutool | 5.7.17 | 工具类库 |
| JDK | 1.8 | 运行环境 |

## 🚀 快速开始

### 环境要求

- JDK 1.8+
- Maven 3.x
- MySQL 5.x
- Redis (需配置密码)

### 1. 初始化数据库

执行 `src/main/resources/db/hmdp.sql` 脚本，创建数据库表和初始数据：

```bash
mysql -u root -p < src/main/resources/db/hmdp.sql
```

### 2. 修改配置

编辑 `src/main/resources/application.yaml`，根据本地环境修改数据库和 Redis 连接信息：

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&serverTimezone=UTC
    username: root
    password: 你的密码
  redis:
    host: 127.0.0.1
    port: 6379
    password: 你的Redis密码
```

### 3. 启动项目

```bash
mvn spring-boot:run
```

项目默认运行在 **http://localhost:8081**

## 📦 功能模块

### 🏪 店铺管理 (`/shop`)
- 根据 ID 查询店铺（含缓存穿透、缓存击穿解决方案）
- 按类型分页查询店铺
- 基于 Redis GEO 的附近店铺搜索
- 店铺信息更新（数据库与缓存双写一致性）

### 📝 探店笔记 (`/blog`)
- 发布探店笔记（文字 + 图片）
- 笔记点赞与评论
- 热门笔记排行
- 关注用户笔记推送

### 👥 用户系统 (`/user`)
- 手机号 + 验证码登录
- JWT Token 认证与拦截
- 用户信息管理

### 🔔 关注功能 (`/follow`)
- 用户关注 / 取消关注
- 共同关注查询

### 🎫 秒杀优惠券 (`/voucher` / `/voucher-order`)
- 优惠券列表查询
- **秒杀抢购**：基于 Redis 的高并发秒杀实现
- 一人一单限制

### 📤 文件上传 (`/upload`)
- 博客图片上传

## 🔥 Redis 缓存实战

本项目核心围绕以下 Redis 缓存问题展开：

### 缓存穿透 (Cache Penetration)
> 查询一个**不存在的数据**，请求直接打到数据库

**解决方案：**
- **缓存空值**：对不存在的数据也写入空值到 Redis，设置较短 TTL
- 实现见 [`ShopServiceImpl.queryWithPassThrough()`](src/main/java/com/hmdp/service/impl/ShopServiceImpl.java)

### 缓存击穿 (Cache Breakdown)
> **热点 key 过期**瞬间，大量请求直接打到数据库

**解决方案：**
- **互斥锁（Mutex Lock）**：获取锁的线程去查库重建缓存，其他线程休眠重试
- **逻辑过期（Logical Expire）**：缓存不设 TTL，由异步线程负责缓存重建
- 实现见 [`ShopServiceImpl.queryWithMutex()` / `queryWithLogicExpire()`](src/main/java/com/hmdp/service/impl/ShopServiceImpl.java)

### 缓存雪崩 (Cache Avalanche)
> 大量缓存**同时过期**或 Redis 宕机

**解决方案：**
- 给缓存 TTL 添加随机值，避免同时过期
- 使用 Redis 集群 / 哨兵模式保证高可用

### 缓存工具类 (`CacheClient`)

封装的通用缓存客户端，支持：
- `set()` — 普通写入
- `setWithLogicalExpire()` — 逻辑过期写入
- `queryWithPassThrough()` — 缓存穿透防护
- `queryWithMutex()` — 互斥锁缓存击穿防护
- `queryWithLogicalExpire()` — 逻辑过期缓存击穿防护

详见 [`CacheClient.java`](src/main/java/com/hmdp/utils/CacheClient.java)

### 其他 Redis 应用场景

| 场景 | Redis 数据类型 | 说明 |
|------|---------------|------|
| 附近店铺搜索 | GEO | 按经纬度 + 距离范围查询 |
| 分布式锁 | String (SETNX) | 解决缓存击穿、秒杀并发 |
| 点赞集合 | Set / ZSet | 用户点赞去重与排行 |
| 关注列表 | Set | 共同关注交集运算 |
| 全局唯一 ID | String (INCR) | 分布式 ID 生成 |

## 📁 项目结构

```
hm-dianping
├── src/main/java/com/hmdp/
│   ├── config/          # 配置类（MVC、Redis、MyBatis、异常处理）
│   ├── controller/      # 前端控制器
│   ├── dto/             # 数据传输对象
│   ├── entity/          # 数据库实体
│   ├── mapper/          # MyBatis 数据访问层
│   ├── service/         # 服务接口
│   │   └── impl/        # 服务实现（含缓存逻辑）
│   └── utils/           # 工具类
│       ├── CacheClient.java      # 通用缓存客户端
│       ├── RedisConstants.java   # Redis Key 常量
│       ├── RedisIdWorker.java    # 分布式 ID 生成器
│       ├── RedisData.java        # 逻辑过期数据封装
│       ├── JwtUtils.java         # JWT 工具类
│       ├── LoginInterceptor.java # 登录拦截器
│       └── UserHolder.java       # 当前用户持有者
└── src/main/resources/
    ├── application.yaml  # 应用配置
    └── db/hmdp.sql       # 数据库初始化脚本
```

## 🔑 关键设计

### 数据库与缓存双写一致性

更新店铺时采用 **先更新数据库，再删除缓存** 策略：

```java
// ShopServiceImpl.update()
updateById(shop);                          // 1. 先更新数据库
stringRedisTemplate.delete(CACHE_SHOP_KEY + id); // 2. 再删除缓存
```

### JWT 登录流程

1. 用户发送手机号获取验证码（存入 Redis）
2. 验证码校验通过后签发 JWT Token
3. 后续请求通过 `LoginInterceptor` 解析 Token 并存入 `UserHolder`

### 秒杀业务

- 基于 Redis 库存预减 + 消息队列异步下单
- 通过 Redis 分布式锁保证一人一单

## 📖 学习要点

通过本项目可掌握：
1. ✅ Spring Boot + MyBatis-Plus 基础 CRUD 开发
2. ✅ Redis 五种基本数据结构在业务中的实际应用
3. ✅ 缓存穿透、缓存击穿、缓存雪崩的完整解决方案
4. ✅ Redis GEO 实现 LBS 附近搜索
5. ✅ Redis 分布式锁的原理与实现
6. ✅ 高并发秒杀业务的设计思路
7. ✅ JWT 无状态认证方案
8. ✅ 数据库与缓存双写一致性策略

---

*项目源自黑马程序员 Redis 实战课程*
