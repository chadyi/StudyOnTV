import { useState, type RefObject } from 'react';
import { ImagePlus, Link2, Upload, X } from 'lucide-react';
import { toast } from 'sonner';
import { resolveMediaUrl } from '@/api';
import { Button } from '@/components/ui/button';
import { Field as UiField } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { formatImageHint } from './question-image';

type Props = {
  images: string[];
  maxImages: number;
  uploading: boolean;
  fileRef: RefObject<HTMLInputElement | null>;
  onAddFiles: (files: File[]) => void;
  onAddUrl: (url: string) => void;
  onRemove: (index: number) => void;
};

export default function QuestionImageField({
  images,
  maxImages,
  uploading,
  fileRef,
  onAddFiles,
  onAddUrl,
  onRemove,
}: Props) {
  const [urlInput, setUrlInput] = useState('');
  const [showUrlInput, setShowUrlInput] = useState(false);

  function addUrl() {
    const u = urlInput.trim();
    if (!u) return;
    if (!/^(\/api\/media\/.+|https?:\/\/.+)/.test(u)) {
      toast.error('请输入 http://、https:// 或 /api/media/ 开头的图片地址');
      return;
    }
    if (images.length >= maxImages) {
      toast.error(`每道题最多支持 ${maxImages} 张配图`);
      return;
    }
    if (images.includes(u)) {
      toast.info('这张图片已在列表中');
      return;
    }
    onAddUrl(u);
    setUrlInput('');
    setShowUrlInput(false);
  }

  function handleDrop(e: React.DragEvent) {
    e.preventDefault();
    if (uploading) return;
    const list = Array.from(e.dataTransfer.files || []).filter(
      f => f.type.startsWith('image/') || /\.(heic|heif)$/i.test(f.name || ''),
    );
    if (list.length) onAddFiles(list);
  }

  return (
    <UiField onDragOver={e => e.preventDefault()} onDrop={handleDrop}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <label className="text-sm font-medium">题目配图（可选，最多 {maxImages} 张）</label>
        <div className="flex items-center gap-1.5">
          <input
            ref={fileRef}
            type="file"
            accept="image/*"
            multiple
            tabIndex={-1}
            className="sr-only"
            onChange={e => {
              const list = Array.from(e.target.files || []);
              if (list.length) onAddFiles(list);
            }}
          />
          <Button
            type="button"
            size="sm"
            variant="outline"
            disabled={uploading || images.length >= maxImages}
            onClick={() => fileRef.current?.click()}
          >
            <Upload className="size-3.5" />
            {uploading ? '压缩中…' : '上传图片'}
          </Button>
          <Button
            type="button"
            size="sm"
            variant="ghost"
            disabled={images.length >= maxImages}
            onClick={() => setShowUrlInput(v => !v)}
          >
            <Link2 className="size-3.5" />
            图片链接
          </Button>
        </div>
      </div>
      {showUrlInput && (
        <div className="mt-2 flex gap-2">
          <Input
            value={urlInput}
            onChange={e => setUrlInput(e.target.value)}
            placeholder="https://example.com/diagram.png"
            onKeyDown={e => {
              if (e.key === 'Enter') {
                e.preventDefault();
                addUrl();
              }
            }}
          />
          <Button type="button" size="sm" onClick={addUrl}>添加</Button>
        </div>
      )}
      {images.length > 0 ? (
        <div className="mt-2 grid grid-cols-2 gap-2 sm:grid-cols-4">
          {images.map((src, i) => (
            <div key={i} className="group relative overflow-hidden rounded-md border bg-muted/30">
              <img src={resolveMediaUrl(src)} alt={`配图 ${i + 1}`} className="h-24 w-full object-contain p-1" />
              <div className="flex items-center justify-between border-t bg-background/90 px-2 py-0.5 text-[11px] text-muted-foreground">
                <span>{formatImageHint(src)}</span>
                <button
                  type="button"
                  aria-label={`移除第 ${i + 1} 张配图`}
                  className="rounded p-0.5 text-muted-foreground hover:bg-destructive/10 hover:text-destructive"
                  onClick={() => onRemove(i)}
                >
                  <X className="size-3.5" />
                </button>
              </div>
            </div>
          ))}
        </div>
      ) : (
        <button
          type="button"
          onClick={() => fileRef.current?.click()}
          className="mt-1 flex w-full items-center justify-center gap-2 rounded-md border border-dashed py-3 text-xs text-muted-foreground transition-colors hover:border-primary/50 hover:text-foreground"
        >
          <ImagePlus className="size-4" />
          点击上传、拖拽图片到此处，或在题干框按 Ctrl+V 粘贴截图（自动压缩）
        </button>
      )}
    </UiField>
  );
}
