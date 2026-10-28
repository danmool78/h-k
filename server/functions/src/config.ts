/**
 * 운영 설정과 수집 상태 (Firestore). 클라이언트 접근은 보안 규칙으로 전부 차단하고
 * 관리자 함수(admin.ts)만 읽고 쓴다.
 *
 *   config/dataGoKr   { key, expiresAt, updatedAt, updatedBy }
 *   config/syncState  { stations: {at, ok, message, count}, status: {...} }
 *   usage/{yyyyMMdd}  { calls }   (KST 기준 일별 공공데이터 API 호출 수, 한도 1,000)
 */
import { FieldValue, getFirestore } from "firebase-admin/firestore";

export interface ApiKeyConfig {
  key: string;
  /** 활용기간 만료일 (yyyy-MM-dd) */
  expiresAt: string;
  updatedAt: string;
  updatedBy: string;
}

export interface SyncResult {
  at: string;
  ok: boolean;
  message: string;
  count?: number;
}

const db = () => getFirestore();
const KEY_DOC = "config/dataGoKr";
const SYNC_DOC = "config/syncState";

let keyCache: { at: number; value: ApiKeyConfig | null } | null = null;

export async function getApiKeyConfig(): Promise<ApiKeyConfig | null> {
  if (keyCache && Date.now() - keyCache.at < 60_000) return keyCache.value;
  const snap = await db().doc(KEY_DOC).get();
  const value = snap.exists ? (snap.data() as ApiKeyConfig) : null;
  keyCache = { at: Date.now(), value };
  return value;
}

/** 수집에 쓸 키. 없으면 예외. */
export async function requireApiKey(): Promise<string> {
  const c = await getApiKeyConfig();
  if (!c?.key) throw new Error("공공데이터 API 키가 없습니다. 관리자 페이지에서 등록하세요.");
  return c.key;
}

export async function saveApiKeyConfig(c: ApiKeyConfig) {
  await db().doc(KEY_DOC).set(c);
  keyCache = { at: Date.now(), value: c };
}

export async function recordSync(kind: "stations" | "status", r: SyncResult) {
  await db().doc(SYNC_DOC).set({ [kind]: r }, { merge: true });
}

export async function getSyncState(): Promise<Record<string, SyncResult>> {
  const snap = await db().doc(SYNC_DOC).get();
  return (snap.data() as Record<string, SyncResult>) ?? {};
}

export function kstDate(epochMs = Date.now()): string {
  return new Date(epochMs + 9 * 3600_000).toISOString().slice(0, 10).replace(/-/g, "");
}

export async function addUsage(calls: number) {
  if (calls <= 0) return;
  await db().doc(`usage/${kstDate()}`).set({ calls: FieldValue.increment(calls) }, { merge: true });
}

export async function getUsage(): Promise<number> {
  const snap = await db().doc(`usage/${kstDate()}`).get();
  return (snap.data()?.calls as number) ?? 0;
}

/** 만료까지 남은 일수 (KST 자정 기준). */
export function daysUntil(expiresAt: string, now = Date.now()): number {
  const end = Date.parse(`${expiresAt}T23:59:59+09:00`);
  return Math.floor((end - now) / 86_400_000);
}

export function maskKey(key: string): string {
  return key.length <= 10 ? "****" : `${key.slice(0, 6)}…${key.slice(-4)}`;
}
