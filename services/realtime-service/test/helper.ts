import Fastify, { type FastifyInstance } from 'fastify'
import type { TestContext } from 'node:test'
import { app } from '../src/app'

export async function buildApp(t: Pick<TestContext, 'after'>): Promise<FastifyInstance> {
  const fastify = Fastify({ logger: false })
  await fastify.register(app)
  await fastify.ready()
  t.after(() => fastify.close())
  return fastify
}
