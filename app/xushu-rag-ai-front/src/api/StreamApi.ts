import { fetchEventSource } from "@microsoft/fetch-event-source";
import {BASE_URL} from "@/http/config.ts";
import service from "@/http";

type ResultCallBack = (e: any | null) => void;

const BaseUrl = BASE_URL;
export const postStreamChat = (
    author: string,
    onMessage: ResultCallBack,
    onError: ResultCallBack,
    onClose: ResultCallBack
) => {
    const ctrl = new AbortController();
    fetchEventSource(BaseUrl + "/post-chat", {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
        },
        body: JSON.stringify({
            author: author,
        }),
        signal: ctrl.signal,
        onmessage: onMessage,
        onerror: (err: any) => {
            onError(err);
        },
        onclose: () => {
            onClose(null);
        },
        onopen: async (response: any) => {
            if (response.ok) {
                return;
            } else if (
                response.status >= 400 &&
                response.status < 500 &&
                response.status !== 429
            ) {
                onError(new Error(`HTTP ${response.status}: ${response.statusText}`));
                return new Promise(() => {});
            } else {
                onError(new Error(`HTTP ${response.status}: ${response.statusText}`));
                return new Promise(() => {});
            }
        },
    });
};

export const getStreamChat = (
    message: string,
    url: string = "/chat/stream",
    onMessage: ResultCallBack,
    onError: ResultCallBack,
    onClose: ResultCallBack,
    sources?: string[],
    kbIds?: number[],
    sessionId?: string,
    escalate?: boolean
) => {
    const ctrl = new AbortController();
    
    const formData = new FormData();
    formData.append('message', message);
    if (sources && sources.length > 0) {
        sources.forEach(source => {
            formData.append('sources', source);
        });
    }
    if (kbIds && kbIds.length > 0) {
        kbIds.forEach(kbId => {
            formData.append('kbIds', kbId.toString());
        });
    }
    if (sessionId) {
        formData.append('sessionId', sessionId);
    }
    if (escalate) {
        formData.append('escalate', 'true');
    }
    
    fetchEventSource(service.defaults.baseURL + url, {
        method: "POST",
        headers: {
            "Authorization": `Bearer ${localStorage.getItem("token") || ""}`
        },
        body: formData,
        signal: ctrl.signal,
        onmessage: onMessage,
        onerror: (err: any) => {
            onError(err);
        },
        onclose: () => {
            onClose(null);
        },
        onopen: async (response: any) => {
            if (response.ok) {
                return;
            } 
            else if (response.status === 401) {
                import('@/api/authUtils').then(module => {
                module.default();
                });
            }
            else{
                onError(new Error(`HTTP ${response.status}: ${response.statusText}`));
                return new Promise(() => {});
            }
        },
    });
};

export const postStreamChatWithSources = (
    message: string,
    sources: string[],
    url: string = "/ai/rag",
    onMessage: ResultCallBack,
    onError: ResultCallBack,
    onClose: ResultCallBack,
) => {
    const ctrl = new AbortController();
    
    const formData = new FormData();
    formData.append('message', message);
    sources.forEach(source => {
        formData.append('sources', source);
    });
    
    fetchEventSource(service.defaults.baseURL + url, {
        method: "POST",
        headers: {
            "Authorization": `Bearer ${localStorage.getItem("token") || ""}`
        },
        body: formData,
        signal: ctrl.signal,
        onmessage: onMessage,
        onerror: (err: any) => {
            onError(err);
        },
        onclose: () => {
            onClose(null);
        },
        onopen: async (response: any) => {
            if (response.ok) {
                return;
            } 
            else if (response.status === 401) {
                import('@/api/authUtils').then(module => {
                module.default();
                });
            }
            else{
                onError(new Error(`HTTP ${response.status}: ${response.statusText}`));
                return new Promise(() => {});
            }
        },
    });
};
