import service from "@/http";
import { RagApi } from "@/api/common.ts";

export const streamRagApi = async (message: string = '你好', sources: string[] = []): Promise<any> => {
    const params: any = { message };
    if (sources && sources.length > 0) {
        params.sources = sources;
    }
    return await service.post(RagApi.StreamRag, null, { params });
};

export const sendRagWithSourcesApi = async (message: string, sources: string[]): Promise<any> => {
    return await service.post(RagApi.StreamRag, null, { params: { message, sources } });
};

export const sendRagWithKbApi = async (
    message: string,
    kbIds?: number[],
    sources?: string[]
): Promise<any> => {
    const params: any = { message };
    if (kbIds && kbIds.length > 0) {
        params.kbIds = kbIds;
    }
    if (sources && sources.length > 0) {
        params.sources = sources;
    }
    return await service.post(RagApi.RagWithKb, null, { params });
};
