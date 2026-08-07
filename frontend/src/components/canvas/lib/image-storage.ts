"use client";

/**
 * WIN-39 (WIN-40 T6) — 画布图片存储：节点图片字节统一上云（assets source_kind='canvas'，
 * ADR-35 素材引用化，节点 metadata.storageKey = assetId）。旧导入文件中的本地
 * storageKey（`image:` 前缀 → IndexedDB）仍可读取（importProject 兼容旧导出，AC-3）。
 */

import localforage from "localforage";

import { nanoid } from "nanoid";
import { readImageMeta } from "./image-utils";
import { canvasImageUrl, uploadCanvasImage } from "@/lib/canvas-api";
import { isLoggedIn } from "@/lib/auth";

export type UploadedImage = {
  url: string;
  storageKey: string;
  width: number;
  height: number;
  bytes: number;
  mimeType: string;
};

const store = localforage.createInstance({ name: "nova-image", storeName: "canvas_image_files" });
const objectUrls = new Map<string, string>();

/** 本地 storageKey 前缀（旧导入/迁移源数据） */
const LEGACY_PREFIX = "image:";

function isServerAssetKey(storageKey: string): boolean {
  // 服务端 assetId 为 UUID 或 assets/ 引用
  return /^[0-9a-fA-F-]{36}$/.test(storageKey) || storageKey.startsWith("assets/");
}

/**
 * 上传图片字节：登录态下上传服务端 assets（返回 assetId 引用）；未登录降级本地 IndexedDB
 * （兼容离线/旧文件导入）。
 */
export async function uploadImage(input: string | Blob): Promise<UploadedImage> {
  const blob = typeof input === "string" ? await (await fetch(input)).blob() : input;
  const blobUrl = URL.createObjectURL(blob);
  let meta;
  try {
    meta = await readImageMeta(blobUrl);
  } finally {
    URL.revokeObjectURL(blobUrl);
  }
  if (isLoggedIn()) {
    try {
      const uploaded = await uploadCanvasImage(blob, undefined, meta.width, meta.height);
      const assetId = uploaded.assetId ?? uploaded.id;
      return {
        url: canvasImageUrl(assetId),
        storageKey: assetId,
        width: meta.width,
        height: meta.height,
        bytes: blob.size,
        mimeType: blob.type || meta.mimeType,
      };
    } catch {
      // 上传失败降级本地（离线/配额），保存状态机 error 态由上层提示
    }
  }
  const storageKey = `${LEGACY_PREFIX}${nanoid()}`;
  await store.setItem(storageKey, blob);
  const url = URL.createObjectURL(blob);
  objectUrls.set(storageKey, url);
  return { url, storageKey, width: meta.width, height: meta.height, bytes: blob.size, mimeType: blob.type || meta.mimeType };
}

export async function resolveImageUrl(storageKey?: string, fallback = "") {
  if (!storageKey) return fallback;
  // 服务端素材引用（assetId）→ 鉴权路径（渲染层用 AuthenticatedImage）
  if (isServerAssetKey(storageKey)) {
    return canvasImageUrl(storageKey);
  }
  const cached = objectUrls.get(storageKey);
  if (cached) return cached;
  const blob = await store.getItem<Blob>(storageKey);
  if (!blob) return fallback;
  const url = URL.createObjectURL(blob);
  objectUrls.set(storageKey, url);
  return url;
}

export async function getImageBlob(storageKey: string) {
  if (isServerAssetKey(storageKey)) return null;   // 服务端字节经鉴权 API 读取
  return store.getItem<Blob>(storageKey);
}

export async function setImageBlob(storageKey: string, blob: Blob) {
  await store.setItem(storageKey, blob);
  const url = URL.createObjectURL(blob);
  objectUrls.set(storageKey, url);
  return url;
}

export async function imageToDataUrl(image: { url?: string; dataUrl?: string; storageKey?: string }): Promise<string> {
  // 优先用 storageKey（IndexedDB 本地 或 服务端 assetId），避免刷新后 blob: URL 失效导致 fetch 失败
  if (image.storageKey && !isServerAssetKey(image.storageKey)) {
    const blob = await store.getItem<Blob>(image.storageKey);
    if (blob) return blobToDataUrl(blob);
  }
  const url = image.dataUrl || image.url || "";
  if (!url) throw new Error("图片数据不可用（可能已刷新丢失），请重新上传或从素材库导入");
  if (url.startsWith("data:")) return url;
  if (url.startsWith("/api/")) {
    // 服务端图片：经鉴权读取后转 dataURL（生图上游引用）
    const { authFetch } = await import("@/lib/auth");
    const response = await authFetch(url, { cache: "no-store" });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return blobToDataUrl(await response.blob());
  }
  // blob: URL 可能已失效（刷新后），尝试 fetch
  try {
    const response = await fetch(url);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return blobToDataUrl(await response.blob());
  } catch {
    throw new Error("图片加载失败（blob URL 可能已失效），请重新上传或从素材库导入");
  }
}

export async function deleteStoredImages(keys: Iterable<string>) {
  await Promise.all(
    Array.from(new Set(keys)).map(async (key) => {
      if (isServerAssetKey(key)) return;   // 服务端素材软删由素材库/回收站管理
      const url = objectUrls.get(key);
      if (url) URL.revokeObjectURL(url);
      objectUrls.delete(key);
      await store.removeItem(key);
    }),
  );
}

export async function cleanupUnusedImages(usedData: unknown) {
  const usedKeys = collectImageStorageKeys(usedData);
  const unused: string[] = [];
  await store.iterate((_value, key) => {
    if (!usedKeys.has(key)) unused.push(key);
  });
  await deleteStoredImages(unused);
}

export function collectImageStorageKeys(value: unknown, keys = new Set<string>()) {
  if (!value || typeof value !== "object") return keys;
  if ("storageKey" in value && typeof value.storageKey === "string" && value.storageKey.startsWith(LEGACY_PREFIX)) keys.add(value.storageKey);
  Object.values(value).forEach((item) => (Array.isArray(item) ? item.forEach((child) => collectImageStorageKeys(child, keys)) : collectImageStorageKeys(item, keys)));
  return keys;
}

function blobToDataUrl(blob: Blob) {
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || ""));
    reader.onerror = () => reject(new Error("读取图片失败"));
    reader.readAsDataURL(blob);
  });
}
