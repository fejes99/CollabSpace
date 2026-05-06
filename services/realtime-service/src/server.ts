import Fastify from "fastify";
import { env } from "./config/env";
import { app } from "./app";

const server = Fastify({
  logger: {
    level: env.LOG_LEVEL,
    ...(env.NODE_ENV === "development" && {
      transport: { target: "pino-pretty" },
    }),
  },
});

void server.register(app);

const start = async (): Promise<void> => {
  try {
    await server.listen({ port: env.PORT, host: "0.0.0.0" });
  } catch (err) {
    server.log.error(err);
    process.exit(1);
  }
};

void start();
