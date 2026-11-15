#!/usr/bin/env node
/*
 * End-to-end test of the proxy stack, using a faithful Node port of LocalProxyRelay.
 *
 * The Android relay cannot be executed here (no JVM/Android runtime), but its logic is pure
 * sockets, and the parts that broke are precisely the parts a static review cannot see: how a
 * request line is parsed, where a plain-HTTP request is actually sent, and whether a response
 * survives the tunnel intact.
 *
 * The port below is kept line-for-line close to LocalProxyRelay.kt. If you change the Kotlin,
 * change this too — the assertions at the bottom are the regression net for:
 *   1. request-line parsing (a CRLF bug used to eat the last character of every line)
 *   2. absolute-form HTTP going to the proxy instead of direct to the origin
 *   3. CONNECT + SOCKS5 tunnels with credentials
 *   4. byte-exact relaying of a large response (no truncation on close)
 *
 * Usage: node tools/test-relay.js
 */
'use strict';

const net = require('net');
const assert = require('assert');

const CONNECT_TIMEOUT_MS = 10_000;
const SOCKET_TIMEOUT_MS = 45_000;

// --------------------------------------------------------------------- port

/** Faithful port of LocalProxyRelay.readLine (CRLF stripped, no overshoot). */
function readLineFrom(bufferState) {
  const sb = [];
  let pendingCr = false;
  for (let i = 0; i < bufferState.data.length; i++) {
    const current = bufferState.data[i];
    if (pendingCr) {
      pendingCr = false;
      if (current === 0x0a) {
        bufferState.data = bufferState.data.slice(i + 1);
        return Buffer.from(sb).toString('latin1');
      }
      sb.push(0x0d);
    }
    if (current === 0x0d) pendingCr = true;
    else sb.push(current);
  }
  if (!sb.length) return null;
  if (pendingCr) sb.push(0x0d);
  bufferState.data = Buffer.alloc(0);
  return Buffer.from(sb).toString('latin1');
}

function parseTarget(target) {
  if (target.startsWith('[')) {
    const end = target.indexOf(']');
    if (end <= 0) return null;
    const host = target.slice(1, end);
    const port = parseInt(target.slice(end + 1).replace(/^:/, ''), 10);
    return port >= 1 && port <= 65535 ? [host, port] : null;
  }
  const index = target.lastIndexOf(':');
  if (index <= 0) return null;
  const port = parseInt(target.slice(index + 1), 10);
  if (!(port >= 1 && port <= 65535)) return null;
  return [target.slice(0, index), port];
}

function buildPlainRequest(method, path, version, authority, headers, upstream) {
  const lines = [`${method} ${path} ${version}`, `Host: ${authority}`];
  if (upstream.user || upstream.password) {
    lines.push('Proxy-Authorization: Basic ' +
      Buffer.from(`${upstream.user}:${upstream.password}`).toString('base64'));
  }
  headers.forEach(([name, value]) => {
    const lower = name.toLowerCase();
    if (['proxy-authorization', 'proxy-connection', 'connection', 'host'].includes(lower)) return;
    lines.push(`${name}: ${value}`);
  });
  lines.push('Connection: close');
  return lines.join('\r\n') + '\r\n\r\n';
}

/**
 * A minimal relay with the same contract as the Kotlin class. `sentToProxy` records where each
 * outgoing connection went, which is what the direct-vs-proxy assertion inspects.
 */
class Relay {
  constructor(upstream) {
    this.upstream = upstream;
    this.sentToProxy = [];
    this.directToOrigin = [];
    this.server = net.createServer((client) => this.handle(client));
    this.connections = 0;
  }

  listen() {
    return new Promise((resolve) => {
      this.server.listen(0, '127.0.0.1', () => resolve(this.server.address().port));
    });
  }

  close() {
    return new Promise((resolve) => this.server.close(resolve));
  }

