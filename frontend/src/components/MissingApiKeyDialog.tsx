'use client';

/**
 * WIN-25 (T19, A2) — 缺失模型提示：语义由「请先配置 API 密钥」改为
 * 「目录无可用模型，请联系管理员」——用户自维护 Key 已移除（Q1 直接移除），
 * 模型由管理员在账号池/模型目录统一配置。
 */

import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';

interface MissingApiKeyDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export function MissingApiKeyDialog({ open, onOpenChange }: MissingApiKeyDialogProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>暂无可用的模型</DialogTitle>
          <DialogDescription>
            模型目录当前没有可用账号（或模型已被禁用），暂时无法生成或转换图片。
            请联系管理员在「管理控制台 → 账号池管理」中配置模型账号。
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button onClick={() => onOpenChange(false)}>
            知道了
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
