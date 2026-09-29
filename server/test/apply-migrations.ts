import { applyD1Migrations, env } from "cloudflare:test";

await applyD1Migrations(env.monolith_leaderboard, env.TEST_MIGRATIONS);
