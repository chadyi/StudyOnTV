export const SAVED_PEERS_STORAGE_KEY = 'sotv-saved-peers';

export type SyncScope = 'config' | 'questions' | 'speech' | 'grading';
export type SyncMode = 'merge' | 'replace';

export interface PeerSelf {
  id?: string;
  name?: string;
  ip?: string;
  port?: number;
  host?: string;
  version?: string;
  isTv?: boolean;
  questions?: number;
}

export interface PeerInfo {
  id?: string;
  name?: string;
  ip?: string;
  port?: number;
  host: string;
  version?: string;
  isTv?: boolean;
  questions?: number;
  lastSeenAt?: number;
  online?: boolean;
}

export interface SavedPeer {
  host: string;
  name?: string;
  version?: string;
  isTv?: boolean;
  questions?: number;
  addedAt: number;
}

export interface MergedPeer {
  id?: string;
  host: string;
  name: string;
  version?: string;
  isTv?: boolean;
  questions?: number;
  lastSeenAt?: number;
  online: boolean;
  discovered: boolean;
  saved: boolean;
}

export interface SyncTargetResult {
  host: string;
  name?: string;
  ok: boolean;
  message?: string;
}

export interface SyncResponse {
  ok: boolean;
  results?: SyncTargetResult[];
  message?: string;
}

export function normalizePeerHost(raw: string): string {
  const input = String(raw || '').trim();
  if (!input) return '';
  const isHttps = /^https:\/\//i.test(input);
  const cleaned = input.replace(/^https?:\/\//i, '').replace(/\/.*$/, '');
  if (!cleaned) return '';
  if (isHttps) {
    const noDefaultPort = cleaned.replace(/:443$/, '');
    return `https://${noDefaultPort}`;
  }
  if (cleaned.startsWith('[')) {
    return /\]:\d+$/.test(cleaned) ? cleaned : `${cleaned}:8099`;
  }
  if (!cleaned.includes(':')) {
    return `${cleaned}:8099`;
  }
  return cleaned;
}

export function peerAdminUrl(host: string): string {
  const normalized = normalizePeerHost(host);
  if (!normalized) return '/admin/';
  return /^https?:\/\//i.test(normalized) ? `${normalized}/admin/` : `http://${normalized}/admin/`;
}

