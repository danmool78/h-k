/**
 * 관리자 페이지용 callable 함수. Google 로그인 + 이메일 허용 목록으로 보호.
 */
import { logger } from "firebase-functions";
import { defineString } from "firebase-functions/params";
import { CallableOptions, CallableRequest, HttpsError, onCall } from "firebase-functions/v2/https";
import {
  addUsage, daysUntil, getApiKeyConfig, getSyncState, getUsage, maskKey, saveApiKeyConfig,
} from "./config";
import { takeCallCount, verifyKey } from "./evApi";
import { VALIDATION_FILE, readJson } from "./storage";
import { syncStationsNow, syncStatusNow } from "./sync";
import { ValidationMap, summarize } from "./validation";

/** 쉼표로 구분한 관리자 이메일. functions/.env (git 제외) 에 설정. */
const ADMIN_EMAILS = defineString("ADMIN_EMAILS");

function requireAdmin(req: CallableRequest): string {
  const email = req.auth?.token.email;
  const verified = req.auth?.token.email_verified;
  const allowed = ADMIN_EMAILS.value().split(",").map((s) => s.trim().toLowerCase());
  if (!email || !verified || !allowed.includes(email.toLowerCase())) {
    throw new HttpsError("permission-denied", "관리자만 접근할 수 있습니다.");
  }
  return email;
}

/** 예상 못 한 오류도 화면에 이유가 보이도록 HttpsError 로 감싼다. */
function adminCall<T>(opts: CallableOptions, handler: (req: CallableRequest) => Promise<T>) {
  return onCall(opts, async (req: CallableRequest) => {
    try {
      return await handler(req);
    } catch (e) {
      if (e instanceof HttpsError) throw e;
      logger.error(e);
      throw new HttpsError("internal", `서버 오류: ${e instanceof Error ? e.message : String(e)}`.slice(0, 300));
    }
  });
}

export const adminGetStatus = adminCall({}, async (req) => {
  requireAdmin(req);
  const [key, sync, usageToday, val] = await Promise.all([
    getApiKeyConfig(), getSyncState(), getUsage(), readJson<ValidationMap>(VALIDATION_FILE),
  ]);
  return {
    validation: summarize(val ?? {}),
    key: key && {
      masked: maskKey(key.key),
      expiresAt: key.expiresAt,
      daysLeft: daysUntil(key.expiresAt),
      updatedAt: key.updatedAt,
      updatedBy: key.updatedBy,
    },
    sync,
    usageToday,
    dailyLimit: 1000,
  };
});

export const adminSetKey = adminCall({}, async (req) => {
  const email = requireAdmin(req);
  const key = String(req.data?.key ?? "").trim();
  const expiresAt = String(req.data?.expiresAt ?? "").trim();
  if (!/^[A-Za-z0-9%+/=]{20,}$/.test(key)) throw new HttpsError("invalid-argument", "키 형식이 올바르지 않습니다.");
  if (!/^\d{4}-\d{2}-\d{2}$/.test(expiresAt)) throw new HttpsError("invalid-argument", "만료일은 yyyy-MM-dd 형식입니다.");

  takeCallCount();
  try {
    await verifyKey(key);
  } catch (e) {
    throw new HttpsError("failed-precondition", `키 확인 실패: ${e instanceof Error ? e.message : e}`);
  } finally {
    await addUsage(takeCallCount());
  }
  await saveApiKeyConfig({ key, expiresAt, updatedAt: new Date().toISOString(), updatedBy: email });
  return { masked: maskKey(key), expiresAt, daysLeft: daysUntil(expiresAt) };
});

export const adminRunSync = adminCall({ timeoutSeconds: 540, memory: "2GiB" }, async (req) => {
  requireAdmin(req);
  const which = req.data?.which;
  if (which === "stations") return syncStationsNow();
  if (which === "status") return syncStatusNow();
  throw new HttpsError("invalid-argument", "which 는 stations 또는 status");
});
