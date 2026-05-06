import fp from 'fastify-plugin'
import sensible, { type FastifySensibleOptions } from '@fastify/sensible'

export const sensiblePlugin = fp<FastifySensibleOptions>(async (fastify) => {
  await fastify.register(sensible)
})