export function isValidPeerHostInput(raw: string): boolean {
  const normalized = normalizePeerHost(raw);
  if (!normalized || /\s/.test(normalized) || /[@?#]/.test(normalized)) return false;
  const isHttps = /^https:\/\//i.test(normalized);
  const authority = normalized.replace(/^https?:\/\//i, '');
  if (!authority) return false;
  let hostPart = '';
  let portPart = '';
  if (authority.startsWith('[')) {
    const close = authority.indexOf(']');
    if (close <= 1) return false;
    hostPart = authority.slice(0, close + 1).toLowerCase();
    const rest = authority.slice(close + 1);
    if (rest && !rest.startsWith(':')) return false;
    portPart = rest ? rest.slice(1) : '';
    const inner = hostPart.slice(1, -1);
    if (!inner.includes(':') || !/^[0-9a-f:.]+$/.test(inner) || inner === '::' || inner === '::1') return false;
  } else {
    const parts = authority.split(':');
    if (parts.length > 2) return false;
    hostPart = parts[0].toLowerCase();
    portPart = parts[1] ?? '';
    if (!hostPart || hostPart === 'localhost' || hostPart.endsWith('.localhost')) return false;
    if (/^[0-9.]+$/.test(hostPart)) {
      const octets = hostPart.split('.');
      if (octets.length !== 4) return false;
      const nums = octets.map(p => (/^(0|[1-9]\d{0,2})$/.test(p) ? Number(p) : -1));
      if (nums.some(n => n < 0 || n > 255)) return false;
      const [a, b] = nums;
      if (a === 0 || a === 127 || (a === 169 && b === 254) || a >= 224) return false;
    } else {
      const labels = hostPart.split('.');
      if (labels.some(l => !l || l.length > 63 || !/^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/.test(l))) return false;
    }
  }
  if (!portPart) return isHttps;
  if (!/^[1-9]\d{0,4}$/.test(portPart)) return false;
  const port = Number(portPart);
  return port >= 1 && port <= 65535;
}

export function loadSavedPeers(): SavedPeer[] {
  try {
    if (typeof localStorage === 'undefined') return [];
    const raw = JSON.parse(localStorage.getItem(SAVED_PEERS_STORAGE_KEY) || '[]');
    if (!Array.isArray(raw)) return [];
    const seen = new Set<string>();
    const list: SavedPeer[] = [];
    for (const item of raw) {
      const host = normalizePeerHost(item?.host || '');
      if (!host) continue;
      const key = host.toLowerCase();
      if (seen.has(key)) continue;
      seen.add(key);
      list.push({
        host,
        name: typeof item?.name === 'string' && item.name.trim() ? item.name.trim() : undefined,
        version: typeof item?.version === 'string' ? item.version : undefined,
        isTv: typeof item?.isTv === 'boolean' ? item.isTv : undefined,
        questions: typeof item?.questions === 'number' ? item.questions : undefined,
        addedAt: typeof item?.addedAt === 'number' ? item.addedAt : Date.now(),
      });
    }
    return list;
  } catch {
    return [];
  }
}

export function saveSavedPeers(peers: SavedPeer[]) {
  try {
    if (typeof localStorage === 'undefined') return;
    localStorage.setItem(SAVED_PEERS_STORAGE_KEY, JSON.stringify(peers));
  } catch {}
}

export function upsertSavedPeer(list: SavedPeer[], peer: Omit<SavedPeer, 'addedAt'>): SavedPeer[] {
  const host = normalizePeerHost(peer.host);
  if (!host) return list;
  const key = host.toLowerCase();
  const existing = list.find(item => item.host.toLowerCase() === key);
  const nextItem: SavedPeer = {
    host,
    name: peer.name || existing?.name,
    version: peer.version ?? existing?.version,
    isTv: peer.isTv ?? existing?.isTv,
    questions: peer.questions ?? existing?.questions,
    addedAt: existing?.addedAt || Date.now(),
  };
  const rest = list.filter(item => item.host.toLowerCase() !== key);
  return [nextItem, ...rest];
}

export function removeSavedPeer(list: SavedPeer[], host: string): SavedPeer[] {
  const key = normalizePeerHost(host).toLowerCase();
  return list.filter(item => item.host.toLowerCase() !== key);
}

export function mergePeers(
  discovered: PeerInfo[],
  saved: SavedPeer[],
  selfHosts: string[] = [],
  probedOnlineHosts: Record<string, PeerInfo> = {},
): MergedPeer[] {
  const selfSet = new Set(
    selfHosts.map(h => normalizePeerHost(h).toLowerCase()).filter(Boolean),
  );
  const map = new Map<string, MergedPeer>();

  for (const item of discovered || []) {
    const host = normalizePeerHost(item.host || (item.ip && item.port ? `${item.ip}:${item.port}` : ''));
    if (!host) continue;
    const key = host.toLowerCase();
    if (selfSet.has(key)) continue;
    map.set(key, {
      id: item.id,
      host,
      name: (item.name || '').trim() || host,
      version: item.version,
      isTv: item.isTv,
      questions: typeof item.questions === 'number' ? item.questions : undefined,
      lastSeenAt: item.lastSeenAt,
      online: item.online !== false,
      discovered: true,
      saved: false,
    });
  }

  for (const item of saved || []) {
    const host = normalizePeerHost(item.host);
    if (!host) continue;
    const key = host.toLowerCase();
    if (selfSet.has(key)) continue;
    const existing = map.get(key);
    const probed = probedOnlineHosts[key];
    if (existing) {
      existing.saved = true;
      if (!existing.name || existing.name === existing.host) {
        existing.name = item.name || existing.host;
      }
    } else {
      map.set(key, {
        id: probed?.id,
        host,
        name: (probed?.name || item.name || '').trim() || host,
        version: probed?.version ?? item.version,
        isTv: probed?.isTv ?? item.isTv,
        questions: typeof probed?.questions === 'number' ? probed.questions : item.questions,
        lastSeenAt: probed?.lastSeenAt,
        online: probed ? probed.online !== false : false,
        discovered: false,
        saved: true,
      });
    }
  }

  return Array.from(map.values()).sort((a, b) => {
    if (a.online !== b.online) return a.online ? -1 : 1;
    if (a.discovered !== b.discovered) return a.discovered ? -1 : 1;
    return a.name.localeCompare(b.name, 'zh-CN');
  });
}

export function extractProbedPeer(result: any, fallbackHost: string): PeerInfo {
  const src = result?.peer && typeof result.peer === 'object' ? result.peer : result || {};
  const host = normalizePeerHost(src.host || fallbackHost);
  return {
    id: typeof src.id === 'string' ? src.id : undefined,
    host,
    name: typeof src.name === 'string' && src.name.trim() ? src.name.trim() : host,
    version: typeof src.version === 'string' ? src.version : undefined,
    isTv: typeof src.isTv === 'boolean' ? src.isTv : src.flavor === 'tv' ? true : src.flavor === 'mobile' ? false : undefined,
    questions: typeof src.questions === 'number' ? src.questions : undefined,
    online: src.online !== false,
    lastSeenAt: Date.now(),
  };
}
