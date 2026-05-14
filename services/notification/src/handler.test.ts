import { describe, expect, it } from "vitest";
import type { ALBEvent } from "aws-lambda";
import { handler } from "./handler.js";

const albEvent = (method: string, path: string): ALBEvent =>
  ({
    httpMethod: method,
    path,
    headers: {},
    queryStringParameters: {},
    body: null,
    isBase64Encoded: false,
    requestContext: { elb: { targetGroupArn: "" } },
  });

describe("handler", () => {
  it("returns 200 for GET /notifications/health", () => {
    const result = handler(albEvent("GET", "/notifications/health"));

    expect(result.statusCode).toBe(200);
    expect(result.body).toBe(JSON.stringify({ status: "ok" }));
  });

  it("returns 404 for unknown routes", () => {
    const result = handler(albEvent("GET", "/notifications/unknown"));

    expect(result.statusCode).toBe(404);
  });
});
