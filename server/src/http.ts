export class HttpError extends Error {
  constructor(readonly status: number, readonly code: string) {
    super(code);
  }
}

export const invalidBody = () => new HttpError(400, "invalid_body");

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

export function empty(): Response {
  return new Response(null, { status: 204 });
}

export async function readJson(req: Request): Promise<Record<string, unknown>> {
  let body: unknown;
  try {
    body = await req.json();
  } catch {
    throw invalidBody();
  }
  if (typeof body !== "object" || body === null || Array.isArray(body)) throw invalidBody();
  return body as Record<string, unknown>;
}
