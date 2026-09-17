import request from './request'

// ===== 认证 =====
export const login = (data) => request.post('/auth/login', data)
export const logout = () => request.post('/auth/logout')
export const getUserInfo = () => request.get('/user/info')

// ===== 商品 =====
export const getProductPage = (params) => request.get('/product/page', { params })
export const addProduct = (data) => request.post('/product', data)
export const updateProduct = (id, data) => request.put(`/product/${id}`, data)
export const updateProductStatus = (id, status) => request.put(`/product/${id}/status`, null, { params: { status } })
export const deleteProduct = (id) => request.delete(`/product/${id}`)

// ===== 分类 =====
export const getCategoryTree = () => request.get('/category/tree')
export const addCategory = (data) => request.post('/category', data)
export const updateCategory = (id, data) => request.put(`/category/${id}`, data)
export const deleteCategory = (id) => request.delete(`/category/${id}`)

// ===== 订单 =====
export const getOrderPage = (params) => request.get('/order/page', { params })
export const getOrderDetail = (id) => request.get(`/order/${id}`)
export const shipOrder = (id) => request.post(`/order/${id}/ship`)
export const adminCancelOrder = (id) => request.post(`/order/${id}/admin-cancel`)
export const getOrderStats = () => request.get('/order/stats')

// ===== 模拟下单（管理端演示） =====
export const getAddressList = () => request.get('/user/address/list')
export const addAddress = (data) => request.post('/user/address', data)
export const addCart = (data) => request.post('/cart', data)
export const createOrder = (data) => request.post('/order', data)
