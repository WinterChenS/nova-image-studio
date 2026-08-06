'use client';

/**
 * 管理控制台轻量 toast —— 复用工作台 Toast 的视觉样式，独立于 WorkspaceShell。
 * 全局单例，组件通过 useToast() 获取 show/dismiss。
 */

import { createContext, useCallback, useContext, useState } from 'react';
import { Toast, type ToastData } from '@/components/workspace/Toast';

interface ToastContextValue {
  show: (message: string, type?: ToastData['type']) => void;
  dismiss: (id: string) => void;
}

const ToastContext = createContext<ToastContextValue | null>(null);

export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [toasts, setToasts] = useState<ToastData[]>([]);
  const [seq, setSeq] = useState(0);

  const show = useCallback((message: string, type: ToastData['type'] = 'info') => {
    setSeq(n => n + 1);
    const id = `console-toast-${seq + 1}`;
    setToasts(prev => [...prev.slice(-3), { id, message, type }]);
  }, [seq]);

  const dismiss = useCallback((id: string) => {
    setToasts(prev => prev.filter(t => t.id !== id));
  }, []);

  return (
    <ToastContext.Provider value={{ show, dismiss }}>
      {children}
      {toasts.map(toast => (
        <Toast key={toast.id} toast={toast} onDismiss={dismiss} />
      ))}
    </ToastContext.Provider>
  );
}

export function useToast(): ToastContextValue {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error('useToast 必须在 ToastProvider 内使用');
  return ctx;
}
