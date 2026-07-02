import { KnowApi, KnowledgeBaseApi, PromptTemplateApi } from "./common";
import { BASE_URL } from "@/http/config";
import axios from "axios";
import { DownloadFileDto, DeleteFileDto, QueryFileDto } from "./dto";
import service from "@/http";
import handleAuthError from "@/api/authUtils";

type Res = any;

const fileService = axios.create({
  baseURL: BASE_URL,
  headers: {
    "Content-Type": "multipart/form-data",
  },
});

fileService.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem("token");
    if (token !== null) {
      config.headers.Authorization = "Bearer " + token;
    }
    return config;
  },
  (error) => {
    console.log(error);
    return Promise.reject(error);
  }
);

fileService.interceptors.response.use(
  (res: any) => {
    return res;
  },
  (error) => {
    if (error.response && error.response.status === 401) {
      handleAuthError();
    }
    console.log(error);
    return Promise.reject(error);
  }
);

export const uploadFileApi = async (filesList: File[]): Promise<Res> => {
  let formData = new FormData();
  filesList.map((e) => {
    formData.append("file", e);
  });

  return fileService.post(KnowApi.UploadFile, formData);
};

export const uploadWithKbApi = async (
  filesList: File[],
  kbId?: number,
  kbName?: string,
  autoClassify: boolean = true
): Promise<Res> => {
  let formData = new FormData();
  filesList.map((e) => {
    formData.append("file", e);
  });
  if (kbId) {
    formData.append("kbId", kbId.toString());
  }
  if (kbName) {
    formData.append("kbName", kbName);
  }
  formData.append("autoClassify", autoClassify.toString());

  return fileService.post(KnowApi.UploadWithKb, formData);
};

export const queryFileApi = async (params: QueryFileDto): Promise<Res> => {
  return service.get(KnowApi.QueryFile, {
    params,
  });
};

export const deleteFileApi = async (params: DeleteFileDto): Promise<Res> => {
  return service.delete(KnowApi.DeleteFile, {
    params,
  });
};

export const downloadFileApi = async (params: DownloadFileDto): Promise<Res> => {
  return service.get(KnowApi.DownloadFile, {
    params,
  });
};

// ==================== 知识库管理 ====================

export const listKnowledgeBasesApi = async (): Promise<Res> => {
  return service.get(KnowledgeBaseApi.List);
};

export const createKnowledgeBaseApi = async (name: string, description?: string): Promise<Res> => {
  return service.post(KnowledgeBaseApi.Create, null, {
    params: { name, description },
  });
};

export const updateKnowledgeBaseApi = async (id: number, name?: string, description?: string, status?: string): Promise<Res> => {
  return service.put(`${KnowledgeBaseApi.Update}/${id}`, null, {
    params: { name, description, status },
  });
};

export const deleteKnowledgeBaseApi = async (id: number): Promise<Res> => {
  return service.delete(`${KnowledgeBaseApi.Delete}/${id}`);
};

export const getKnowledgeBaseByIdApi = async (id: number): Promise<Res> => {
  return service.get(`${KnowledgeBaseApi.GetById}/${id}`);
};

// ==================== 提示词模板管理 ====================

export const listPromptTemplatesApi = async (): Promise<Res> => {
  return service.get(PromptTemplateApi.List);
};

export const listPromptTemplatesByKbIdApi = async (kbId: number): Promise<Res> => {
  return service.get(`${PromptTemplateApi.ListByKbId}/${kbId}`);
};

export const createPromptTemplateApi = async (kbId: number, name: string, templateContent: string, isDefault?: number): Promise<Res> => {
  return service.post(PromptTemplateApi.Create, null, {
    params: { kbId, name, templateContent, isDefault },
  });
};

export const updatePromptTemplateApi = async (id: number, name?: string, templateContent?: string, status?: string, isDefault?: number): Promise<Res> => {
  return service.put(`${PromptTemplateApi.Update}/${id}`, null, {
    params: { name, templateContent, status, isDefault },
  });
};

export const deletePromptTemplateApi = async (id: number): Promise<Res> => {
  return service.delete(`${PromptTemplateApi.Delete}/${id}`);
};

export const getPromptTemplateByIdApi = async (id: number): Promise<Res> => {
  return service.get(`${PromptTemplateApi.GetById}/${id}`);
};

// ==================== 文档版本管理 ====================

export const listDocumentsByKbIdApi = async (kbId: number): Promise<Res> => {
  return service.get(`${KnowApi.ListByKb}/${kbId}`);
};

export const getDocumentHistoryApi = async (kbId: number, originalName: string): Promise<Res> => {
  return service.get(KnowApi.DocHistory, {
    params: { kbId, originalName },
  });
};

export const getLatestDocumentsApi = async (kbId: number): Promise<Res> => {
  return service.get(`${KnowApi.LatestDocs}/${kbId}`);
};

// 批量查询多个知识库的最新文档（用于知识库级联选择）
export const getLatestDocumentsBatchApi = async (kbIds: number[]): Promise<Res> => {
  return service.get(KnowApi.LatestDocsBatch, {
    params: { kbIds: kbIds.join(',') },
  });
};
