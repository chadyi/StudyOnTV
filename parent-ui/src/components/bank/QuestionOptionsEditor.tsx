import { useRef } from 'react';
import { Check, ImagePlus, Plus, X } from 'lucide-react';
import { resolveMediaUrl } from '@/api';
import { Button } from '@/components/ui/button';
import { Field as UiField, FieldDescription, FieldGroup } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { Field } from './shared';

type Props = {
  type: string;
  opts: string[];
  optionImages: string[];
  correct: number[];
  uploading: boolean;
  onChangeField: (key: string, value: any) => void;
  onSetOptionImage: (index: number, file: File) => void;
  onClearOptionImage: (index: number) => void;
};

export default function QuestionOptionsEditor({
  type,
  opts,
  optionImages,
  correct,
  uploading,
  onChangeField,
  onSetOptionImage,
  onClearOptionImage,
}: Props) {
  const optFileRef = useRef<HTMLInputElement>(null);
  const optTargetRef = useRef<number>(0);

  return (
    <Field label="选项">
      <FieldDescription>
        {type === 'multiple'
          ? '点击字母标记全部正确答案，支持为选项单独上传或粘贴配图。'
          : '点击字母标记正确答案，支持为选项单独上传或粘贴配图。'}
      </FieldDescription>
      <input
        ref={optFileRef}
        type="file"
        accept="image/*"
        tabIndex={-1}
        className="sr-only"
        onChange={e => {
          const file = e.target.files?.[0];
          if (file) onSetOptionImage(optTargetRef.current, file);
          if (optFileRef.current) optFileRef.current.value = '';
        }}
      />
      <FieldGroup className="gap-2">
        {(type === 'boolean' ? ['正确', '错误'] : opts).map((o: string, i: number) => {
          const on = correct.includes(i);
          const optImg = type === 'boolean' ? '' : (optionImages[i] || '');
          return (
            <div key={i} className="space-y-1.5">
              <UiField orientation="horizontal" className="gap-2">
                <Button
                  type="button"
                  size="icon"
                  variant={on ? 'default' : 'outline'}
                  className="shrink-0 rounded-full text-xs font-semibold"
                  aria-label={`标记选项 ${String.fromCharCode(65 + i)} 为正确答案`}
                  aria-pressed={on}
                  onClick={() =>
                    onChangeField(
                      'correct',
                      type === 'multiple'
                        ? on
                          ? correct.filter(x => x !== i)
                          : [...correct, i]
                        : [i],
                    )
                  }
                >
                  {on ? <Check /> : String.fromCharCode(65 + i)}
                </Button>
                {type === 'boolean' ? (
                  <FieldDescription className="text-foreground">{o}</FieldDescription>
                ) : (
                  <>
                    <Input
                      aria-label={`选项 ${String.fromCharCode(65 + i)}`}
                      value={o}
                      placeholder={`选项 ${String.fromCharCode(65 + i)}（可直接粘贴截图）`}
                      onChange={e =>
                        onChangeField(
                          'opts',
                          opts.map((x, j) => (j === i ? e.target.value : x)),
                        )
                      }
                      onPaste={e => {
                        for (const item of Array.from(e.clipboardData?.items || [])) {
                          if (item.kind === 'file' && item.type.startsWith('image/')) {
                            const file = item.getAsFile();
                            if (file) {
                              e.preventDefault();
                              onSetOptionImage(i, file);
                              return;
                            }
                          }
                        }
                      }}
                    />
                    <Button
                      type="button"
                      size="icon"
                      variant="ghost"
                      title={`上传选项 ${String.fromCharCode(65 + i)} 配图`}
                      aria-label={`上传选项 ${String.fromCharCode(65 + i)} 配图`}
                      disabled={uploading}
                      onClick={() => {
                        optTargetRef.current = i;
                        optFileRef.current?.click();
                      }}
                    >
                      <ImagePlus className="size-4" />
                    </Button>
                    {opts.length > 2 && (
                      <Button
                        type="button"
                        size="icon"
                        variant="ghost"
                        aria-label="删除此选项"
                        onClick={() => {
                          onChangeField('opts', opts.filter((_, j) => j !== i));
                          onChangeField('optionImages', optionImages.filter((_, j) => j !== i));
                          onChangeField(
                            'correct',
                            correct.filter(x => x !== i).map(x => (x > i ? x - 1 : x)),
                          );
                        }}
                      >
                        <X />
                      </Button>
                    )}
                  </>
                )}
              </UiField>
              {optImg && (
                <div className="ms-11 flex items-center gap-2">
                  <img
                    src={resolveMediaUrl(optImg)}
                    alt={`选项 ${String.fromCharCode(65 + i)} 配图`}
                    className="h-16 rounded border bg-muted/20 object-contain p-0.5"
                  />
                  <Button
                    type="button"
                    size="sm"
                    variant="ghost"
                    className="h-7 px-2 text-xs text-muted-foreground hover:text-destructive"
                    onClick={() => onClearOptionImage(i)}
                  >
                    <X className="size-3.5" />
                    移除配图
                  </Button>
                </div>
              )}
            </div>
          );
        })}
      </FieldGroup>
      {type !== 'boolean' && opts.length < 8 && (
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={() => {
            onChangeField('opts', [...opts, '']);
            onChangeField('optionImages', [...optionImages, '']);
          }}
        >
          <Plus />
          添加选项
        </Button>
      )}
    </Field>
  );
}
