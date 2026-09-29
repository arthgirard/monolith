import type { Env } from "./types";

// Placeholder entrypoint so wrangler/vitest can load the worker; routes arrive in later tasks.
export default {
  async fetch(_request: Request, _env: Env): Promise<Response> {
    return new Response("not found", { status: 404 });
  },
};
