'use client';

/**
 * WIN-39 (WIN-40 T4/T6) — 鉴权图片渲染：服务端图片字节经 {@code authFetch}
 * 读取后以 blob URL 渲染（<img> 无法携带 Authorization 头）。
 * 用于 Agent 会话图片目录 / 画布节点图片等云端图片。
 */

import { useEffect, useRef, useState } from 'react';
import { authFetch } from '@/lib/auth';
import { cn } from '@/lib/utils';

interface AuthenticatedImageProps {
  /** 需要鉴权的 API 图片路径（如 /api/nova/agent/images/{id}） */
  src: string;
  alt?: string;
  className?: string;
  loading?: 'lazy' | 'eager';
  /** 加载中占位 */
  placeholderClassName?: string;
}

export function AuthenticatedImage({
  src,
  alt = '',
  className,
  loading = 'lazy',
  placeholderClassName,
}: AuthenticatedImageProps) {
  const [objectUrl, setObjectUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const tokenRef = useRef(0);

  useEffect(() => {
    if (!src) return;
    const token = ++tokenRef.current;
    let url: string | null = null;
    let cancelled = false;
    // 延迟到微任务后再 setState，避免 effect 内同步 setState 级联渲染（lint）
    void Promise.resolve().then(() => authFetch(src, { cache: 'no-store' }))
      .then(async response => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const blob = await response.blob();
        if (cancelled || token !== tokenRef.current) return;
        url = URL.createObjectURL(blob);
        setFailed(false);
        setObjectUrl(url);
      })
      .catch(() => {
        if (!cancelled && token === tokenRef.current) setFailed(true);
      });
    return () => {
      cancelled = true;
      if (url) URL.revokeObjectURL(url);
    };
  }, [src]);

  if (failed) {
    return <div className={cn('flex items-center justify-center bg-muted text-[10px] text-muted-foreground', placeholderClassName)}>加载失败</div>;
  }

  if (!objectUrl) {
    return <div className={cn('animate-pulse bg-muted', placeholderClassName)} />;
  }

  return <img src={objectUrl} alt={alt} loading={loading} className={className} />;
}