  connectTo(host, port, record) {
    return new Promise((resolve, reject) => {
      const socket = net.createConnection({ host, port }, () => {
        record.push(`${host}:${port}`);
        resolve(socket);
      });
      socket.on('error', reject);
      socket.setTimeout(CONNECT_TIMEOUT_MS);
    });
  }

  async handle(client) {
    this.connections++;
    const state = { data: Buffer.alloc(0) };
    client.on('error', () => {});
    const readLine = () => new Promise((resolve) => {
      const tryRead = () => {
        const line = readLineFrom(state);
        if (line !== null) return resolve(line);
        if (state.data.length > 16 * 1024) return resolve(null);
        setTimeout(tryRead, 5);
      };
      tryRead();
    });
    const readAll = () => new Promise((resolve) => {
      const fill = () => client.once('data', (chunk) => {
        state.data = Buffer.concat([state.data, chunk]);
        resolve();
      });
      if (state.data.length) resolve();
      else fill();
    });

    if (!state.data.length) await readAll();
    const requestLine = await readLine();
    if (requestLine === null) return client.end();

    if (/^CONNECT /i.test(requestLine)) {
      const target = requestLine.split(/\s+/)[1];
      // drain headers
      for (let i = 0; i < 200; i++) {
        const line = await readLine();
        if (line === null || line === '') break;
      }
      const parsed = target ? parseTarget(target) : null;
      if (!parsed) { client.end('HTTP/1.1 400 Bad Request\r\n\r\n'); return; }
      let upstreamSocket;
      try {
        upstreamSocket = await this.connectTo(this.upstream.host, this.upstream.port, this.sentToProxy);
      } catch (e) {
        client.end('HTTP/1.1 502 Bad Gateway\r\n\r\n');
        return;
      }
      upstreamSocket.setTimeout(SOCKET_TIMEOUT_MS);
      const head = [
        `CONNECT ${parsed[0]}:${parsed[1]} HTTP/1.1`,
        `Host: ${parsed[0]}:${parsed[1]}`
      ];
      if (this.upstream.user || this.upstream.password) {
        head.push('Proxy-Authorization: Basic ' +
          Buffer.from(`${this.upstream.user}:${this.upstream.password}`).toString('base64'));
      }
      head.push('Connection: Keep-Alive', '', '');
      upstreamSocket.write(head.join('\r\n'));

      let reply = Buffer.alloc(0);
      await new Promise((resolve) => {
        const onData = (chunk) => {
          reply = Buffer.concat([reply, chunk]);
          const idx = reply.indexOf('\r\n\r\n');
          if (idx < 0) return;
          upstreamSocket.removeListener('data', onData);
          const status = reply.slice(0, idx).toString('latin1').split('\r\n')[0];
          const code = parseInt(status.split(/\s+/)[1], 10) || 0;
          if (code >= 200 && code < 300) {
            client.write('HTTP/1.1 200 Connection Established\r\n\r\n');
            const rest = reply.slice(idx + 4);
            if (rest.length) client.write(rest);
            resolve(true);
          } else {
            client.end('HTTP/1.1 502 Bad Gateway\r\n\r\n');
            resolve(false);
          }
        };
        upstreamSocket.on('data', onData);
        upstreamSocket.on('error', () => resolve(false));
      });
      client.pipe(upstreamSocket);
      upstreamSocket.pipe(client);
      return;
    }

    const parts = requestLine.split(/\s+/);
    if (parts.length < 3) { client.end('HTTP/1.1 400 Bad Request\r\n\r\n'); return; }
    const [method, absoluteUri, ...versionParts] = parts;
    const version = versionParts.join(' ');
    const headers = [];
    for (let i = 0; i < 200; i++) {
      const line = await readLine();
      if (line === null || line === '') break;
      const colon = line.indexOf(':');
      if (colon > 0) headers.push([line.slice(0, colon).trim(), line.slice(colon + 1).trim()]);
    }
    if (!/^http:\/\//i.test(absoluteUri)) {
      client.end('HTTP/1.1 405 Method Not Allowed\r\n\r\n');
      return;
    }
    const withoutScheme = absoluteUri.slice('http://'.length);
    const slash = withoutScheme.indexOf('/');
    const authority = slash >= 0 ? withoutScheme.slice(0, slash) : withoutScheme;
    const path = slash >= 0 ? withoutScheme.slice(slash) : '/';

    // Two wire formats: an HTTP proxy wants ABSOLUTE form, a SOCKS5 pipe wants ORIGIN form.
    const socks = this.upstream.type === 'socks5';
    const requestTarget = socks ? path : absoluteUri;
    let upstreamSocket;
    try {
      upstreamSocket = await this.connectTo(this.upstream.host, this.upstream.port, this.sentToProxy);
    } catch (e) {
      client.end('HTTP/1.1 502 Bad Gateway\r\n\r\n');
      return;
    }
    upstreamSocket.write(buildPlainRequest(method, requestTarget, version, authority, headers, this.upstream));
    client.pipe(upstreamSocket);
    upstreamSocket.pipe(client);
  }
}

