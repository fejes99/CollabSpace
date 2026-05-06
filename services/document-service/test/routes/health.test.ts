import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildApp } from '../helper'

test('GET /health returns 200 with status ok', async (t) => {
  const app = await buildApp(t)

  const res = await app.inject({ method: 'GET', url: '/health' })

  assert.equal(res.statusCode, 200)
  assert.deepEqual(JSON.parse(res.payload), { status: 'ok' })
})
