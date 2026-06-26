export const enum KnowApi {
  UploadFile = "/knowledge/file/upload",
  QueryFile = "/knowledge/contents",
  DeleteFile = "/knowledge/delete",
  DownloadFile = "/knowledge/download",
  UploadWithKb = "/knowledge/file/upload-with-kb",
  ListByKb = "/knowledge/list-by-kb",
  DocHistory = "/knowledge/doc-history",
  LatestDocs = "/knowledge/latest-docs",
}

export const enum ChatApi {
  StreamChat = "/chat/stream",
  SimpleChat = "/chat/simple",
  Models = "/chat/models",
}

export const enum RagApi {
  StreamRag = "/ai/rag",
  RagWithKb = "/ai/rag-with-kb",
}

export const enum KnowledgeBaseApi {
  List = "/kb/list",
  Create = "/kb/create",
  Update = "/kb",
  Delete = "/kb",
  GetById = "/kb",
  Suggest = "/kb/suggest",
}

export const enum PromptTemplateApi {
  List = "/template/list",
  ListByKbId = "/template/list",
  Create = "/template/create",
  Update = "/template",
  Delete = "/template",
  GetById = "/template",
  GetForQuestion = "/template/get-for-question",
}

export const enum OneApi {
  AddOneApi = "/one-api",
  QueryApi = "/select",
  ChangeApi = "/change/",
  QueryOneApi = "/select/",
  DeleteOneApi = "/delete/",
  DeleteApi = "/delete",
}

export const enum DrawApi {
  DrawApi = "/draw/",
}
