<template>
  <div class="knowledge-container">
    <el-tabs v-model="activeTab" type="border-card" style="height: 100%;">
      <el-tab-pane label="文件管理" name="files">
        <el-upload
          class="upload-demo"
          drag
          multiple
          v-model:file-list="fileList"
          :auto-upload="false"
          v-loading="isUploading"
        >
          <el-icon class="el-icon--upload"><upload-filled /></el-icon>
          <div class="el-upload__text">
            拖拽文件至此或<em>点击选择文件</em>进行上传
          </div>
          <template #tip>
            <div style="text-align: center">
              <el-text
                >文件支持 <i>pdf、doc、md、excel、text</i>等，最大可上传<em
                  style="color: blue"
                  >100MB</em
                ></el-text
              >
            </div>
          </template>
        </el-upload>

        <el-form
          style="display: flex; justify-content: space-between; align-items: center; margin: 20px 0; flex-wrap: wrap; gap: 10px;"
          :model="queryFileDto"
        >
          <div style="display: flex; gap: 10px; align-items: center; flex-wrap: wrap;">
            <el-select
              v-model="selectedKbId"
              placeholder="选择知识库"
              style="width: 200px;"
              size="small"
            >
              <el-option label="全部知识库" :value="0" />
              <el-option
                v-for="kb in knowledgeBases"
                :key="kb.id"
                :label="kb.name"
                :value="kb.id"
              />
            </el-select>
            <el-button
              type="danger"
              @click="batchDelete"
              :disabled="selectedFiles.length === 0"
            >
              批量删除
            </el-button>
            <el-button
              type="primary"
              @click="batchDownload"
              :disabled="selectedFiles.length === 0"
            >
              批量下载
            </el-button>
            <el-form-item label="文件名:" style="margin-bottom: 0">
              <el-input placeholder="请输入文件名称" v-model="queryFileDto.fileName" />
            </el-form-item>
            <el-form-item style="margin-bottom: 0">
              <el-button type="primary" @click="loadStoreFileData" :disabled="isLoading"
                >搜索</el-button
              >
            </el-form-item>
          </div>
          <div style="display: flex; gap: 10px; align-items: center;">
            <el-checkbox v-model="autoClassify">自动分类到知识库</el-checkbox>
            <el-button
              type="warning"
              @click="uploadFile"
              :disabled="isUploading"
            >
              全部上传
            </el-button>
          </div>
        </el-form>

        <el-table
          :data="storeFileData"
          border
          v-loading="isLoading"
          height="calc(100vh - 450px)"
          @selection-change="handleSelectionChange"
        >
          <el-table-column type="selection" width="55" />
          <el-table-column label="序号" width="80">
            <template #default="scope">
              {{ (queryFileDto.page - 1) * queryFileDto.pageSize + scope.$index + 1 }}
            </template>
          </el-table-column>
          <el-table-column prop="fileName" label="文件名" width="380" />
          <el-table-column label="所属知识库" width="150">
            <template #default="scope">
              {{ scope.row.kbName || getKbName(scope.row.kbId) || '-' }}
            </template>
          </el-table-column>
          <el-table-column prop="version" label="版本" width="120">
            <template #default="scope">
              <el-tag size="small" :type="scope.row.version ? 'success' : 'info'">
                {{ scope.row.version || '未知' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="上传时间">
            <template #default="scope">
              {{ format(new Date(scope.row.createTime), "yyyy-MM-dd HH:mm") }}
            </template>
          </el-table-column>
          <el-table-column label="更新时间">
            <template #default="scope">
              {{ format(new Date(scope.row.updateTime), "yyyy-MM-dd HH:mm") }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="scope">
              <el-button
                @click="deleteStoreFile(scope.row)"
                type="danger"
                size="small"
                >删除</el-button
              >
              <el-button
                @click="openFilePreview(scope.row)"
                type="primary"
                size="small"
                >下载</el-button
              >
            </template>
          </el-table-column>
        </el-table>

        <div style="margin-top: 20px; display: flex; justify-content: center;">
          <el-pagination
            v-model:current-page="queryFileDto.page"
            v-model:page-size="queryFileDto.pageSize"
            :page-sizes="[10, 20, 50, 100]"
            :total="storeFileTotal"
            @size-change="handleSizeChange"
            @current-change="handleCurrentChange"
            layout="total, sizes, prev, pager, next, jumper"
          />
        </div>
      </el-tab-pane>

      <el-tab-pane label="知识库管理" name="kb">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px;">
          <el-button type="primary" @click="showCreateKbDialog = true">新建知识库</el-button>
        </div>

        <el-table
          :data="knowledgeBases"
          border
          v-loading="isKbLoading"
          height="calc(100vh - 200px)"
        >
          <el-table-column prop="name" label="知识库名称" width="250" />
          <el-table-column prop="description" label="描述" width="400" />
          <el-table-column prop="documentCount" label="文档数量" width="120" />
          <el-table-column prop="status" label="状态" width="100">
            <template #default="scope">
              <el-tag :type="scope.row.status === 'ACTIVE' ? 'success' : 'danger'">
                {{ scope.row.status === 'ACTIVE' ? '启用' : '禁用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="创建时间">
            <template #default="scope">
              {{ format(new Date(scope.row.createTime), "yyyy-MM-dd HH:mm") }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="180" fixed="right">
            <template #default="scope">
              <el-button
                @click="editKnowledgeBase(scope.row)"
                type="primary"
                size="small"
                >编辑</el-button
              >
              <el-button
                @click="toggleKbStatus(scope.row)"
                type="warning"
                size="small"
                >{{ scope.row.status === 'ACTIVE' ? '禁用' : '启用' }}</el-button
              >
              <el-button
                @click="deleteKnowledgeBase(scope.row)"
                type="danger"
                size="small"
                >删除</el-button
              >
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="提示词模板" name="template">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px;">
          <el-select
            v-model="templateKbId"
            placeholder="选择知识库"
            style="width: 200px;"
            size="small"
            @change="loadTemplates"
          >
            <el-option label="全部" :value="0" />
            <el-option
              v-for="kb in knowledgeBases"
              :key="kb.id"
              :label="kb.name"
              :value="kb.id"
            />
          </el-select>
          <el-button type="primary" @click="showCreateTemplateDialog = true">新建模板</el-button>
        </div>

        <el-table
          :data="promptTemplates"
          border
          v-loading="isTemplateLoading"
          height="calc(100vh - 250px)"
        >
          <el-table-column prop="name" label="模板名称" width="200" />
          <el-table-column prop="kbName" label="所属知识库" width="150" />
          <el-table-column prop="templateContent" label="模板内容" width="500" show-overflow-tooltip />
          <el-table-column prop="isDefault" label="默认" width="80">
            <template #default="scope">
              <el-tag v-if="scope.row.isDefault === 1" type="success">默认</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="180" fixed="right">
            <template #default="scope">
              <el-button
                @click="editPromptTemplate(scope.row)"
                type="primary"
                size="small"
                >编辑</el-button
              >
              <el-button
                @click="setDefaultTemplate(scope.row)"
                v-if="scope.row.isDefault !== 1"
                type="warning"
                size="small"
                >设为默认</el-button
              >
              <el-button
                @click="deletePromptTemplate(scope.row)"
                type="danger"
                size="small"
                >删除</el-button
              >
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="showCreateKbDialog" title="新建知识库" width="500px">
      <el-form :model="kbForm" label-width="80px">
        <el-form-item label="名称">
          <el-input v-model="kbForm.name" placeholder="请输入知识库名称" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="kbForm.description" type="textarea" :rows="3" placeholder="请输入知识库描述" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreateKbDialog = false">取消</el-button>
        <el-button type="primary" @click="createKnowledgeBase">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="showCreateTemplateDialog" title="新建提示词模板" width="600px">
      <el-form :model="templateForm" label-width="80px">
        <el-form-item label="知识库">
          <el-select v-model="templateForm.kbId" placeholder="请选择知识库">
            <el-option
              v-for="kb in knowledgeBases"
              :key="kb.id"
              :label="kb.name"
              :value="kb.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="templateForm.name" placeholder="请输入模板名称" />
        </el-form-item>
        <el-form-item label="模板内容">
          <el-input v-model="templateForm.content" type="textarea" :rows="8" placeholder="请输入提示词模板内容，支持 {context}、{question}、{kb_name} 变量" />
        </el-form-item>
        <el-form-item label="设为默认">
          <el-checkbox v-model="templateForm.isDefault" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreateTemplateDialog = false">取消</el-button>
        <el-button type="primary" @click="createPromptTemplate">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, reactive } from "vue";
import { type UploadUserFile, ElMessage, ElMessageBox } from "element-plus";
import {
  uploadFileApi,
  uploadWithKbApi,
  queryFileApi,
  deleteFileApi,
  downloadFileApi,
  listKnowledgeBasesApi,
  createKnowledgeBaseApi,
  updateKnowledgeBaseApi,
  deleteKnowledgeBaseApi,
  listPromptTemplatesApi,
  listPromptTemplatesByKbIdApi,
  createPromptTemplateApi,
  updatePromptTemplateApi,
  deletePromptTemplateApi,
} from "@/api/KnowHubApi";
import { QueryFileDto } from "@/api/dto";
import { format } from "date-fns";

const activeTab = ref("files");
const storeFileData = ref<any[]>([]);
const queryFileDto = ref<QueryFileDto>({
  page: 1,
  pageSize: 10,
  fileName: "",
});
const isUploading = ref(false);
const isLoading = ref(false);
const storeFileTotal = ref(0);
const selectedFiles = ref<any[]>([]);
const selectedKbId = ref(0);
const autoClassify = ref(true);

const knowledgeBases = ref<any[]>([]);
const isKbLoading = ref(false);

const promptTemplates = ref<any[]>([]);
const templateKbId = ref(0);
const isTemplateLoading = ref(false);

const showCreateKbDialog = ref(false);
const kbForm = reactive({
  name: "",
  description: "",
});

const showCreateTemplateDialog = ref(false);
const templateForm = reactive({
  kbId: null as number | null,
  name: "",
  content: "",
  isDefault: false,
});

const fileList = ref<UploadUserFile[]>();

const loadStoreFileData = () => {
  isLoading.value = true;
  const params: any = { ...queryFileDto.value, page: queryFileDto.value.page - 1 };
  if (selectedKbId.value > 0) {
    params.kbId = selectedKbId.value;
  }
  queryFileApi(params)
    .then((res) => {
      if (res.code == 0) {
        const data = res.data;
        storeFileTotal.value = data.totalElements;
        storeFileData.value = data.records;
      } else {
        ElMessage({
          type: "error",
          message: res.message,
        });
      }
    })
    .catch((err) => {
      ElMessage({
        type: "error",
        message: err,
      });
    })
    .finally(() => {
      isLoading.value = false;
    });
};

const uploadFile = () => {
  const files: File[] = [];
  fileList.value?.forEach((e) => {
    files.push(e.raw as File);
  });

  const maxSize = 100 * 1024 * 1024;
  for (const file of files) {
    if (file.size > maxSize) {
      ElMessage({
        type: "error",
        message: `文件 ${file.name} 超过了最大上传大小限制 (100MB)`,
      });
      return;
    }
  }

  isUploading.value = true;
  // 统一使用分库上传：指定知识库或自动分类，确保文件关联kb_id
  const uploadPromise = uploadWithKbApi(
    files, 
    selectedKbId.value > 0 ? selectedKbId.value : undefined, 
    undefined, 
    autoClassify.value
  );

  uploadPromise
    .then((res) => {
      let code = res.data.code;
      if (code == 0) {
        ElMessage({
          type: "success",
          message: res.data.data,
        });
        fileList.value = [];
        loadStoreFileData();
        loadKnowledgeBases();
      } else {
        ElMessage({
          type: "error",
          message: res.data.message,
        });
      }
    })
    .catch((err) => {
      console.log(err);
      ElMessage({
        type: "error",
        message: err,
      });
    })
    .finally(() => {
      isUploading.value = false;
    });
};

const deleteStoreFile = (e: any) => {
  ElMessageBox.confirm("确定要删除这个文件吗？", "警告", {
    confirmButtonText: "确定",
    cancelButtonText: "取消",
    type: "warning",
  })
    .then(() => {
      deleteFileApi({ ids: e.id })
        .then((res) => {
          let code = res.code;
          if (code == 0) {
            ElMessage({
              type: "success",
              message: res.data,
            });
            loadStoreFileData();
            loadKnowledgeBases();
          } else {
            ElMessage({
              type: "error",
              message: res.message,
            });
          }
        })
        .catch((err) => {
          ElMessage({
            type: "error",
            message: err,
          });
        });
    })
    .catch(() => {});
};

const openFilePreview = (e: any) => {
  downloadFileApi({ ids: e.id })
    .then((res) => {
      let code = res.code;
      if (code == 0) {
        ElMessage({
          type: "success",
          message: res.data,
        });
        loadStoreFileData();
      } else {
        ElMessage({
          type: "error",
          message: res.message,
        });
      }
    })
    .catch((err) => {
      ElMessage({
        type: "error",
        message: err,
      });
    });
};

const handleSizeChange = (val: number) => {
  queryFileDto.value.pageSize = val;
  loadStoreFileData();
};

const handleCurrentChange = (val: number) => {
  queryFileDto.value.page = val;
  loadStoreFileData();
};

const handleSelectionChange = (selection: any[]) => {
  selectedFiles.value = selection;
};

const batchDelete = () => {
  if (selectedFiles.value.length === 0) return;

  ElMessageBox.confirm("确定要删除选中的文件吗？", "警告", {
    confirmButtonText: "确定",
    cancelButtonText: "取消",
    type: "warning",
  })
    .then(() => {
      const ids = selectedFiles.value.map((file: any) => file.id);
      deleteFileApi({ ids })
        .then((res) => {
          if (res.code == 0) {
            ElMessage({
              type: "success",
              message: res.data,
            });
            selectedFiles.value = [];
            loadStoreFileData();
            loadKnowledgeBases();
          } else {
            ElMessage({
              type: "error",
              message: res.message,
            });
          }
        })
        .catch((err) => {
          ElMessage({
            type: "error",
            message: err,
          });
        });
    })
    .catch(() => {});
};

const batchDownload = () => {
  if (selectedFiles.value.length === 0) return;

  const ids = selectedFiles.value.map((file: any) => file.id);
  downloadFileApi({ ids })
    .then((res) => {
      if (res.code == 0) {
        ElMessage({
          type: "success",
          message: res.data,
        });
        selectedFiles.value = [];
        loadStoreFileData();
      } else {
        ElMessage({
          type: "error",
          message: res.message,
        });
      }
    })
    .catch((err) => {
      ElMessage({
        type: "error",
        message: err,
      });
    });
};

const loadKnowledgeBases = () => {
  isKbLoading.value = true;
  listKnowledgeBasesApi()
    .then((res) => {
      if (res.code == 0) {
        knowledgeBases.value = res.data;
      } else {
        ElMessage({
          type: "error",
          message: res.message,
        });
      }
    })
    .catch((err) => {
      ElMessage({
        type: "error",
        message: err,
      });
    })
    .finally(() => {
      isKbLoading.value = false;
    });
};

const createKnowledgeBase = () => {
  if (!kbForm.name.trim()) {
    ElMessage.warning("请输入知识库名称");
    return;
  }
  createKnowledgeBaseApi(kbForm.name, kbForm.description)
    .then((res) => {
      if (res.code == 0) {
        ElMessage.success("创建成功");
        showCreateKbDialog.value = false;
        kbForm.name = "";
        kbForm.description = "";
        loadKnowledgeBases();
      } else {
        ElMessage.error(res.message);
      }
    })
    .catch((err) => {
      ElMessage.error(err);
    });
};

const editKnowledgeBase = (row: any) => {
  kbForm.name = row.name;
  kbForm.description = row.description || "";
  showCreateKbDialog.value = true;
};

const toggleKbStatus = (row: any) => {
  const newStatus = row.status === "ACTIVE" ? "INACTIVE" : "ACTIVE";
  updateKnowledgeBaseApi(row.id, undefined, undefined, newStatus)
    .then((res) => {
      if (res.code == 0) {
        ElMessage.success("状态已更新");
        loadKnowledgeBases();
      } else {
        ElMessage.error(res.message);
      }
    })
    .catch((err) => {
      ElMessage.error(err);
    });
};

const deleteKnowledgeBase = (row: any) => {
  ElMessageBox.confirm(`确定要删除知识库 "${row.name}" 吗？`, "警告", {
    confirmButtonText: "确定",
    cancelButtonText: "取消",
    type: "warning",
  })
    .then(() => {
      deleteKnowledgeBaseApi(row.id)
        .then((res) => {
          if (res.code == 0) {
            ElMessage.success("删除成功");
            loadKnowledgeBases();
            loadStoreFileData();
          } else {
            ElMessage.error(res.message);
          }
        })
        .catch((err) => {
          ElMessage.error(err);
        });
    })
    .catch(() => {});
};

const loadTemplates = () => {
  isTemplateLoading.value = true;
  const apiPromise = templateKbId.value > 0
    ? listPromptTemplatesByKbIdApi(templateKbId.value)
    : listPromptTemplatesApi();

  apiPromise
    .then((res) => {
      if (res.code == 0) {
        promptTemplates.value = res.data;
      } else {
        ElMessage.error(res.message);
      }
    })
    .catch((err) => {
      ElMessage.error(err);
    })
    .finally(() => {
      isTemplateLoading.value = false;
    });
};

const createPromptTemplate = () => {
  if (!templateForm.kbId) {
    ElMessage.warning("请选择知识库");
    return;
  }
  if (!templateForm.name.trim()) {
    ElMessage.warning("请输入模板名称");
    return;
  }
  if (!templateForm.content.trim()) {
    ElMessage.warning("请输入模板内容");
    return;
  }
  createPromptTemplateApi(templateForm.kbId, templateForm.name, templateForm.content, templateForm.isDefault ? 1 : 0)
    .then((res) => {
      if (res.code == 0) {
        ElMessage.success("创建成功");
        showCreateTemplateDialog.value = false;
        templateForm.kbId = null;
        templateForm.name = "";
        templateForm.content = "";
        templateForm.isDefault = false;
        loadTemplates();
      } else {
        ElMessage.error(res.message);
      }
    })
    .catch((err) => {
      ElMessage.error(err);
    });
};

const editPromptTemplate = (row: any) => {
  templateForm.kbId = row.kbId;
  templateForm.name = row.name;
  templateForm.content = row.templateContent;
  templateForm.isDefault = row.isDefault === 1;
  showCreateTemplateDialog.value = true;
};

const setDefaultTemplate = (row: any) => {
  updatePromptTemplateApi(row.id, undefined, undefined, undefined, 1)
    .then((res) => {
      if (res.code == 0) {
        ElMessage.success("已设为默认模板");
        loadTemplates();
      } else {
        ElMessage.error(res.message);
      }
    })
    .catch((err) => {
      ElMessage.error(err);
    });
};

const deletePromptTemplate = (row: any) => {
  ElMessageBox.confirm(`确定要删除模板 "${row.name}" 吗？`, "警告", {
    confirmButtonText: "确定",
    cancelButtonText: "取消",
    type: "warning",
  })
    .then(() => {
      deletePromptTemplateApi(row.id)
        .then((res) => {
          if (res.code == 0) {
            ElMessage.success("删除成功");
            loadTemplates();
          } else {
            ElMessage.error(res.message);
          }
        })
        .catch((err) => {
          ElMessage.error(err);
        });
    })
    .catch(() => {});
};

// 根据kbId查找知识库名称（表格列回退）
const getKbName = (kbId: number | string | undefined): string => {
  if (kbId == null) return '';
  const kb = knowledgeBases.value.find((k: any) => k.id == kbId);
  return kb ? kb.name : '';
};

onMounted(() => {
  loadStoreFileData();
  loadKnowledgeBases();
  loadTemplates();
});
</script>

<style scoped>
.knowledge-container {
  height: calc(100vh - 60px);
  display: flex;
  flex-direction: column;
}

.el-table {
  ::-webkit-scrollbar {
    width: 6px;
    height: 6px;
  }
  ::-webkit-scrollbar-thumb {
    background: #ddd;
    border-radius: 3px;
  }
  ::-webkit-scrollbar-track {
    background: #f5f5f5;
  }
}

.upload-demo {
  margin-bottom: 20px;
}

.el-form {
  background-color: #fff;
  padding: 15px;
  border-radius: 4px;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.05);
}
</style>
