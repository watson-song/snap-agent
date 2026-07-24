import { defineConfig } from 'vitest/config';
import { readFileSync } from 'fs';
import { resolve } from 'path';

const STATIC_DIR = resolve(__dirname, '../../../main/resources/static/snap-agent');

// Helper: read a source file as a string so tests can eval it into jsdom
function readSource(file) {
  return readFileSync(resolve(STATIC_DIR, file), 'utf-8');
}

export default defineConfig({
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['unit/**/*.test.js'],
    setupFiles: ['./setup.js'],
    coverage: {
      reporter: 'text',
      include: ['../../../main/resources/static/snap-agent/*.js'],
    },
  },
});
