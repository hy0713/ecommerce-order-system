<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">商品管理</h2>
        <p class="page-desc">商品分页、搜索、新增、编辑、库存调整与上下架</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openDialog()">新增商品</el-button>
    </div>

    <div class="card">
      <div class="toolbar">
        <el-input
          v-model="query.keyword" placeholder="搜索商品名称 / ID" clearable
          :prefix-icon="Search" style="width: 280px" @keyup.enter="load(1)" @clear="load(1)"
        />
        <!-- 注意：Element Plus 2.3.8 的 el-radio-button 只有 label 属性承载「值」，没有 value 属性 -->
        <el-radio-group v-model="statusTab" @change="load(1)">
          <el-radio-button label="all">全部</el-radio-button>
          <el-radio-button :label="1">在售</el-radio-button>
          <el-radio-button :label="0">已下架</el-radio-button>
        </el-radio-group>
        <div class="toolbar-right">
          <el-button text size="small" :icon="Refresh" @click="load()">刷新</el-button>
        </div>
      </div>

      <el-table :data="list" v-loading="loading" size="large">
        <el-table-column label="商品" min-width="240">
          <template #default="{ row }">
            <div class="p-cell">
              <img v-if="row.icon" :src="row.icon" class="p-thumb" alt="" @error="onImgErr" />
              <div v-else class="p-thumb p-thumb-ph"><el-icon><Picture /></el-icon></div>
              <div class="p-meta">
                <div class="p-name">{{ row.name }}</div>
                <div class="p-id num">ID {{ row.id }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="categoryName" label="分类" width="120" />
        <el-table-column label="价格" width="120">
          <template #default="{ row }"><span class="num price">{{ fmtMoney(row.price) }}</span></template>
        </el-table-column>
        <el-table-column label="库存" width="110">
          <template #default="{ row }">
            <span class="num" :class="{ 'stock-low': row.stock <= 5 }">{{ row.stock }}</span>
            <span v-if="row.stock <= 5" class="low-tag">偏低</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <span class="dot" :class="row.status === 1 ? 'c-green' : 'c-grey'">{{ row.status === 1 ? '在售' : '已下架' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDialog(row)">编辑 / 库存调整</el-button>
            <el-button link :type="row.status === 1 ? 'warning' : 'success'" size="small" @click="toggleStatus(row)">
              {{ row.status === 1 ? '下架' : '上架' }}
            </el-button>
            <el-button link type="danger" size="small" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="没有匹配的商品" :image-size="80" /></template>
      </el-table>

      <el-pagination layout="total, prev, pager, next" :total="total"
        :page-size="query.pageSize" :current-page="query.pageNum" @current-change="load" />
    </div>

    <!-- 新增 / 编辑 / 库存调整 -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑商品（含库存调整）' : '新增商品'" width="560px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="92px">
        <el-form-item label="商品名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入商品名称" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="所属分类" prop="categoryId">
              <el-select v-model="form.categoryId" placeholder="选择分类" filterable style="width: 100%">
                <el-option v-for="c in leafCategories" :key="c.id" :label="c.name" :value="c.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="商品状态">
              <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="上架" inactive-text="下架" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="单价(元)" prop="price">
              <el-input-number v-model="form.price" :min="0.01" :precision="2" :step="10" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="库存" prop="stock">
              <el-input-number v-model="form.stock" :min="0" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="商品描述">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="选填" />
        </el-form-item>
        <el-form-item label="商品图片">
          <el-input v-model="form.icon" placeholder="图片 URL，例如 https://..." />
        </el-form-item>
        <el-form-item v-if="form.icon" label="预览">
          <img :src="form.icon" class="icon-preview" alt="" @error="onImgErr" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Picture, Plus, Refresh, Search } from '@element-plus/icons-vue'
import { addProduct, deleteProduct, getCategoryTree, getProductPage, updateProduct, updateProductStatus } from '../api'
import { fmtMoney } from '../utils/order'

const list = ref([])
const total = ref(0)
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const formRef = ref()
const categoryTree = ref([])

const query = reactive({ keyword: '', pageNum: 1, pageSize: 10 })
// 状态筛选：'all' | 1 | 0（'all' 时不向后端传 status 参数）
const statusTab = ref('all')
const form = reactive({ id: null, name: '', categoryId: null, price: 0, stock: 0, description: '', icon: '', status: 1 })
const rules = {
  name: [{ required: true, message: '请输入商品名称', trigger: 'blur' }],
  categoryId: [{ required: true, message: '请选择分类', trigger: 'change' }],
  price: [{ required: true, message: '请输入单价', trigger: 'blur' }],
  stock: [{ required: true, message: '请输入库存', trigger: 'blur' }]
}

const leafCategories = computed(() => {
  const leaves = []
  const walk = (nodes) => {
    for (const n of nodes || []) {
      if (n.children && n.children.length) walk(n.children)
      else leaves.push(n)
    }
  }
  walk(categoryTree.value)
  return leaves
})

const load = async (page) => {
  if (page) query.pageNum = page
  loading.value = true
  try {
    const params = { ...query }
    if (statusTab.value !== 'all') params.status = statusTab.value
    const res = await getProductPage(params)
    list.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

const loadCategories = async () => {
  const res = await getCategoryTree()
  categoryTree.value = res.data
}

const openDialog = (row) => {
  Object.assign(form, row ? { ...row } : { id: null, name: '', categoryId: null, price: 0, stock: 0, description: '', icon: '', status: 1 })
  dialogVisible.value = true
}

const handleSave = async () => {
  await formRef.value.validate()
  saving.value = true
  try {
    if (form.id) {
      await updateProduct(form.id, form)
      ElMessage.success('修改成功')
    } else {
      await addProduct(form)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

const toggleStatus = async (row) => {
  await updateProductStatus(row.id, row.status === 1 ? 0 : 1)
  ElMessage.success(row.status === 1 ? '已下架' : '已上架')
  load()
}

const handleDelete = async (row) => {
  await ElMessageBox.confirm(`确认删除商品「${row.name}」？`, '提示', { type: 'warning' })
  await deleteProduct(row.id)
  ElMessage.success('删除成功')
  load()
}

const onImgErr = (e) => { e.target.style.visibility = 'hidden' }

onMounted(() => {
  load()
  loadCategories()
})
</script>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 14px; padding: 16px 20px; border-bottom: 1px solid var(--border-2); flex-wrap: wrap; }
.toolbar-right { margin-left: auto; }
.p-cell { display: flex; align-items: center; gap: 12px; }
.p-thumb { width: 44px; height: 44px; border-radius: 8px; object-fit: cover; flex: none; background: #f1f5f9; border: 1px solid var(--border-2); }
.p-thumb-ph { display: flex; align-items: center; justify-content: center; color: var(--text-3); }
.p-name { font-size: 14px; color: var(--text); }
.p-id { font-size: 12px; color: var(--text-3); margin-top: 2px; }
.price { color: var(--text); font-weight: 600; }
.stock-low { color: var(--danger); font-weight: 600; }
.low-tag { margin-left: 6px; font-size: 11px; color: var(--danger); background: var(--danger-bg); padding: 1px 6px; border-radius: 4px; }
.icon-preview { width: 64px; height: 64px; border-radius: 8px; object-fit: cover; border: 1px solid var(--border-2); }
</style>
