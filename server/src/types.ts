export interface Env {
  monolith_leaderboard: D1Database;
}

export interface Share {
  saved: boolean;
  streak: boolean;
  pauses: boolean;
}

export interface UserRow {
  id: string;
  display_name: string;
}

export interface ShareColumns {
  share_saved: number;
  share_streak: number;
  share_pauses: number;
}

export function shareOf(c: ShareColumns): Share {
  return { saved: c.share_saved === 1, streak: c.share_streak === 1, pauses: c.share_pauses === 1 };
}

export const MAX_GROUPS = 10;

export const MAX_MEMBERS = 20;
export const RETENTION_DAYS = 35;
export const INACTIVE_DAYS = 120;
export const DAY_MS = 24 * 60 * 60 * 1000;
