import {createReadStream} from 'node:fs';
import {stat} from 'node:fs/promises';
import {createServer} from 'node:http';
import {dirname, extname, isAbsolute, relative, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

const workspaceRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const port = Number(process.env.PREVIEW_PORT) || 4173;
const contentTypes = {
  '.css': 'text/css; charset=utf-8',
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
};

createServer(async (request, response) => {
  try {
    const url = new URL(request.url || '/', `http://${request.headers.host || '127.0.0.1'}`);
    const requestedPath = decodeURIComponent(url.pathname === '/' ? '/tools/ui-preview.html' : url.pathname);
    const absolutePath = resolve(workspaceRoot, requestedPath.slice(1));
    const workspaceRelativePath = relative(workspaceRoot, absolutePath);

    // 预览服务只允许读取仓库内文件，避免编码后的路径穿越工作区。
    if (workspaceRelativePath.startsWith('..') || isAbsolute(workspaceRelativePath)) {
      response.writeHead(403).end('Forbidden');
      return;
    }

    const file = await stat(absolutePath);
    if (!file.isFile()) {
      response.writeHead(404).end('Not found');
      return;
    }

    response.writeHead(200, {
      'Cache-Control': 'no-store',
      'Content-Type': contentTypes[extname(absolutePath)] || 'application/octet-stream',
    });
    createReadStream(absolutePath).pipe(response);
  } catch (_) {
    response.writeHead(404).end('Not found');
  }
}).listen(port, '127.0.0.1', () => {
  console.log(`CodeDeck preview: http://127.0.0.1:${port}/tools/ui-preview.html`);
});
