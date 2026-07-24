import { readFileSync } from 'fs';
import { resolve } from 'path';
import { vi } from 'vitest';

// Path to the static frontend source directory
const STATIC_DIR = resolve(__dirname, '../../../main/resources/static/snap-agent');

// Read source files
globalThis.__SOURCE_DIR__ = STATIC_DIR;

// Helper: load a script file into the current jsdom global scope
globalThis.loadScript = function (filename) {
  const code = readFileSync(resolve(STATIC_DIR, filename), 'utf-8');
  // eslint-disable-next-line no-eval
  eval(code);
};

// Reset DOM and globals before each test file
beforeEach(() => {
  document.body.innerHTML = '';
  // Clear all mocks
  vi.restoreAllMocks();
});
