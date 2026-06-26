import router from "@/router";
import { ElMessage } from "element-plus";

/**
 * 处理认证错误
 * 当API返回401未授权或登录超时时调用此函数
 */
export const handleAuthError = () => {
  // 显示错误消息
  ElMessage({
    message: '登录已过期，请重新登录',
    type: 'warning',
    duration: 3000
  });
  
  // 清除本地存储的认证信息
  localStorage.removeItem("token");
  localStorage.removeItem("userRole");
  
  // 跳转到登录页面
  router.push({ name: "login" });
};

export default handleAuthError;