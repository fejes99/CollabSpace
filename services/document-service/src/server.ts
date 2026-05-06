import Fastify from "fastify";
import { app } from "./app";
import { env } from "./config/env";

const server = Fastify({
  logger: {
    level: env.LOG_LEVEL,
    ...(env.NODE_ENV === "development" && {
      transport: { target: "pino-pretty" },
    }),
  },
});

(async () => {
  await server.register(app);
  try {
    await server.listen({ port: env.PORT, host: "0.0.0.0" });
  } catch (err) {
    server.log.error(err);
    process.exit(1);
  }
})().catch((err: unknown) => {
  process.stderr.write(`Fatal startup error: ${String(err)}\n`);
  process.exit(1);
});
