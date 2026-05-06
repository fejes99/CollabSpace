import { type FastifyPluginAsync } from "fastify";

const responseSchema = {
  200: {
    type: "object",
    properties: {
      status: { type: "string" },
    },
    required: ["status"],
    additionalProperties: false,
  },
};

export const healthRoutes: FastifyPluginAsync = async (fastify) => {
  fastify.get("/health", { schema: { response: responseSchema } }, (_request, _reply) => {
    return { status: "ok" };
  });
};
