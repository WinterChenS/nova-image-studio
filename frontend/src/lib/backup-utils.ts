'use client';

/**
 * WIN-42 (T17, ADR-41) — 备份/恢复下线后的「读取能力」模块。
 *
 * <p>C10：backup-utils 的下载打包（JSZip）与恢复入口已从 UI 下线；本模块仅保留
 * IndexedDB/localStorage 的<b>读取能力</b>（exportIndexedDB / exportLocalStorage /
 * exportLocalForage），供迁移工具与本地存量清理（migration.ts）引用。打包、导入、
 * 下载相关逻辑全部移除，不再提供任何备份/恢复入口。
 */

import localforage from 'localforage';

export interface BackupProgress {
    percent: number;
    message: string;
}

export type ProgressCallback = (progress: BackupProgress) => void;

type BackupRecord = Record<string, unknown>;
type DatabaseBackup = Record<string, BackupRecord[]>;
type IndexedDBBackup = Record<string, DatabaseBackup>;

// M2：迁移到服务端后应清除的设置类 localStorage key（UI 偏好保留）
export const LEGACY_SETTING_KEYS_TO_CLEAR = [
    'nova-model-registry',
    'nova-t2i-settings',
    'nova-i2i-settings',
    'nova-reverse-prompt-settings',
    'nova-agent-params',
    'nova-agent-web-search',
    'nova-agent-intent-recognition',
    'nova-gif-settings',
];

// localStorage keys to backup（读取能力保留，供迁移/清理引用）
export const LOCAL_STORAGE_KEYS = [
    'nova-model-registry',
    'nova-jobs',
    'nova-t2i-settings',
    'nova-i2i-settings',
    'nova-reverse-prompt-settings',
    'theme',
    'nova-wide-mode',
    // Agent 模式
    'nova-agent-params',
    'nova-agent-web-search',
    'nova-agent-intent-recognition',
    // 动图生成
    'nova-gif-settings',
    'nova-gif-active-job',
    // 我的素材
    'nova-assets-settings',
    // 无限画布生成配置
    'nova-image:canvas_config',
];

// IndexedDB databases to backup
export const INDEXEDDB_DATABASES = [
    { name: 'nova-image-db', version: 2, stores: ['images', 'blobs'] },
    { name: 'nova-reverse-db', version: 1, stores: ['reverse-results'] },
    { name: 'nova-upload-cache', version: 1, stores: ['images'] },
    // Agent 模式对话、图片登记、元信息
    { name: 'nova-agent-db', version: 1, stores: ['messages', 'images', 'meta'] },
    // 本地图片素材库
    { name: 'nova-assets-db', version: 1, stores: ['assets', 'asset-blobs'] },
];

// localforage keyless 实例（无限画布：项目状态 + 图片 blob）
const LOCALFORAGE_STORES: { name: string; storeName: string }[] = [
    { name: 'nova-image', storeName: 'canvas_app_state' },
    { name: 'nova-image', storeName: 'canvas_image_files' },
];

type LocalForageEntry = { key: string; value: unknown } | { key: string; _blobRef: string; _blobMimeType: string };
type LocalForageBackup = Record<string, Record<string, LocalForageEntry[]>>;

// 用于生成读取时 Blob 的唯一引用 ID
let _blobRefSeq = 0;
function nextBlobRef(): string {
    return `b${Date.now()}_${++_blobRefSeq}`;
}

/**
 * 导出 localforage（keyless）store：保留 key；Blob 值以引用标记（读取能力，
 * 不再打包 ZIP）。数据逐 store 写入 result。
 */
export async function exportLocalForage(): Promise<LocalForageBackup> {
    const result: LocalForageBackup = {};
    for (const cfg of LOCALFORAGE_STORES) {
        try {
            const instance = localforage.createInstance({ name: cfg.name, storeName: cfg.storeName });
            const entries: LocalForageEntry[] = [];
            await instance.iterate((value: unknown, key: string) => {
                if (value instanceof Blob) {
                    const ref = nextBlobRef();
                    entries.push({ key, _blobRef: ref, _blobMimeType: value.type });
                } else {
                    entries.push({ key, value });
                }
            });
            if (!result[cfg.name]) result[cfg.name] = {};
            result[cfg.name][cfg.storeName] = entries;
        } catch {
            // skip failed localforage export
        }
    }
    return result;
}

/**
 * 导出 localStorage 数据（读取能力）。
 */
export function exportLocalStorage(): Record<string, string> {
    const data: Record<string, string> = {};

    for (const key of LOCAL_STORAGE_KEYS) {
        try {
            const value = localStorage.getItem(key);
            if (value !== null) {
                data[key] = value;
            }
        } catch {
            // skip failed localStorage export
        }
    }

    return data;
}

