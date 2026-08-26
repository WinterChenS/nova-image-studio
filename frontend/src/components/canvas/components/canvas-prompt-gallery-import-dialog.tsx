"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { AlertCircle, Check, ExternalLink, Image as ImageIcon, LibraryBig, Loader2, Search, X } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { ALL_CATEGORY, DEFAULT_CATEGORIES, PROMPT_DATA_SOURCES, getPromptSourceLabel, type PromptWithKey } from "@/lib/prompt-gallery-data";
// WIN-42 复审修复②：服务端 ILIKE/tag 搜索接线（不再全量拉取 + 客户端过滤）
import { searchPromptGallery, toPromptWithKey } from "@/lib/prompt-gallery-api";
import { cn } from "@/lib/utils";

type CanvasPromptGalleryImportDialogProps = {
  open: boolean;
  importing: boolean;
  onOpenChange: (open: boolean) => void;
  onConfirm: (prompt: PromptWithKey) => void;
};

const PAGE_STEP = 40;
/** 搜索输入防抖（ms） */
const SEARCH_DEBOUNCE_MS = 300;

let cachedBlacklist: string[] | null = null;

async function fetchPromptBlacklist(): Promise<string[]> {
  if (cachedBlacklist) return cachedBlacklist;
  try {
    const response = await fetch("/api/nova/blacklist");
    if (!response.ok) return [];
    const data = await response.json();
    const keywords: string[] = Array.isArray(data.keywords)
      ? data.keywords.map((keyword: string) => keyword.toLowerCase())
      : [];
    cachedBlacklist = keywords;
    return keywords;
  } catch {
    return [];
  }
}

function hasChinese(value: string) {
  return /[\u4e00-\u9fa5]/.test(value);
}

function isBlacklisted(prompt: PromptWithKey, blacklist: string[]) {
  if (!blacklist.length) return false;
  const content = [
    prompt.title,
    prompt.content,
    prompt.contributor || "",
    prompt.notes || "",
    prompt.tags.join(" "),
  ].join(" ").toLowerCase();
  return blacklist.some((keyword) => content.includes(keyword));
}

