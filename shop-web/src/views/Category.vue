<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">分类管理</h2>
        <p class="page-desc">分类树支持两级结构，排序数值越小越靠前</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openDialog()">新增一级分类</el-button>
    </div>

    <div class="card card-pad">
      <el-tree
        :data="categoryTree" node-key="id" default-expand-all
        :expand-on-click-node="false" :props="{ children: 'children', label: 'name' }"
        class="cat-tree" v-loading="loading"
      >
        <template #default="{ data }">
          <div class="node" :class="{ root: !data.parentId }">
            <div class="node-left">
              <el-icon v-if="!data.parentId" class="node-ico root-ico"><Folder /></el-icon>
              <el-icon v-else class="node-ico"><Document /></el-icon>
              <span class="node-name">{{ data.name }}</span>
              <span class="node-sort num">排序 {{ data.sort }}</span>
              <span v-if="data.children?.length" class="node-count">{{ data.children.length }} 个子分类</span>
            </div>
            <div class="node-actions">
              <el-button v-if="!data.parentId" link type="primary" size="small" @click="openDialog(data, 'child')">新增子分类</el-button>
              <el-button link type="primary" size="small" @click="openDialog(data)">编辑</el-button>
              <el-button link type="danger" size="small" @click="handleDelete(data)">删除</el-button>
            </div>
          </div>
        </template>
        <template #empty><el-empty description="暂无分类，点击右上角新增一级分类" :image-size="80" /></template>
      </el-tree>
    </div>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑分类' : (isChild ? '新增子分类' : '新增一级分类')" width="440px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item v-if="isChild" label="父级分类">
          <el-input :model-value="parentName" disabled />
        </el-form-item>
        <el-form-item label="分类名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入分类名称" />
        </el-form-item>
        <el-form-item label="排序权重">
          <el-input-number v-model="form.sort" :min="0" style="width: 160px" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="禁用" />
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
import { Document, Folder, Plus } from '@element-plus/icons-vue'
import { addCategory, deleteCategory, getCategoryTree, updateCategory } from '../api'

const categoryTree = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const saving = ref(false)
const formRef = ref()
const isChild = ref(false)
const parentName = ref('')
const form = reactive({ id: null, name: '', parentId: 0, sort: 0, status: 1 })
const rules = { name: [{ required: true, message: '请输入分类名称', trigger: 'blur' }] }

const load = async () => {
  loading.value = true
  try {
    const res = await getCategoryTree()
    categoryTree.value = res.data
  } finally {
    loading.value = false
  }
}

const openDialog = (data, type) => {
  if (data && type === 'child') {
    isChild.value = true
    parentName.value = data.name
    Object.assign(form, { id: null, name: '', parentId: data.id, sort: 0, status: 1 })
  } else if (data) {
    isChild.value = false
    parentName.value = ''
    Object.assign(form, { ...data })
  } else {
    isChild.value = false
    parentName.value = ''
    Object.assign(form, { id: null, name: '', parentId: 0, sort: 0, status: 1 })
  }
  dialogVisible.value = true
}

const handleSave = async () => {
  await formRef.value.validate()
  saving.value = true
  try {
    if (form.id) {
      await updateCategory(form.id, form)
      ElMessage.success('修改成功')
    } else {
      await addCategory(form)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

const handleDelete = async (data) => {
  const hasChild = data.children && data.children.length
  await ElMessageBox.confirm(
    `确认删除分类「${data.name}」？${hasChild ? '（其下子分类将一并删除）' : ''}`, '提示', { type: 'warning' }
  )
  await deleteCategory(data.id)
  ElMessage.success('删除成功')
  load()
}

onMounted(load)
</script>

<style scoped>
.cat-tree :deep(.el-tree-node__content) { height: auto; padding: 2px 0; }
.node {
  display: flex; align-items: center; justify-content: space-between;
  width: 100%; padding: 8px 12px; border-radius: var(--radius-sm);
}
.node:hover { background: var(--brand-50); }
.node:hover .node-actions { opacity: 1; }
.node-left { display: flex; align-items: center; gap: 10px; min-width: 0; }
.node-ico { color: var(--text-3); }
.root-ico { color: var(--brand); }
.node-name { font-size: 14px; color: var(--text); }
.node.root .node-name { font-weight: 600; }
.node-sort { font-size: 12px; color: var(--text-3); }
.node-count { font-size: 11px; color: var(--brand-600); background: var(--brand-100); padding: 1px 8px; border-radius: 10px; }
.node-actions { opacity: 0; transition: opacity 0.15s; white-space: nowrap; }
@media (hover: none) { .node-actions { opacity: 1; } }
</style>
