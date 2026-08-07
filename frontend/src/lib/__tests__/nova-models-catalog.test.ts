import { beforeEach, describe, expect, it } from 'vitest';
import {
  clearCatalogCache,
  getCatalogCache,
  loadRegistry,
  setCatalogCache,
  getCompleteImageModels,
  getCompleteTextModels,
  setRegistryCache,
  clearRegistryCache,
  type ImageModelConfig,
} from '@/lib/nova-models';
import type { CatalogModel } from '@/lib/catalog-api';

/**
 * WIN-30 (T18, A2/A5) — 目录 → 注册表桥接：loadRegistry() 以目录为唯一模型源
 * （用户自维护 Key 已移除）；available=false 模型随配置进入注册表但不被
 * getCompleteImageModels 之外的默认回退选用（A5 由渲染层禁用）。
 */
describe('nova-models catalog bridge', () => {
  beforeEach(() => {
    clearCatalogCache();
    clearRegistryCache();
  });

  const catalog: CatalogModel[] = [
    {
      id: 'img-1', type: 'image', protocol: 'google', name: 'Banana', modelId: 'gemini-2.5-flash-image',
      baseUrl: 'https://generativelanguage.googleapis.com', enabled: true, available: true,
      builtinPreset: 'gemini-2.5-flash-image', maxRefImages: 3, maxOutputSize: '1K', supportsAdvancedParams: false,
    },
    {
      id: 'img-2', type: 'image', protocol: 'openai', name: 'GPT Image 2', modelId: 'gpt-image-2',
      baseUrl: 'https://api.openai.com', enabled: true, available: false,
      builtinPreset: 'gpt-image-2', maxRefImages: 16, maxOutputSize: '4K', supportsAdvancedParams: true,
    },
    {
      id: 'txt-1', type: 'text', protocol: 'openai-responses', name: 'GPT 5.4 Mini', modelId: 'gpt-5.4-mini',
      baseUrl: 'https://api.openai.com', enabled: true, available: true, note: '',
    },
    {
      id: 'img-disabled', type: 'image', protocol: 'google', name: 'Disabled', modelId: 'x',
      baseUrl: 'https://x.example.com', enabled: false, available: false,
      builtinPreset: 'gemini-2.5-flash-image',
    },
  ];

  it('serves catalog models via loadRegistry when hydrated', () => {
    setCatalogCache(catalog);
    const registry = loadRegistry();
    expect(registry.imageModels.map((m) => m.id)).toEqual(['img-1', 'img-2']);
    expect(registry.textModels.map((m) => m.id)).toEqual(['txt-1']);
    // 禁用的目录模型不出现在下拉（A5）
    expect(registry.imageModels.some((m) => m.id === 'img-disabled')).toBe(false);
  });

  it('marks catalog models as configured with available flag', () => {
    setCatalogCache(catalog);
    const registry = loadRegistry();
    const img1 = registry.imageModels.find((m) => m.id === 'img-1') as ImageModelConfig;
    expect(img1.apiKey).toBeTruthy();   // 判定「已配置」不再依赖用户 Key
    expect(img1.available).toBe(true);
    const img2 = registry.imageModels.find((m) => m.id === 'img-2') as ImageModelConfig;
    expect(img2.available).toBe(false);
  });

  it('getCompleteImageModels includes available and unavailable (rendering layer disables)', () => {
    setCatalogCache(catalog);
    const complete = getCompleteImageModels(loadRegistry());
    expect(complete.some((m) => m.id === 'img-1')).toBe(true);
    expect(complete.some((m) => m.id === 'img-2')).toBe(true); // 保留用于禁用渲染
  });

  it('getCompleteTextModels returns catalog text models', () => {
    setCatalogCache(catalog);
    expect(getCompleteTextModels(loadRegistry()).map((m) => m.id)).toEqual(['txt-1']);
  });

  it('defaults fall back to the first available catalog model', () => {
    setCatalogCache(catalog);
    const registry = loadRegistry();
    expect(registry.defaults.textToImage).toBe('img-1');
  });

  it('falls back to registry cache when no catalog is hydrated', () => {
    setRegistryCache({
      imageModels: [{ id: 'legacy', protocol: 'google', name: '旧模型', modelId: 'old', apiKey: 'sk-1', baseUrl: 'https://x', builtinPreset: 'gemini-2.5-flash-image', maxRefImages: 0, maxOutputSize: '1K', supportsAdvancedParams: false }],
      textModels: [],
      defaults: { textToImage: 'legacy', imageToImage: '', reversePrompt: '', agent: '', promptOptimize: '', imageDescribe: '' },
    });
    const registry = loadRegistry();
    expect(registry.imageModels[0]?.id).toBe('legacy');
  });

  it('exposes the catalog cache', () => {
    expect(getCatalogCache()).toBeNull();
    setCatalogCache(catalog);
    expect(getCatalogCache()).toHaveLength(4);
  });
});