export function CanvasPromptGalleryImportDialog({ open, importing, onOpenChange, onConfirm }: CanvasPromptGalleryImportDialogProps) {
  const [items, setItems] = useState<PromptWithKey[]>([]);
  const [total, setTotal] = useState(0);
  const [categories, setCategories] = useState<string[]>(DEFAULT_CATEGORIES);
  const [blacklist, setBlacklist] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [debouncedQuery, setDebouncedQuery] = useState("");
  const [selectedCategory, setSelectedCategory] = useState(ALL_CATEGORY);
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const loadMoreRef = useRef<HTMLDivElement>(null);
  const pageRef = useRef(1);
  const requestIdRef = useRef(0);

  // 搜索输入防抖 → 服务端重新检索
  useEffect(() => {
    if (!open) return;
    const timer = setTimeout(() => setDebouncedQuery(query.trim()), SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [open, query]);

  const loadPage = useCallback(async (page: number, replace: boolean) => {
    const requestId = ++requestIdRef.current;
    setLoading(true);
    setError(null);
    try {
      const [result, keywords] = await Promise.all([
        searchPromptGallery({
          category: selectedCategory === ALL_CATEGORY ? undefined : selectedCategory,
          q: debouncedQuery || undefined,
          page,
          limit: PAGE_STEP,
        }),
        fetchPromptBlacklist(),
      ]);
      if (requestId !== requestIdRef.current) return;
      pageRef.current = page;
      setBlacklist(keywords);
      setTotal(result.total);
      if (Array.isArray(result.categories) && result.categories.length > 0) {
        setCategories([ALL_CATEGORY, ...result.categories.filter((c) => c !== ALL_CATEGORY)]);
      }
      setItems((prev) => {
        const mapped = result.items.map(toPromptWithKey);
        return replace ? mapped : [...prev, ...mapped];
      });
    } catch (err) {
      if (requestId === requestIdRef.current) {
        setError(err instanceof Error ? err.message : "提示词广场加载失败");
      }
    } finally {
      if (requestId === requestIdRef.current) setLoading(false);
    }
  }, [selectedCategory, debouncedQuery]);

  // 打开时拉第一页（setTimeout 宏任务，避免 effect 内同步 setState）
  useEffect(() => {
    if (!open) return;
    const timer = setTimeout(() => void loadPage(1, true), 0);
    return () => clearTimeout(timer);
  }, [open, loadPage]);

  // 关闭时重置（保留缓存分类/黑名单；延迟到宏任务避免同步 setState）
  useEffect(() => {
    if (open) return;
    const timer = setTimeout(() => {
      setQuery("");
      setDebouncedQuery("");
      setSelectedCategory(ALL_CATEGORY);
      setSelectedKey(null);
      setItems([]);
      setTotal(0);
      pageRef.current = 1;
    }, 0);
    return () => clearTimeout(timer);
  }, [open]);

  // 展示层过滤：黑名单 + 仅中文（沿用既有行为，不参与服务端检索）
  const filteredPrompts = useMemo(() => {
    return items.filter((prompt) => {
      if (isBlacklisted(prompt, blacklist)) return false;
      return hasChinese(prompt.title) || hasChinese(prompt.content);
    });
  }, [blacklist, items]);

  const hasMore = items.length < total;

  useEffect(() => {
    if (!open || !loadMoreRef.current || !scrollRef.current || loading || !hasMore) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting && items.length < total) {
          void loadPage(pageRef.current + 1, false);
        }
      },
      { root: scrollRef.current, rootMargin: "360px" },
    );
    observer.observe(loadMoreRef.current);
    return () => observer.disconnect();
  }, [filteredPrompts.length, open, loading, hasMore, items.length, total, loadPage]);

  const displayedPrompts = filteredPrompts;
  const selectedPrompt = selectedKey ? items.find((prompt) => prompt.uniqueKey === selectedKey) || null : null;

  const handleConfirm = () => {
    if (!selectedPrompt || importing) return;
    onConfirm(selectedPrompt);
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="flex h-[100dvh] max-h-[100dvh] w-screen flex-col sm:h-auto sm:max-h-[90dvh] sm:w-full sm:max-w-6xl sm:rounded-xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <LibraryBig className="h-4 w-4" />
            从提示词广场导入
            <span className="text-xs font-normal text-muted-foreground">
              {selectedPrompt ? "已选 1 / 1" : "选择 1 个模板"}
            </span>
          </DialogTitle>
        </DialogHeader>

        <div className="flex flex-wrap items-center gap-2 border-b pb-2">
          <div className="relative min-w-60 flex-1">
            <Search className="absolute top-1/2 left-2.5 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground" />
            <input
              type="text"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="搜索提示词、标题或作者"
              className="h-8 w-full rounded-md border border-input bg-background pr-8 pl-8 text-sm outline-none focus-visible:border-ring focus-visible:ring-2 focus-visible:ring-ring/30"
            />
            {query && (
              <button
                type="button"
                onClick={() => setQuery("")}
                className="absolute top-1/2 right-2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                title="清空搜索"
              >
                <X className="h-3.5 w-3.5" />
              </button>
            )}
          </div>
          <Button variant="outline" size="sm" onClick={() => onOpenChange(false)} disabled={importing}>
            取消
          </Button>
          <Button size="sm" onClick={handleConfirm} disabled={!selectedPrompt || importing}>
            {importing ? <Loader2 className="h-4 w-4 animate-spin" /> : <Check className="h-4 w-4" />}
            导入到画布
          </Button>
        </div>

        <div className="flex max-h-24 min-h-11 flex-wrap items-start gap-1.5 overflow-y-auto border-b py-1.5 pr-1 select-none overscroll-contain">
          {categories.map((category) => (
            <button
              key={category}
              type="button"
              onClick={() => setSelectedCategory(category)}
              className={cn(
                "inline-flex min-h-7 shrink-0 items-center rounded-full border px-2.5 text-xs leading-tight whitespace-nowrap transition-colors",
                selectedCategory === category ? "border-primary bg-primary text-primary-foreground" : "border-border hover:bg-muted",
              )}
            >
              {category}
            </button>
          ))}
        </div>

        {loading ? (
          <div className="flex min-h-64 items-center justify-center text-muted-foreground">
            <Loader2 className="h-5 w-5 animate-spin" />
          </div>
        ) : error ? (
          <div className="flex min-h-64 flex-col items-center justify-center gap-2 text-destructive">
            <AlertCircle className="h-8 w-8" />
            <p className="text-sm">{error}</p>
          </div>
        ) : filteredPrompts.length === 0 ? (
          <div className="flex min-h-64 flex-col items-center justify-center gap-2 text-muted-foreground">
            <LibraryBig className="h-8 w-8 opacity-50" />
            <p className="text-sm">没有匹配的提示词模板</p>
          </div>
        ) : (
          <div ref={scrollRef} className="min-h-0 flex-1 overflow-y-auto pr-1">
            <div className="grid grid-cols-1 gap-3 pb-3 md:grid-cols-2 xl:grid-cols-3">
              {displayedPrompts.map((prompt) => (
                <PromptImportCard
                  key={prompt.uniqueKey}
                  prompt={prompt}
                  selected={selectedKey === prompt.uniqueKey}
                  onSelect={() => setSelectedKey(prompt.uniqueKey)}
                  onConfirm={() => {
                    setSelectedKey(prompt.uniqueKey);
                    onConfirm(prompt);
                  }}
                  importing={importing}
                />
              ))}
            </div>
            {hasMore && (
              <div ref={loadMoreRef} className="flex items-center justify-center py-5 text-muted-foreground">
                <Loader2 className="h-5 w-5 animate-spin" />
              </div>
            )}
          </div>
        )}

        <div className="-mx-4 -mb-4 flex min-h-14 items-center justify-between gap-3 border-t bg-muted/50 px-4 py-3 text-xs">
          <span className="min-w-0 truncate text-muted-foreground">
            {selectedPrompt ? `将导入：${selectedPrompt.title}` : `共 ${total} 个模板 · 已加载 ${items.length} 个`}
          </span>
          <Popover>
            <PopoverTrigger className="inline-flex shrink-0 items-center gap-1 text-muted-foreground transition-colors hover:text-foreground">
              <span>提示词来源</span>
              <ExternalLink className="h-3 w-3" />
            </PopoverTrigger>
            <PopoverContent align="end" className="w-72 p-2">
              <p className="px-2 pb-1.5 text-xs font-medium text-muted-foreground">提示词来源（{PROMPT_DATA_SOURCES.length}）</p>
              <div className="space-y-0.5">
                {PROMPT_DATA_SOURCES.map((source) => (
                  <a
                    key={source.name}
                    href={source.sourceUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="flex items-center justify-between gap-2 rounded-md px-2 py-1.5 text-sm transition-colors hover:bg-muted"
                  >
                    <span className="truncate">{getPromptSourceLabel(source.sourceUrl)}</span>
                    <ExternalLink className="h-3 w-3 shrink-0 text-muted-foreground" />
                  </a>
                ))}
              </div>
            </PopoverContent>
          </Popover>
        </div>
      </DialogContent>
    </Dialog>
  );
}

