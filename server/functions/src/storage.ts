import { getStorage } from "firebase-admin/storage";

const PREFIX = "ev/";

export const STATIONS_FILE = "stations.json";
export const STATUS_FILE = "status.json";
export const OCCUPANCY_FILE = "occupancy.json";
export const VALIDATION_FILE = "validation.json";
export const LAG_FILE = "lag.json";

/** 프로젝트 기본 Storage 버킷의 ev/ 아래에 저장. */
const file = (name: string) => getStorage().bucket().file(PREFIX + name);

export async function readJson<T>(name: string): Promise<T | null> {
  const f = file(name);
  const [exists] = await f.exists();
  if (!exists) return null;
  const [buf] = await f.download();
  return JSON.parse(buf.toString("utf8")) as T;
}

export async function writeJson(name: string, data: unknown) {
  await file(name).save(JSON.stringify(data), { contentType: "application/json", resumable: false });
}

export async function writeBuffer(name: string, data: Buffer) {
  await file(name).save(data, { contentType: "application/octet-stream", resumable: false });
}

export async function readBuffer(name: string): Promise<Buffer | null> {
  const f = file(name);
  const [exists] = await f.exists();
  if (!exists) return null;
  const [buf] = await f.download();
  return buf;
}

/** [prefix] 아래 파일 이름 (PREFIX 제외) */
export async function listNames(prefix: string): Promise<string[]> {
  const [files] = await getStorage().bucket().getFiles({ prefix: PREFIX + prefix });
  return files.map((f) => f.name.slice(PREFIX.length));
}

export async function deletePrefix(prefix: string): Promise<number> {
  const [files] = await getStorage().bucket().getFiles({ prefix: PREFIX + prefix });
  await Promise.all(files.map((f) => f.delete({ ignoreNotFound: true })));
  return files.length;
}
