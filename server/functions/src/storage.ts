import { getStorage } from "firebase-admin/storage";

const PREFIX = "ev/";

export const STATIONS_FILE = "stations.json";
export const STATUS_FILE = "status.json";
export const OCCUPANCY_FILE = "occupancy.json";

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
