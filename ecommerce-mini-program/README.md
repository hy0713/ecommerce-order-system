# 轻量电商微信小程序用户端（阶段四）

C 端用户侧微信小程序,与微服务后端(`shop-parent`)、商家管理后台(`shop-web`)数据完全互通,形成「用户端小程序 + 商家管理后台 + 微服务后端」全栈闭环。

## 技术栈（版本锁定，与主项目同源）

| 分类 | 选型 | 版本 |
|---|---|---|
| 编译框架 | uni-app | 3.0.0-5010520260709002（编译器 5.15 / Vue3） |
| 核心语法 | Vue 3 组合式 API | 3.3.13 |
| 状态管理 | Pinia | 2.1.7 |
| UI 组件库 | uView Plus | 3.8.86（easycom 自动按需引入） |
| 构建 | Vite | 5.2.8 |
| 后端对接 | 微服务网关 | http://localhost:8080/api |

## 快速开始

```bash
npm install --registry=https://registry.npmmirror.com
npm run build:mp-weixin          # 产物 dist/build/mp-weixin
```

1. 确保后端运行：MySQL/Redis/RabbitMQ + Nacos + 4 个微服务（见 shop-parent/README）
2. 微信开发者工具 → 导入项目 → 选择 `dist/build/mp-weixin` 目录
3. AppID 使用测试号即可；开发者工具需勾选「不校验合法域名」（已通过 `manifest.json` 的 `urlCheck: false` 声明）
4. 登录使用演示账号 `admin / 123456`（与后端共用用户体系，管理后台同账号可见同数据）

## 页面清单（12 页，规格 3.1-3.10）

| 页面 | 路径 | 功能 |
|---|---|---|
| 登录 | /pages/login/login | 账号密码登录、微信一键登录（演示） |
| 首页 | /pages/index/index | 搜索、轮播、分类入口、热门商品双列 |
| 分类 | /pages/category/category | 左导航 + 右商品双列 |
| 商品详情 | /pages/product/detail | 图片轮播、加购、立即购买 |
| 商品列表 | /pages/product/list | 搜索 / 分类商品分页 |
| 购物车 | /pages/cart/cart | 选中、数量加减、删除、合计结算 |
| 订单确认 | /pages/order/confirm | 地址选择、金额汇总、提交订单 |
| 订单列表 | /pages/order/list | 6 状态 Tab、支付 / 取消 / 再次购买 |
| 订单详情 | /pages/order/detail | 状态、收货信息、明细、支付 / 取消 |
| 地址列表 | /pages/address/list | 增删改查、设默认、选择模式 |
| 地址编辑 | /pages/address/edit | 新增 / 编辑地址表单校验 |
| 个人中心 | /pages/mine/mine | 用户信息、订单入口、菜单、退出 |

TabBar 4 入口：首页 / 分类 / 购物车 / 我的。

## 接口对接（全部复用后端现有 RESTful 接口，经网关）

| 功能 | 方法与路径（实际后端，已逐一核对） |
|---|---|
| 登录 / 登出 / 用户信息 | POST `/auth/login`、POST `/auth/logout`、GET `/user/info` |
| 分类树 | GET `/category/tree` |
| 商品列表 / 详情 | GET `/product/page`（keyword/categoryId/status/分页）、GET `/product/{id}`（缓存） |
| 购物车 | GET `/cart/list`、POST `/cart`、PUT `/cart/{id}/quantity`、DELETE `/cart/{id}`、PUT `/cart/select-all?selected=` |
| 订单 | POST `/order`（addressId）、POST `/order/{id}/pay\|cancel`、GET `/order/page`、GET `/order/{id}` |
| 地址 | GET/POST `/user/address`、PUT/DELETE `/user/address/{id}`、PUT `/user/address/{id}/default` |

> 规格文档 3.x 中的示例路径（如 `/api/user/login`、`/api/product/list`）与后端实际接口不一致，按规格 1.1「接口路径与后端完全一致」要求，全部以实际后端为准，见上表。

## 核心设计

- **请求封装**（utils/request.js）：`uni.request` 二次封装，自动携带 `Authorization: Bearer {token}`；统一解析 `Result`（code=200 返回 data）；非 200 弹窗提示；401 清登录态跳登录页；支持 query 参数（`PUT /cart/select-all?selected=`）
- **路由鉴权**：需登录页面（购物车 / 订单 / 地址 / 个人中心）在 `onLoad`/`onShow` 校验 token，未登录跳登录页；登录成功 `navigateBack` 回跳原页面
- **登录态**：token + userInfo 持久化到 `uni.storage`，退出调用后端使 Redis token 失效
- **立即购买**：取消全选 → 加购（默认选中）→ 跳订单确认，保证只结算当前商品
- **购物车角标**：Pinia cart store 全局维护数量，登录 / 加购 / 结算后刷新
- **订单超时**：待支付订单 30 分钟由后端 MQ 自动取消并回补库存，订单详情页有提示
- **下拉刷新 / 上拉加载**：首页、商品列表、订单列表、购物车全部支持

## 与后端 / 管理后台的数据互通验证

- 小程序下单 → 管理后台「订单管理」实时可见（同一 MySQL）
- 管理后台上下架商品 → 小程序首页 / 分类 / 列表同步更新（商品详情走 Redis 缓存，30 分钟 TTL）
- 管理后台 `admin-cancel` 取消订单 → 小程序订单列表状态同步为「已取消」，库存回补

## 已知限制（演示版）

1. **微信一键登录为演示**：后端无微信 openid 体系，演示按钮引导使用账号密码登录
2. **购物车单选为演示实现**：后端仅提供「全选/取消全选」接口（`PUT /cart/select-all`），单商品勾选以全选状态模拟；下单结算范围 = 选中项，与后端一致
3. **tabBar 无图标**：使用纯文字（微信支持），待提供设计资源后补充 `iconPath`
4. **售后为占位**：后端无售后模块，订单详情「申请售后」为提示占位
5. **图片资源**：商品无 icon 时展示本地占位图 `static/placeholder.png`
6. **uView Plus 图标字体已本地化**：`setConfig` 覆盖 iconUrl 指向 `static/fonts/upicon.ttf`，不依赖阿里 CDN，离线可用

## 验收状态

| 规格 4.x 阶段 | 状态 |
|---|---|
| 4.1 基础工程（编译运行 / TabBar / 请求封装 / 鉴权） | ✅ `build:mp-weixin` 编译通过，产物 539K |
| 4.2 商品浏览链路（首页 / 分类 / 详情） | ✅ 代码完成，接口对齐 |
| 4.3 购物车与下单链路（购物车 / 确认 / 列表 / 详情） | ✅ 代码完成，接口对齐 |
| 4.4 个人中心与完善（登录 / 地址 / 个人中心 / 异常处理） | ✅ 代码完成 |
| 最终验收（闭环 / 双端同步 / 鉴权 / 交互 / 规范） | ✅ 代码层面全部满足，运行时需微信开发者工具实测 |

## 目录结构

```
ecommerce-mini-program/
├── src/                      # uni-app 源码（官方推荐结构）
│   ├── pages/                # 12 个页面
│   ├── components/           # goods-card 商品卡片（复用）
│   ├── store/                # user.js 登录态 / cart.js 购物车
│   ├── utils/                # request.js 请求封装 / common.js 工具
│   ├── static/               # 占位图
│   ├── App.vue / main.js / pages.json / manifest.json / uni.scss
├── dist/build/mp-weixin/     # 微信小程序编译产物
└── vite.config.js / package.json
```
