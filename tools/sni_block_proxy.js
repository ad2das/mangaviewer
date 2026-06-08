#!/usr/bin/env node
const net = require('net');

const args = new Map();
for (let i = 2; i < process.argv.length; i++) {
  const arg = process.argv[i];
  if (!arg.startsWith('--')) continue;
  const eq = arg.indexOf('=');
  if (eq >= 0) args.set(arg.slice(2, eq), arg.slice(eq + 1));
  else args.set(arg.slice(2), process.argv[++i] || 'true');
}

const port = Number(args.get('port') || 18088);
const block = String(args.get('block') || 'sbxh4.com').toLowerCase();
const mode = String(args.get('mode') || 'first-chunk');

function log(...parts) {
  console.log(new Date().toISOString(), ...parts);
}

function parseConnectTarget(line) {
  const match = /^CONNECT\s+([^\s:]+)(?::(\d+))?\s+/i.exec(line || '');
  if (!match) return null;
  return { host: match[1], port: Number(match[2] || 443) };
}

function parseSni(buffer) {
  if (!buffer || buffer.length < 6) return null;
  if (buffer[0] !== 0x16 || buffer[1] !== 0x03 || buffer[5] !== 0x01) return null;
  const recordLength = buffer.readUInt16BE(3);
  if (buffer.length < 5 + recordLength) return null;
  let p = 5;
  if (buffer[p] !== 0x01) return null;
  p += 4;
  p += 2 + 32;
  if (p >= buffer.length) return null;
  const sessionLen = buffer[p++];
  p += sessionLen;
  if (p + 2 > buffer.length) return null;
  const cipherLen = buffer.readUInt16BE(p);
  p += 2 + cipherLen;
  if (p >= buffer.length) return null;
  const compressionLen = buffer[p++];
  p += compressionLen;
  if (p + 2 > buffer.length) return null;
  const extLen = buffer.readUInt16BE(p);
  p += 2;
  const extEnd = p + extLen;
  while (p + 4 <= extEnd && p + 4 <= buffer.length) {
    const type = buffer.readUInt16BE(p);
    const len = buffer.readUInt16BE(p + 2);
    p += 4;
    if (type === 0) {
      if (p + 5 > buffer.length) return null;
      const listLen = buffer.readUInt16BE(p);
      let q = p + 2;
      const listEnd = q + listLen;
      while (q + 3 <= listEnd && q + 3 <= buffer.length) {
        const nameType = buffer[q++];
        const nameLen = buffer.readUInt16BE(q);
        q += 2;
        if (nameType === 0 && q + nameLen <= buffer.length)
          return buffer.slice(q, q + nameLen).toString('ascii').toLowerCase();
        q += nameLen;
      }
      return null;
    }
    p += len;
  }
  return null;
}

function isBlockedName(name) {
  return name === block || name.endsWith(`.${block}`);
}

function closeBoth(a, b) {
  try { a.destroy(); } catch (_) {}
  try { if (b) b.destroy(); } catch (_) {}
}

function pipeTunnel(client, target, firstBytes) {
  const upstream = net.connect(target.port, target.host, () => {
    client.write('HTTP/1.1 200 Connection Established\r\n\r\n');
    if (firstBytes && firstBytes.length) upstream.write(firstBytes);
    client.pipe(upstream);
    upstream.pipe(client);
  });
  upstream.setTimeout(30000);
  upstream.on('error', (err) => {
    log('UPSTREAM_FAIL', target.host, target.port, err.message);
    closeBoth(client, upstream);
  });
  upstream.on('timeout', () => closeBoth(client, upstream));
}

function handle(client) {
  client.setTimeout(30000);
  let header = Buffer.alloc(0);
  let target = null;
  client.on('data', function onHeader(data) {
    header = Buffer.concat([header, data]);
    const marker = header.indexOf('\r\n\r\n');
    if (marker < 0) {
      if (header.length > 16384) closeBoth(client);
      return;
    }
    client.off('data', onHeader);
    const headerText = header.slice(0, marker).toString('ascii');
    target = parseConnectTarget(headerText.split('\r\n')[0]);
    if (!target) {
      client.write('HTTP/1.1 501 Not Implemented\r\nConnection: close\r\n\r\n');
      closeBoth(client);
      return;
    }
    const buffered = header.slice(marker + 4);
    client.write('HTTP/1.1 200 Connection Established\r\n\r\n');
    const inspect = buffered.length ? Promise.resolve(buffered) : new Promise((resolve) => {
      client.once('data', resolve);
      setTimeout(() => resolve(Buffer.alloc(0)), 1200);
    });
    inspect.then((firstBytes) => {
      const sni = parseSni(firstBytes);
      if (sni && isBlockedName(sni)) {
        log('BLOCK_SNI', sni, target.host, `bytes=${firstBytes.length}`, `mode=${mode}`);
        closeBoth(client);
        return;
      }
      if (!sni && mode === 'connect-host' && isBlockedName(target.host.toLowerCase())) {
        log('BLOCK_CONNECT_HOST', target.host, `bytes=${firstBytes.length}`);
        closeBoth(client);
        return;
      }
      log('ALLOW', target.host, `sni=${sni || 'unparsed'}`, `bytes=${firstBytes.length}`);
      pipeTunnelAfterConnectReply(client, target, firstBytes);
    });
  });
  client.on('error', () => closeBoth(client));
  client.on('timeout', () => closeBoth(client));
}

function pipeTunnelAfterConnectReply(client, target, firstBytes) {
  const upstream = net.connect(target.port, target.host, () => {
    if (firstBytes && firstBytes.length) upstream.write(firstBytes);
    client.pipe(upstream);
    upstream.pipe(client);
  });
  upstream.setTimeout(30000);
  upstream.on('error', (err) => {
    log('UPSTREAM_FAIL', target.host, target.port, err.message);
    closeBoth(client, upstream);
  });
  upstream.on('timeout', () => closeBoth(client, upstream));
}

const server = net.createServer(handle);
server.listen(port, '0.0.0.0', () => {
  log('LISTEN', `0.0.0.0:${port}`, `block=${block}`, `mode=${mode}`);
});
