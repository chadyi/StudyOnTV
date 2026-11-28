import { LoaderCircle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel } from '@/components/ui/field';
import { Input } from '@/components/ui/input';

type Props = {
  open: boolean;
  hostInput: string;
  error: string;
  pending: boolean;
  onOpenChange: (open: boolean) => void;
  onHostChange: (value: string) => void;
  onSubmit: () => void;
};

export default function AddPeerDialog({
  open,
  hostInput,
  error,
  pending,
  onOpenChange,
  onHostChange,
  onSubmit,
}: Props) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>手动添加设备</DialogTitle>
          <DialogDescription>
            支持局域网、Tailscale/VPN 任意网段 IP 或域名（如 192.168.1.20:8099、100.64.x.x、https://tv.example.com），探测在线后自动收藏。
          </DialogDescription>
        </DialogHeader>
        <form
          onSubmit={e => {
            e.preventDefault();
            onSubmit();
          }}
          className="space-y-4"
        >
          <FieldGroup>
            <Field>
              <FieldLabel htmlFor="manual-peer-host">设备地址（IP 或域名）</FieldLabel>
              <Input
                id="manual-peer-host"
                autoFocus
                autoComplete="off"
                autoCapitalize="none"
                spellCheck={false}
                placeholder="192.168.1.20:8099 或 https://tv.example.com"
                value={hostInput}
                onChange={e => onHostChange(e.target.value)}
              />
              <FieldDescription>未填端口时 HTTP 默认 :8099，HTTPS 默认 :443。</FieldDescription>
              <FieldError>{error}</FieldError>
            </Field>
          </FieldGroup>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
              取消
            </Button>
            <Button type="submit" disabled={pending || !hostInput.trim()}>
              {pending ? (
                <>
                  <LoaderCircle className="size-4 animate-spin" />
                  探测中…
                </>
              ) : (
                '探测并收藏'
              )}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
