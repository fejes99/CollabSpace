import type { ALBEvent, ALBResult } from "aws-lambda";

const json = (statusCode: number, body: unknown): ALBResult => ({
  statusCode,
  headers: { "content-type": "application/json" },
  body: JSON.stringify(body),
});

export const handler = (event: ALBEvent): ALBResult => {
  const { httpMethod, path } = event;

  if (httpMethod === "GET" && path === "/notifications/health") {
    return json(200, { status: "ok" });
  }

  return json(404, { error: "not found" });
};
