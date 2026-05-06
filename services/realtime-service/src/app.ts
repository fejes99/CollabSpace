import { FastifyPluginAsync } from "fastify";
import { sensiblePlugin } from "./plugins/sensible";
import { healthRoutes } from "./routes/health";

export const app: FastifyPluginAsync = async (fastify, _opts) => {
  await fastify.register(sensiblePlugin);
  await fastify.register(healthRoutes);
};
