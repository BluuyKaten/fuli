import type { Environment } from 'vitest/environments'

// Compile Vue templates for the client while host nodes come from Vue's custom renderer.
export default {
  name: 'vue-renderer',
  viteEnvironment: 'client',
  setup: () => ({ teardown() {} })
} satisfies Environment