/**
 * 打开 IndexedDB 数据库
 */
export function openDatabase(name: string, version: number, createStores: boolean = false): Promise<IDBDatabase | null> {
    return new Promise((resolve) => {
        if (typeof indexedDB === 'undefined') {
            resolve(null);
            return;
        }

        const request = indexedDB.open(name, version);

        request.onerror = () => resolve(null);
        request.onsuccess = () => resolve(request.result);
        request.onupgradeneeded = (e) => {
            const db = (e.target as IDBOpenDBRequest).result;
            const oldVersion = e.oldVersion || 0;
            if (!createStores && oldVersion > 0) return;

            // 根据数据库名称创建相应的 stores
            if (name === 'nova-image-db') {
                if (!db.objectStoreNames.contains('images')) {
                    db.createObjectStore('images', { keyPath: 'id' });
                }
                if (!db.objectStoreNames.contains('blobs')) {
                    db.createObjectStore('blobs', { keyPath: 'key' });
                }
            } else if (name === 'nova-reverse-db') {
                if (!db.objectStoreNames.contains('reverse-results')) {
                    db.createObjectStore('reverse-results', { keyPath: 'slot' });
                }
            } else if (name === 'nova-upload-cache') {
                if (!db.objectStoreNames.contains('images')) {
                    db.createObjectStore('images', { keyPath: 'key' });
                }
            } else if (name === 'nova-agent-db') {
                if (!db.objectStoreNames.contains('messages')) {
                    db.createObjectStore('messages', { keyPath: 'id' });
                }
                if (!db.objectStoreNames.contains('images')) {
                    db.createObjectStore('images', { keyPath: 'imgId' });
                }
                if (!db.objectStoreNames.contains('meta')) {
                    db.createObjectStore('meta', { keyPath: 'key' });
                }
            } else if (name === 'nova-assets-db') {
                if (!db.objectStoreNames.contains('assets')) {
                    const store = db.createObjectStore('assets', { keyPath: 'id' });
                    store.createIndex('hash', 'hash', { unique: false });
                    store.createIndex('createdAt', 'createdAt', { unique: false });
                }
                if (!db.objectStoreNames.contains('asset-blobs')) {
                    db.createObjectStore('asset-blobs', { keyPath: 'key' });
                }
            }
        };
    });
}

/**
 * 导出单个 IndexedDB store 的所有数据（读取能力：Blob 以引用标记，不再打包）。
 */
export async function exportStore(db: IDBDatabase, storeName: string): Promise<BackupRecord[]> {
    return new Promise((resolve, reject) => {
        try {
            const transaction = db.transaction(storeName, 'readonly');
            const store = transaction.objectStore(storeName);
            const request = store.getAll();

            request.onsuccess = async () => {
                const records = request.result;

                const processedRecords = await Promise.all(
                    records.map(async (record) => {
                        const processed = { ...record };

                        // 遍历所有字段，将 Blob 类型以引用标记（读取能力，不再存字节）
                        for (const key of Object.keys(processed)) {
                            const val = processed[key];
                            if (val instanceof Blob) {
                                processed[key] = { _blobRef: nextBlobRef(), _blobMimeType: val.type };
                            }
                        }

                        return processed;
                    })
                );

                resolve(processedRecords);
            };

            request.onerror = () => reject(request.error);
        } catch (error) {
            reject(error);
        }
    });
}

/**
 * 导出所有 IndexedDB 数据（读取能力：逐数据库、逐 store 顺序读取，返回结构化数据）。
 */
export async function exportIndexedDB(onProgress?: ProgressCallback): Promise<IndexedDBBackup> {
    const allData: IndexedDBBackup = {};
    let completedStores = 0;
    const totalStores = INDEXEDDB_DATABASES.reduce((sum, db) => sum + db.stores.length, 0);

    for (const dbConfig of INDEXEDDB_DATABASES) {
        const db = await openDatabase(dbConfig.name, dbConfig.version);

        if (!db) {
            continue;
        }

        const dbData: DatabaseBackup = {};

        for (const storeName of dbConfig.stores) {
            try {
                if (!db.objectStoreNames.contains(storeName)) {
                    continue;
                }

                const storeData = await exportStore(db, storeName);
                dbData[storeName] = storeData;

                completedStores++;
                if (onProgress) {
                    const percent = 10 + Math.floor((completedStores / totalStores) * 80);
                    onProgress({
                        percent,
                        message: `正在读取 ${dbConfig.name}/${storeName}...`,
                    });
                }
            } catch {
                // store export failed, continue with next
            }
        }

        db.close();
        allData[dbConfig.name] = dbData;
    }

    return allData;
}
