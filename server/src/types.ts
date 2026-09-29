export interface Env {
  monolith_leaderboard: D1Database;
}

export interface Share {
  saved: boolean;
  streak: boolean;
  pauses: boolean;
}

export interface MemberRow {
  id: string;
  group_id: string;
  display_name: string;
  share_saved: number;
  share_streak: number;
  share_pauses: number;
  invite_code: string;
}

export function shareOf(m: MemberRow): Share {
  return { saved: m.share_saved === 1, streak: m.share_streak === 1, pauses: m.share_pauses === 1 };
}

export const MAX_MEMBERS = 20;
export const RETENTION_DAYS = 35;
export const INACTIVE_DAYS = 120;
export const DAY_MS = 24 * 60 * 60 * 1000;
