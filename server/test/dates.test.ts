import { describe, expect, it } from "vitest";
import { addDays, isIsoDate, utcToday, windowRange } from "../src/dates";

describe("dates", () => {
  it("accepts only real YYYY-MM-DD dates", () => {
    expect(isIsoDate("2026-02-28")).toBe(true);
    expect(isIsoDate("2026-02-30")).toBe(false);
    expect(isIsoDate("2026-2-3")).toBe(false);
    expect(isIsoDate(20260228)).toBe(false);
  });

  it("adds days across month and year ends", () => {
    expect(addDays("2026-12-31", 1)).toBe("2027-01-01");
    expect(addDays("2026-03-01", -1)).toBe("2026-02-28");
  });

  it("utcToday formats epoch millis", () => {
    expect(utcToday(Date.UTC(2026, 8, 28, 23, 59))).toBe("2026-09-28");
  });

  it("day window is the date itself", () => {
    expect(windowRange("day", "2026-09-28")).toEqual({ from: "2026-09-28", to: "2026-09-28" });
  });

  it("week window runs Monday to Sunday", () => {
    // 2026-09-28 is a Monday, 2026-10-04 a Sunday.
    expect(windowRange("week", "2026-09-28")).toEqual({ from: "2026-09-28", to: "2026-10-04" });
    expect(windowRange("week", "2026-10-04")).toEqual({ from: "2026-09-28", to: "2026-10-04" });
  });

  it("month window is the calendar month", () => {
    expect(windowRange("month", "2026-02-14")).toEqual({ from: "2026-02-01", to: "2026-02-28" });
    expect(windowRange("month", "2026-12-31")).toEqual({ from: "2026-12-01", to: "2026-12-31" });
  });
});
