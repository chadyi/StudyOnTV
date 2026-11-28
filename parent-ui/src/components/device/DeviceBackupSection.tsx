import { useRef, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { ArchiveRestore, Download, HardDriveDownload, LoaderCircle, Upload } from 'lucide-react';
import { toast } from 'sonner';
import { api } from '@/api';
import { readFileText } from '@/document-import';
import { download } from '@/components/bank/shared';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Field, FieldLabel } from '@/components/ui/field';
import { SettingItem, SettingsCard } from './DeviceSettingsFrame';

export default function DeviceBackupSection() {
  const client = useQueryClient();
  const [includeQuestions, setIncludeQuestions] = useState(true);
  const [exportingBackup, setExportingBackup] = useState(false);
  const backupFileRef = useRef<HTMLInputElement>(null);
  const [pendingBackup, setPendingBackup] = useState<{ fileName: string; data: any } | null>(null);
  const [restoreMode, setRestoreMode] = useState<'replace' | 'merge'>('replace');

  async function handleExportBackup() {
    if (exportingBackup) return;
    setExportingBackup(true);
    try {
      const data = await api(
        `/api/backup?includeQuestions=${includeQuestions ? 1 : 0}`,
        'GET',
        undefined,
        60000,
      );
      const now = new Date();
      const ymd = `${now.getFullYear()}${String(now.getMonth() + 1).padStart(2, '0')}${String(now.getDate()).padStart(2, '0')}`;
      download(data, `studyontv-backup-${ymd}.json`);
      toast.success('整机备份已导出');
    } catch (error: any) {
      toast.error(error?.message || '导出整机备份失败');
    } finally {
      setExportingBackup(false);
    }
  }

  async function handlePickBackupFile(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (backupFileRef.current) backupFileRef.current.value = '';
    if (!file) return;
    try {
      const text = (await readFileText(file)).replace(/^\uFEFF/, '').trim();
      const parsed = JSON.parse(text);
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
        throw new Error('备份文件格式不正确');
      }
      setRestoreMode('replace');
      setPendingBackup({ fileName: file.name, data: parsed });
    } catch (error: any) {
      toast.error(error?.message ? `读取备份失败：${error.message}` : '备份文件不是有效的 JSON');
    }
  }

  const restoreBackup = useMutation({
    mutationFn: ({ data, mode }: { data: any; mode: 'replace' | 'merge' }) =>
      api(`/api/backup/restore?mode=${mode}`, 'POST', { backup: data, mode }, 60000),
    onSuccess: (res: any) => {
      const summary = res?.summary || res || {};
      const backupObj = pendingBackup?.data?.backup || pendingBackup?.data || {};
      const kidsCount =
        summary.children ??
        summary.childrenRestored ??
        summary.childCount ??
        (Array.isArray(backupObj.children) ? backupObj.children.length : 1);
      const qCount =
        summary.questions ??
        summary.questionsRestored ??
        summary.added ??
        summary.questionCount ??
        (Array.isArray(backupObj.questions)
          ? backupObj.questions.length
          : Array.isArray(backupObj.items)
            ? backupObj.items.length
            : 0);
      setPendingBackup(null);
      client.invalidateQueries();
      toast.success(
        res?.message || `已恢复 ${kidsCount} 个孩子档案、${qCount} 道题目及相关学习记录`,
      );
    },
    onError: (error: any) => toast.error(error?.message || '恢复备份失败'),
  });

  return (
    <>
      <SettingsCard
        title="数据备份与换机迁移"
        icon={HardDriveDownload}
        description="一键打包全部孩子档案、规则计划、学习记录与题库配图，换新电视或新平板可直接恢复。"
      >
        <SettingItem
          title="导出整机备份"
          description="导出为单文件 JSON，保存在手机或电脑本地；不含管理密码与云端密钥。"
          wideControl
        >
          <div className="flex flex-wrap items-center justify-end gap-3">
            <Field orientation="horizontal" className="w-auto gap-2">
              <Checkbox
                id="backup-include-questions"
                checked={includeQuestions}
                onCheckedChange={checked => setIncludeQuestions(checked === true)}
              />
              <FieldLabel htmlFor="backup-include-questions" className="font-normal text-xs">
                包含题库与配图
              </FieldLabel>
            </Field>
            <Button size="sm" variant="outline" disabled={exportingBackup} onClick={handleExportBackup}>
              {exportingBackup ? <LoaderCircle className="animate-spin" /> : <Download />}
              {exportingBackup ? '正在打包…' : '导出整机备份'}
            </Button>
          </div>
        </SettingItem>
        <SettingItem
          title="恢复备份"
          description="从之前导出的 studyontv-backup-*.json 恢复数据，支持全量覆盖换机或合并导入。"
          wideControl
        >
          <input
            ref={backupFileRef}
            type="file"
            accept=".json,application/json,text/plain"
            tabIndex={-1}
            className="sr-only"
            onChange={handlePickBackupFile}
          />
          <Button
            size="sm"
            variant="outline"
            disabled={restoreBackup.isPending}
            onClick={() => backupFileRef.current?.click()}
          >
            <Upload />
            选择备份文件恢复
          </Button>
        </SettingItem>
      </SettingsCard>

      <Dialog
        open={!!pendingBackup}
        onOpenChange={open => {
          if (!open && !restoreBackup.isPending) setPendingBackup(null);
        }}
      >
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <ArchiveRestore className="size-4 text-primary" />
              确认恢复整机备份
            </DialogTitle>
            <DialogDescription>
              已读取备份文件 <span className="font-mono text-foreground">{pendingBackup?.fileName}</span>，请选择恢复方式：
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-2.5 py-2">
            <button
              type="button"
              onClick={() => setRestoreMode('replace')}
              className={`flex w-full flex-col items-start gap-1 rounded-lg border p-3 text-left transition-colors ${
                restoreMode === 'replace'
                  ? 'border-primary bg-primary/5 ring-1 ring-primary/30'
                  : 'border-border/70 hover:bg-muted/40'
              }`}
            >
              <span className="text-sm font-medium">覆盖恢复（推荐换机使用）</span>
              <span className="text-xs text-muted-foreground">
                用备份完整替换当前设备上的孩子档案、规则计划与题库记录，适合新设备迁移还原。
              </span>
            </button>
            <button
              type="button"
              onClick={() => setRestoreMode('merge')}
              className={`flex w-full flex-col items-start gap-1 rounded-lg border p-3 text-left transition-colors ${
                restoreMode === 'merge'
                  ? 'border-primary bg-primary/5 ring-1 ring-primary/30'
                  : 'border-border/70 hover:bg-muted/40'
              }`}
            >
              <span className="text-sm font-medium">合并导入</span>
              <span className="text-xs text-muted-foreground">
                保留当前设备已有数据，将备份中的题库与孩子档案合并进来（相同 ID 更新）。
              </span>
            </button>
          </div>
          <DialogFooter>
            <Button variant="outline" disabled={restoreBackup.isPending} onClick={() => setPendingBackup(null)}>
              取消
            </Button>
            <Button
              disabled={restoreBackup.isPending || !pendingBackup}
              onClick={() =>
                pendingBackup && restoreBackup.mutate({ data: pendingBackup.data, mode: restoreMode })
              }
            >
              {restoreBackup.isPending ? <LoaderCircle className="animate-spin" /> : null}
              {restoreBackup.isPending ? '正在恢复…' : '确认恢复'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