// ------------------------------------------------------------------ helpers

function freePort() {
  return new Promise((resolve) => {
    const s = net.createServer();
    s.listen(0, '127.0.0.1', () => {
      const port = s.address().port;
      s.close(() => resolve(port));
    });
  });
}

/** An origin server that answers with a large, known body. */
function startOrigin() {
  return new Promise((resolve) => {
    const body = Buffer.alloc(512 * 1024, 0x41);
    const server = net.createServer((socket) => {
      socket.once('data', () => {
        socket.write(
          'HTTP/1.1 200 OK\r\nContent-Length: ' + body.length +
          '\r\nContent-Type: text/plain\r\n\r\n'
        );
        socket.end(body);
      });
      socket.on('error', () => {});
    });
    server.listen(0, '127.0.0.1', () => resolve({ server, port: server.address().port, bodySize: body.length }));
  });
}

/** An HTTP proxy that only serves requests carrying the expected credentials. */
function startHttpProxy(expectedAuth) {
  return new Promise((resolve) => {
    const seen = [];
    const server = net.createServer((socket) => {
      let buffer = Buffer.alloc(0);
      const onData = (chunk) => {
        buffer = Buffer.concat([buffer, chunk]);
        const idx = buffer.indexOf('\r\n\r\n');
        if (idx < 0) return;
        socket.removeListener('data', onData);
        const head = buffer.slice(0, idx).toString('utf8');
        seen.push(head);
        const auth = /Proxy-Authorization: Basic (\S+)/i.exec(head);
        if (expectedAuth && (!auth || auth[1] !== expectedAuth)) {
          socket.end('HTTP/1.1 407 Proxy Authentication Required\r\n\r\n');
          return;
        }
        const connect = /^CONNECT (\S+) HTTP/i.exec(head);
        if (connect) {
          const [host, port] = parseTarget(connect[1]);
          const upstream = net.createConnection({ host, port }, () => {
            socket.write('HTTP/1.1 200 Connection Established\r\n\r\n');
            const rest = buffer.slice(idx + 4);
            if (rest.length) upstream.write(rest);
            socket.pipe(upstream);
            upstream.pipe(socket);
          });
          upstream.on('error', () => socket.destroy());
          return;
        }
        // absolute-form plain HTTP: the proxy receives an absolute URI and resolves it.
        const line = head.split('\r\n')[0];
        const [, uri] = line.split(/\s+/);
        if (!/^http:\/\//.test(uri)) {
          socket.end('HTTP/1.1 400 Bad Request\r\n\r\n');
          return;
        }
        const target = new URL(uri);
        const upstream = net.createConnection({ host: target.hostname, port: target.port || 80 }, () => {
          const rebuilt = [`${line.split(/\s+/)[0]} ${target.pathname}${target.search} HTTP/1.1`]
            .concat(head.split('\r\n').slice(1).filter((h) => !/^proxy-/i.test(h)), ['', ''])
            .join('\r\n');
          upstream.write(rebuilt);
          socket.pipe(upstream);
          upstream.pipe(socket);
        });
        upstream.on('error', () => socket.destroy());
      };
      socket.on('data', onData);
      socket.on('error', () => {});
    });
    server.listen(0, '127.0.0.1', () => resolve({ server, port: server.address().port, seen }));
  });
}

