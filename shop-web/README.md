# 轻量电商管理后台（阶段三：Vue3 前端）

基于 Vite + Vue3 + Element Plus 的管理后台，对接阶段二微服务网关（http://localhost:8080）。

## 技术栈（版本锁定）

Vite 4.5.0 / Vue 3.3.13 / Element Plus 2.3.8 / Pinia 2.1.7 / Axios 1.4.0 / Vue Router 4.2.5

## 启动

```bash
npm install --registry=https://registry.npmmirror.com
npm run dev        # http://localhost:5173，默认账号 admin / 123456
```

开发代理：`/api` → `http://localhost:8080`（网关），即规格 4.3「统一请求地址为网关地址」。

## 页面（规格 4.2 五个核心页面）

| 路由 | 页面 | 说明 |
|---|---|---|
| /login | 登录 | 用户名密码登录，token 存入 Pinia + localStorage |
| /dashboard | 数据概览 | 订单总量 / 总销售额 / 今日订单量 / 商品总数 |
| /products | 商品管理 | 列表分页搜索、新增/编辑/删除、上下架、库存调整 |
| /categories | 分类管理 | 树形列表、增删改、排序权重 |
| /orders | 订单管理 | 列表/状态筛选/详情抽屉、发货、取消（管理端）、模拟下单 |

## 对接实现（规格 4.3/4.4）

- **Axios 封装**（src/api/request.js）：请求拦截器统一加 `Authorization: Bearer {token}`；响应拦截器统一解析 `Result`（code=200 成功，非 200 弹窗），HTTP 401 清 token 跳登录
- **登录鉴权**（src/store/user.js）：登录成功存 token+userInfo 到 Pinia + localStorage；路由守卫无 token 跳 /login；退出调用后端 logout 使 Redis token 失效
- **模拟下单闭环**（订单页按钮）：自动确保默认地址 → 加购 → 下单，一条链路完成规格 4.5 演示

## 验收（4.5 全栈闭环）

1. 登录退出、权限控制 ✅
2. 五个核心页面可操作、数据与后端同步 ✅
3. 闭环：新增商品 → 库存调整 → 模拟下单 → 订单后台可查 → 发货 → 统计更新 ✅
4. 异常提示友好（非 200 弹窗、401 跳登录、无白屏）✅