function PromptImportCard({
  prompt,
  selected,
  importing,
  onSelect,
  onConfirm,
}: {
  prompt: PromptWithKey;
  selected: boolean;
  importing: boolean;
  onSelect: () => void;
  onConfirm: () => void;
}) {
  const image = prompt.images[0];

  return (
    <div
      role="button"
      tabIndex={importing ? -1 : 0}
      onClick={() => {
        if (!importing) onSelect();
      }}
      onKeyDown={(event) => {
        if (importing) return;
        if (event.key === "Enter" || event.key === " ") {
          event.preventDefault();
          onSelect();
        }
      }}
      className={cn(
        "group flex min-h-0 flex-col overflow-hidden rounded-md border bg-card text-left transition-colors",
        selected ? "border-primary ring-1 ring-primary/30" : "border-border hover:border-muted-foreground/30",
        importing && "cursor-not-allowed opacity-70",
      )}
      title={prompt.title}
    >
      <div className="grid grid-cols-[132px_minmax(0,1fr)] gap-3 p-2">
        <div className="relative aspect-square overflow-hidden rounded-md bg-muted">
          {image ? (
            <img src={image} alt={prompt.title} className="h-full w-full object-cover" loading="lazy" />
          ) : (
            <div className="flex h-full w-full items-center justify-center text-muted-foreground">
              <ImageIcon className="h-6 w-6 opacity-50" />
            </div>
          )}
          <span
            className={cn(
              "absolute top-1.5 right-1.5 flex h-5 min-w-5 items-center justify-center rounded-full border px-1 text-[10px] shadow-sm",
              selected ? "border-primary bg-primary text-primary-foreground" : "border-white/70 bg-black/35 text-white",
            )}
          >
            {selected ? <Check className="h-3 w-3" /> : prompt.images.length || 0}
          </span>
        </div>

        <div className="flex min-w-0 flex-col gap-2">
          <div className="min-w-0">
            <p className="truncate text-sm font-medium text-foreground">{prompt.title}</p>
            <p className="mt-1 line-clamp-3 text-xs leading-relaxed text-muted-foreground">{prompt.content}</p>
          </div>
          <div className="flex max-h-12 min-h-5 flex-wrap gap-1 overflow-y-auto pr-1">
            {prompt.tags.length > 0 ? (
              prompt.tags.slice(0, 4).map((tag) => (
                <Badge key={tag} variant="outline" className="h-auto min-h-5 max-w-full px-1.5 py-0.5 text-[10px] leading-tight">
                  {tag}
                </Badge>
              ))
            ) : (
              <span className="text-[11px] text-muted-foreground">无标签</span>
            )}
          </div>
          <div className="mt-auto flex items-center justify-between gap-2">
            <span className="truncate text-[11px] text-muted-foreground">{prompt.contributor || prompt.source || "未知来源"}</span>
            <Button
              type="button"
              size="xs"
              variant={selected ? "default" : "outline"}
              onClick={(event) => {
                event.stopPropagation();
                onConfirm();
              }}
              disabled={importing}
              className="shrink-0"
            >
              导入
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}
