'use strict';
// Test-only import graph loader.
// Reads an ES module as text, recursively finds relative imports,
// rewrites them to nested data: URLs (bottom-up), and returns a single
// data: URL that can be imported. Fails loudly on missing file or cyclic import.

const fs = require('node:fs');
const path = require('node:path');

const FROM_RE = /(from\s+['"])(\.[^'"]+)(['"])/g;

function loadModule(filePath, loading = new Set()) {
  const resolved = path.resolve(filePath);
  if (loading.has(resolved)) {
    throw new Error(`Cyclic import detected: ${resolved}`);
  }
  if (!fs.existsSync(resolved)) {
    throw new Error(`Missing import: ${resolved}`);
  }
  loading.add(resolved);

  const src = fs.readFileSync(resolved, 'utf8');
  const dir = path.dirname(resolved);

  const result = src.replace(FROM_RE, (full, prefix, specifier, suffix) => {
    const importPath = path.resolve(dir, specifier);
    const nested = loadModule(importPath, loading);
    const dataUrl = 'data:text/javascript;base64,' + Buffer.from(nested).toString('base64');
    return prefix + dataUrl + suffix;
  });

  loading.delete(resolved);
  return result;
}

function loadAsDataUrl(filePath) {
  const source = loadModule(filePath);
  return 'data:text/javascript;base64,' + Buffer.from(source).toString('base64');
}

module.exports = { loadModule, loadAsDataUrl };