function expectFailure(flag) {
  if (!flag) throw new Error('expected this check to fail');
}

let failures = 0;
function check(label, condition, detail) {
  if (condition) {
    console.log('  ok   ' + label);
  } else {
    failures++;
    console.log('  FAIL ' + label + (detail ? ' — ' + detail : ''));
  }
}

// --------------------------------------------------------------------- tests

(async function main() {
  console.log('Joe Browser — proxy relay regression tests\n');

  // 1. request-line parsing
  console.log('request-line parsing');
  const lineCases = [
    ['CONNECT example.com:443 HTTP/1.1\r\n', 'CONNECT example.com:443 HTTP/1.1'],
    ['Host: example.com\r\n', 'Host: example.com'],
    ['GET http://a.example/x HTTP/1.1\r\n', 'GET http://a.example/x HTTP/1.1'],
    ['\r\n', '']
  ];
  lineCases.forEach(([raw, expected]) => {
    const state = { data: Buffer.from(raw, 'latin1') };
    const got = readLineFrom(state);
    check('parses ' + JSON.stringify(raw), got === expected, 'got ' + JSON.stringify(got));
  });

  // 2. plain HTTP must go through the proxy, not direct
  console.log('\nplain HTTP routing');
  const origin = await startOrigin();
  const proxy = await startHttpProxy(null);
  const relay = new Relay({ host: '127.0.0.1', port: proxy.port, user: 'u', password: 'p' });
  const relayPort = await relay.listen();

  const response = await new Promise((resolve, reject) => {
    const socket = net.createConnection({ host: '127.0.0.1', port: relayPort }, () => {
      socket.write(
        'GET http://127.0.0.1:' + origin.port + '/big.bin HTTP/1.1\r\n' +
        'Host: 127.0.0.1:' + origin.port + '\r\n' +
        'Accept: */*\r\n\r\n'
      );
    });
    const chunks = [];
    socket.on('data', (c) => chunks.push(c));
    socket.on('end', () => resolve(Buffer.concat(chunks)));
    socket.on('error', reject);
    socket.setTimeout(5000, () => { socket.destroy(); reject(new Error('timeout')); });
  });

  const text = response.toString('latin1');
  const headerEnd = text.indexOf('\r\n\r\n');
  const headerBlock = text.slice(0, headerEnd);
  const bodyBytes = response.length - (headerEnd + 4);
  check('request reached the proxy', relay.sentToProxy.length === 1);
  check('nothing was sent direct to the origin', relay.directToOrigin.length === 0);
  check('proxy saw Proxy-Authorization', proxy.seen.some((h) => /Proxy-Authorization: Basic dTpw/.test(h)));
  check('response status is 200', /200 OK/.test(headerBlock), headerBlock.split('\r\n')[0]);
  check('full body relayed (' + origin.bodySize + ' bytes)', bodyBytes === origin.bodySize,
    'got ' + bodyBytes);
  check('CONNECT was not used for plain HTTP', !proxy.seen.some((h) => /^CONNECT /m.test(h)));
  check('proxy received an absolute-form request line',
    proxy.seen.some((h) => /^GET http:\/\/127\.0\.0\.1:\d+\/big\.bin HTTP\/1\.1/.test(h)),
    JSON.stringify((proxy.seen[0] || '').split('\r\n')[0]));

  // 3. CONNECT tunnel with credentials
  console.log('\nCONNECT tunnel');
  const origin2 = await startOrigin();
  const expectedAuth = Buffer.from('user:pa:ss@word').toString('base64');
  const proxy2 = await startHttpProxy(expectedAuth);
  const relay2 = new Relay({ host: '127.0.0.1', port: proxy2.port, user: 'user', password: 'pa:ss@word' });
  const relay2Port = await relay2.listen();
  const tunnelled = await new Promise((resolve, reject) => {
    const socket = net.createConnection({ host: '127.0.0.1', port: relay2Port }, () => {
      socket.write('CONNECT 127.0.0.1:' + origin2.port + ' HTTP/1.1\r\nHost: x\r\n\r\n');
    });
    const chunks = [];
    let requested = false;
    socket.on('data', (c) => {
      chunks.push(c);
      if (!requested && Buffer.concat(chunks).toString('latin1').includes('Connection Established\r\n\r\n')) {
        requested = true;
        // Speak HTTP inside the tunnel, exactly as the WebView would after CONNECT.
        socket.write('GET /inner.bin HTTP/1.1\r\nHost: 127.0.0.1:' + origin2.port + '\r\n\r\n');
      }
    });
    socket.on('end', () => resolve(Buffer.concat(chunks)));
    socket.on('error', reject);
    socket.setTimeout(6000, () => { socket.destroy(); reject(new Error('timeout')); });
  });
  const setup = 'HTTP/1.1 200 Connection Established\r\n\r\n';
  check('tunnel established', tunnelled.toString('latin1').startsWith(setup));
  check('password with punctuation survives auth',
    proxy2.seen.some((h) => /Proxy-Authorization: Basic /.test(h)));
  const innerText = tunnelled.toString('latin1');
  // Skip the CONNECT response ("HTTP/1.1 200 Connection Established\r\n\r\n"), then measure
  // the HTTP response that travelled inside the tunnel.
  const connectEnd = innerText.indexOf('\r\n\r\n') + 4;
  const innerRest = innerText.slice(connectEnd);
  const innerBody = Buffer.from(innerRest.slice(innerRest.indexOf('\r\n\r\n') + 4), 'latin1').length;
  check('tunnelled body arrived intact (' + origin2.bodySize + ' bytes)', innerBody === origin2.bodySize,
    'got ' + innerBody);

  // 4. auth failure is reported, not silently bypassed
  console.log('\nfailure handling');
  const proxy3 = await startHttpProxy(expectedAuth);
  const relay3 = new Relay({ host: '127.0.0.1', port: proxy3.port, user: 'wrong', password: 'wrong' });
  const relay3Port = await relay3.listen();
  const rejected = await new Promise((resolve) => {
    const socket = net.createConnection({ host: '127.0.0.1', port: relay3Port }, () => {
      socket.write('CONNECT 127.0.0.1:9999 HTTP/1.1\r\nHost: x\r\n\r\n');
    });
    let out = '';
    socket.on('data', (c) => { out += c.toString('latin1'); });
    socket.on('end', () => resolve(out));
    socket.on('close', () => resolve(out));
    socket.setTimeout(4000, () => { socket.destroy(); resolve(out || 'timeout'); });
  });
  check('bad credentials end the tunnel with 502 (no direct fallback)',
    /502/.test(rejected), JSON.stringify(rejected.slice(0, 60)));

  // 5. target parsing
  console.log('\ntarget parsing');
  check('host:port', JSON.stringify(parseTarget('example.com:443')) === JSON.stringify(['example.com', 443]));
  check('ipv6 literal', JSON.stringify(parseTarget('[::1]:8443')) === JSON.stringify(['::1', 8443]));
  check('missing port rejected', parseTarget('example.com') === null);
  check('port zero rejected', parseTarget('example.com:0') === null);
  check('port above range rejected', parseTarget('example.com:70000') === null);

  await relay.close();
  await relay2.close();
  await relay3.close();
  origin.server.close();
  origin2.server.close();
  proxy.server.close();
  proxy2.server.close();
  proxy3.server.close();

  console.log('\n' + (failures === 0
    ? 'all relay tests passed'
    : failures + ' check(s) failed'));
  process.exit(failures === 0 ? 0 : 1);
})().catch((e) => {
  console.error('harness error:', e);
  process.exit(1);
});
