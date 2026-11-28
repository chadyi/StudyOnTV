import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  AlertCircle,
  LoaderCircle,
  MonitorSmartphone,
  Plus,
  Radar,
  RefreshCw,
} from 'lucide-react';
import { toast } from 'sonner';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { SectionHeading } from '@/components/ui/section-heading';
import { api, getHost } from './api';
import AddPeerDialog from './components/devices/AddPeerDialog';
import BatchSyncSection from './components/devices/BatchSyncSection';
import CurrentHostCard from './components/devices/CurrentHostCard';
import PeerDeviceCard from './components/devices/PeerDeviceCard';
import {
  extractProbedPeer,
  isValidPeerHostInput,
  loadSavedPeers,
  mergePeers,
  normalizePeerHost,
  peerAdminUrl,
  removeSavedPeer,
  saveSavedPeers,
  upsertSavedPeer,
  type MergedPeer,
  type PeerInfo,
  type PeerSelf,
  type SavedPeer,
  type SyncMode,
  type SyncResponse,
  type SyncScope,
  type SyncTargetResult,
} from './peers';

interface DevicesPageProps {
  sessionId: number;
  device?: any;
  questionCount?: number;
  onSwitchHost: (host: string, name?: string) => void;
}

export default function DevicesPage({ sessionId, device, questionCount, onSwitchHost }: DevicesPageProps) {
  const queryClient = useQueryClient();
  const [savedPeers, setSavedPeers] = useState<SavedPeer[]>(() => loadSavedPeers());
  const [probedMap, setProbedMap] = useState<Record<string, PeerInfo>>({});
  const [selectedHosts, setSelectedHosts] = useState<string[]>([]);
  const [scopes, setScopes] = useState<SyncScope[]>(['config', 'questions', 'speech', 'grading']);
  const [mode, setMode] = useState<SyncMode>('merge');
  const [defaultPassword, setDefaultPassword] = useState('');
  const [perHostPasswords, setPerHostPasswords] = useState<Record<string, string>>({});
  const [showPerHostPassword, setShowPerHostPassword] = useState<Record<string, boolean>>({});
  const [expandedPasswordHosts, setExpandedPasswordHosts] = useState<Record<string, boolean>>({});
  const [syncResults, setSyncResults] = useState<SyncTargetResult[] | null>(null);

  const [addOpen, setAddOpen] = useState(false);
  const [addHostInput, setAddHostInput] = useState('');
  const [addError, setAddError] = useState('');
  const [probingHost, setProbingHost] = useState<string | null>(null);

  const peersQuery = useQuery<{ self?: PeerSelf; peers?: PeerInfo[] }>({
    queryKey: ['peers', sessionId],
    queryFn: () => api('/api/peers?scan=1'),
    staleTime: 10000,
  });

  const selfInfo = useMemo(() => {
    const apiSelf = peersQuery.data?.self;
    const host =
      apiSelf?.host ||
      (apiSelf?.ip && apiSelf?.port ? `${apiSelf.ip}:${apiSelf.port}` : '') ||
      (device?.ip && device?.port ? `${device.ip}:${device.port}` : '') ||
      getHost();
    return {
      name: apiSelf?.name || device?.name || '当前设备',
      host,
      version: apiSelf?.version || device?.version || '',
      isTv: typeof apiSelf?.isTv === 'boolean' ? apiSelf.isTv : device?.flavor === 'tv',
      questions: typeof apiSelf?.questions === 'number' ? apiSelf.questions : questionCount,
    };
  }, [peersQuery.data?.self, device, questionCount]);

  const peers: MergedPeer[] = useMemo(() => {
    const selfHosts = [
      selfInfo.host,
      getHost(),
      peersQuery.data?.self?.ip && peersQuery.data?.self?.port
        ? `${peersQuery.data.self.ip}:${peersQuery.data.self.port}`
        : '',
    ].filter(Boolean);
    return mergePeers(peersQuery.data?.peers || [], savedPeers, selfHosts, probedMap);
  }, [peersQuery.data?.peers, peersQuery.data?.self, savedPeers, selfInfo.host, probedMap]);

  const persistSaved = (next: SavedPeer[]) => {
    setSavedPeers(next);
    saveSavedPeers(next);
  };

  const toggleSavePeer = (peer: MergedPeer) => {
    if (peer.saved) {
      persistSaved(removeSavedPeer(savedPeers, peer.host));
      toast.success(`已取消收藏 ${peer.name}`);
    } else {
      persistSaved(
        upsertSavedPeer(savedPeers, {
          host: peer.host,
          name: peer.name,
          version: peer.version,
          isTv: peer.isTv,
          questions: peer.questions,
        }),
      );
      toast.success(`已收藏 ${peer.name}`);
    }
  };

  const handleRemoveSaved = (peer: MergedPeer) => {
    persistSaved(removeSavedPeer(savedPeers, peer.host));
    setSelectedHosts(prev => prev.filter(h => h !== peer.host));
    toast.success(`已移除 ${peer.name}`);
  };

  const rescanMutation = useMutation({
    mutationFn: () => api('/api/peers?scan=1'),
    onSuccess: data => {
      queryClient.setQueryData(['peers', sessionId], data);
      const count = Array.isArray(data?.peers) ? data.peers.length : 0;
      toast.success(count > 0 ? `扫描完成，发现 ${count} 台局域网设备` : '扫描完成，暂未发现其他在线设备');
    },
    onError: (err: any) => {
      toast.error(err?.message || '扫描局域网设备失败');
    },
  });

  const probeAddMutation = useMutation({
    mutationFn: async (rawHost: string) => {
      const normalized = normalizePeerHost(rawHost);
      if (!isValidPeerHostInput(normalized)) {
        throw new Error('请输入有效的设备地址，如 192.168.1.20:8099、100.64.x.x 或 https://tv.example.com');
      }
      if (normalized.toLowerCase() === normalizePeerHost(selfInfo.host).toLowerCase()) {
        throw new Error('这是当前正在管理的设备，无需重复添加');
      }
      const res = await api('/api/peers/probe', 'POST', { host: normalized });
      if (res?.ok === false || res?.online === false || res?.peer?.online === false) {
        throw new Error(res?.message || '目标设备离线或无法连接，请确认已打开「题了个题」且网络可达');
      }
      return { res, normalized };
    },
    onSuccess: ({ res, normalized }) => {
      const probed = extractProbedPeer(res, normalized);
      setProbedMap(prev => ({ ...prev, [probed.host.toLowerCase()]: probed }));
      persistSaved(
        upsertSavedPeer(savedPeers, {
          host: probed.host,
          name: probed.name,
          version: probed.version,
          isTv: probed.isTv,
          questions: probed.questions,
        }),
      );
      setAddOpen(false);
      setAddHostInput('');
      setAddError('');
      toast.success(`已连接并收藏「${probed.name}」`);
    },
    onError: (err: any) => {
      setAddError(err?.message || '无法探测该设备，请确认地址正确且处于同一局域网');
    },
  });

  const handleReprobePeer = async (peer: MergedPeer) => {
    if (probingHost) return;
    setProbingHost(peer.host);
    try {
      const res = await api('/api/peers/probe', 'POST', { host: peer.host });
      if (res?.ok === false || res?.online === false || res?.peer?.online === false) {
        throw new Error(res?.message || `未能连接到 ${peer.host}`);
      }
      const probed = extractProbedPeer(res, peer.host);
      setProbedMap(prev => ({ ...prev, [probed.host.toLowerCase()]: probed }));
      if (peer.saved) {
        persistSaved(
          upsertSavedPeer(savedPeers, {
            host: probed.host,
            name: probed.name,
            version: probed.version,
            isTv: probed.isTv,
            questions: probed.questions,
          }),
        );
      }
      toast.success(`「${probed.name}」在线`);
    } catch (err: any) {
      toast.error(err?.message || `未能连接到 ${peer.host}`);
    } finally {
      setProbingHost(null);
    }
  };

  const validSelectedHosts = useMemo(
    () => selectedHosts.filter(h => peers.some(p => p.host === h)),
    [selectedHosts, peers],
  );
  const allSelected = peers.length > 0 && validSelectedHosts.length === peers.length;

  const toggleSelectHost = (host: string) => {
    setSelectedHosts(prev => (prev.includes(host) ? prev.filter(item => item !== host) : [...prev, host]));
  };
  const toggleSelectAll = () => {
    setSelectedHosts(allSelected ? [] : peers.map(p => p.host));
  };
  const toggleScope = (scope: SyncScope) => {
    setScopes(prev => (prev.includes(scope) ? prev.filter(s => s !== scope) : [...prev, scope]));
  };

  const syncMutation = useMutation({
    mutationFn: async () => {
      if (!validSelectedHosts.length) throw new Error('请先勾选至少 1 台目标设备');
      if (!scopes.length) throw new Error('请先勾选至少 1 项同步内容');
      const fallbackPwd = defaultPassword.trim();
      const missingPwdHosts = validSelectedHosts.filter(
        host => !(perHostPasswords[host] || '').trim() && !fallbackPwd,
      );
      if (missingPwdHosts.length > 0) {
        throw new Error('请输入目标设备的管理密码（多台相同时填写统一管理密码即可，不同时可为单台点「单独密码」）');
      }
      const targets = validSelectedHosts.map(host => ({
        host,
        password: (perHostPasswords[host] || '').trim() || fallbackPwd,
      }));
      const payload: Record<string, any> = { targets, scopes, mode };
      if (fallbackPwd) payload.defaultPassword = fallbackPwd;
      return (await api('/api/peers/sync', 'POST', payload)) as SyncResponse;
    },
    onSuccess: data => {
      const results = Array.isArray(data?.results) ? data.results : [];
      setSyncResults(results);
      const okCount = results.filter(r => r.ok).length;
      const failCount = results.length - okCount;
      if (results.length === 0) toast.success(data?.message || '同步请求已完成');
      else if (failCount === 0) toast.success(`已成功同步到全部 ${okCount} 台设备`);
      else if (okCount === 0) toast.error(`同步失败（${failCount} 台设备未完成），请查看下方明细`);
      else toast.warning(`部分完成：${okCount} 台成功，${failCount} 台失败`);
    },
    onError: (err: any) => toast.error(err?.message || '批量同步失败'),
  });

  const handleOpenInNewTab = (peer: MergedPeer) => {
    window.open(peerAdminUrl(peer.host), '_blank', 'noopener,noreferrer');
  };

  return (
    <div className="space-y-6 pb-8">
      <CurrentHostCard
        selfInfo={selfInfo}
        scanning={rescanMutation.isPending || peersQuery.isFetching}
        onRescan={() => rescanMutation.mutate()}
        onOpenAdd={() => {
          setAddError('');
          setAddHostInput('');
          setAddOpen(true);
        }}
      />

      <section className="space-y-2.5">
        <div className="flex flex-wrap items-end justify-between gap-2">
          <SectionHeading
            icon={Radar}
            title="局域网设备与收藏列表"
            description="自动发现同一 WiFi 下的 StudyOnTV 设备，并合并本浏览器收藏的设备。"
          />
          {peers.length > 0 && (
            <Button variant="ghost" size="sm" className="h-8 text-xs" onClick={toggleSelectAll}>
              {allSelected ? '取消全选' : `全选 (${peers.length} 台)`}
            </Button>
          )}
        </div>

        {peersQuery.isError && (
          <Alert variant="destructive">
            <AlertCircle className="size-4" />
            <AlertDescription>
              自动扫描失败：{(peersQuery.error as any)?.message || '请确认设备已升级到最新版本并处于同一局域网'}。
              仍可通过「手动添加设备」输入 IP:端口管理。
            </AlertDescription>
          </Alert>
        )}

        {peersQuery.isLoading && peers.length === 0 ? (
          <Card className="shadow-xs">
            <CardContent className="flex items-center justify-center gap-2 py-12 text-sm text-muted-foreground">
              <LoaderCircle className="size-4 animate-spin" />
              正在扫描局域网中的 StudyOnTV 设备…
            </CardContent>
          </Card>
        ) : peers.length === 0 ? (
          <Card className="border-dashed shadow-xs">
            <CardContent className="flex flex-col items-center gap-3 py-10 text-center">
              <MonitorSmartphone className="size-9 text-muted-foreground/70" />
              <div className="space-y-1">
                <p className="text-sm font-medium">暂未发现其他 StudyOnTV 设备</p>
                <p className="max-w-md text-xs text-muted-foreground">
                  请确认另一台电视或平板已打开「题了个题」且连接在同一家庭 WiFi 下；如果路由器隔绝了局域网广播，可点击「手动添加设备」直接输入对方 IP:端口。
                </p>
              </div>
              <div className="flex flex-wrap justify-center gap-2 pt-1">
                <Button variant="outline" size="sm" onClick={() => rescanMutation.mutate()} disabled={rescanMutation.isPending}>
                  <RefreshCw className="size-4" />
                  重新扫描
                </Button>
                <Button size="sm" onClick={() => setAddOpen(true)}>
                  <Plus className="size-4" />
                  手动添加设备
                </Button>
              </div>
            </CardContent>
          </Card>
        ) : (
          <div className="grid gap-3 md:grid-cols-2">
            {peers.map(peer => (
              <PeerDeviceCard
                key={peer.host}
                peer={peer}
                checked={validSelectedHosts.includes(peer.host)}
                customPwdExpanded={!!expandedPasswordHosts[peer.host] || !!perHostPasswords[peer.host]}
                password={perHostPasswords[peer.host] || ''}
                pwdVisible={!!showPerHostPassword[peer.host]}
                probing={probingHost === peer.host}
                onToggleSelect={toggleSelectHost}
                onPasswordChange={(host, value) => setPerHostPasswords(prev => ({ ...prev, [host]: value }))}
                onTogglePwdVisible={host => setShowPerHostPassword(prev => ({ ...prev, [host]: !prev[host] }))}
                onExpandCustomPwd={host => setExpandedPasswordHosts(prev => ({ ...prev, [host]: true }))}
                onSwitchHost={onSwitchHost}
                onOpenInNewTab={handleOpenInNewTab}
                onReprobe={handleReprobePeer}
                onToggleSave={toggleSavePeer}
                onRemoveSaved={handleRemoveSaved}
              />
            ))}
          </div>
        )}
      </section>

      <BatchSyncSection
        scopes={scopes}
        mode={mode}
        defaultPassword={defaultPassword}
        selectedCount={validSelectedHosts.length}
        pending={syncMutation.isPending}
        results={syncResults}
        onToggleScope={toggleScope}
        onModeChange={setMode}
        onDefaultPasswordChange={setDefaultPassword}
        onSync={() => syncMutation.mutate()}
        onFillHostPassword={host => {
          if (!selectedHosts.includes(host)) setSelectedHosts(prev => [...prev, host]);
          setExpandedPasswordHosts(prev => ({ ...prev, [host]: true }));
        }}
      />

      <AddPeerDialog
        open={addOpen}
        hostInput={addHostInput}
        error={addError}
        pending={probeAddMutation.isPending}
        onOpenChange={setAddOpen}
        onHostChange={value => {
          setAddHostInput(value);
          if (addError) setAddError('');
        }}
        onSubmit={() => {
          setAddError('');
          probeAddMutation.mutate(addHostInput);
        }}
      />
    </div>
  );
}
