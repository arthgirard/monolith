export type BoardWindow = "day" | "week" | "month";

function parse(date: string): Date {
  return new Date(`${date}T00:00:00Z`);
}

function format(d: Date): string {
  return d.toISOString().slice(0, 10);
}

export function isIsoDate(v: unknown): v is string {
  if (typeof v !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(v)) return false;
  const d = parse(v);
  return !Number.isNaN(d.getTime()) && format(d) === v;
}

export function addDays(date: string, n: number): string {
  const d = parse(date);
  d.setUTCDate(d.getUTCDate() + n);
  return format(d);
}

export function utcToday(now: number): string {
  return format(new Date(now));
}

export function windowRange(window: BoardWindow, date: string): { from: string; to: string } {
  switch (window) {
    case "day":
      return { from: date, to: date };
    case "week": {
      const mondayOffset = (parse(date).getUTCDay() + 6) % 7;
      const from = addDays(date, -mondayOffset);
      return { from, to: addDays(from, 6) };
    }
    case "month": {
      const from = `${date.slice(0, 8)}01`;
      const end = parse(from);
      end.setUTCMonth(end.getUTCMonth() + 1);
      end.setUTCDate(0);
      return { from, to: format(end) };
    }
  }
}
