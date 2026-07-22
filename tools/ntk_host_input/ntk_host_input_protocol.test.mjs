import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import grpc from '@grpc/grpc-js';
import protoLoader from '@grpc/proto-loader';

const here = path.dirname(fileURLToPath(import.meta.url));
const helper = path.join(here, 'ntk_host_input.mjs');
const PROTOCOL = 2;
const RELEASE_MAGIC = 0x4e545232;

test('protocol 2 prepares every request before a fixed release and has no start fallback',
  { timeout: 20_000 }, async (t) => {
    const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'ntk-host-protocol-'));
    const protoPath = path.join(temporary, 'emulator_controller.proto');
    fs.writeFileSync(protoPath, `
      syntax = "proto3";
      package android.emulation.control;
      service EmulatorController {
        rpc getStatus(Empty) returns (Status);
        rpc streamInputEvent(stream InputEvent) returns (Empty);
      }
      message Empty {}
      message Status {}
      message Touch {
        int32 x = 1; int32 y = 2; int32 identifier = 3; int32 pressure = 4;
        int32 touch_major = 5; int32 touch_minor = 6; int32 expiration = 7;
        int32 orientation = 8;
      }
      message TouchEvent { repeated Touch touches = 1; int32 display = 2; }
      message InputEvent { TouchEvent touch_event = 1; }
    `, 'utf8');

    const packageDefinition = protoLoader.loadSync(protoPath, {
      keepCase: true, longs: String, enums: Number, defaults: true, oneofs: true,
    });
    const loaded = grpc.loadPackageDefinition(packageDefinition);
    const service = loaded.android.emulation.control.EmulatorController;
    const inputEvents = [];
    const grpcServer = new grpc.Server();
    grpcServer.addService(service.service, {
      getStatus(_call, callback) { callback(null, {}); },
      streamInputEvent(call, callback) {
        call.on('data', (value) => inputEvents.push(value));
        call.on('end', () => callback(null, {}));
      },
    });
    const grpcPort = await bindGrpc(grpcServer);
    const controlPort = await reservePort();
    const child = spawn(process.execPath, [
      helper,
      '--grpc-host', '127.0.0.1',
      '--grpc-port', String(grpcPort),
      '--control-host', '127.0.0.1',
      '--control-port', String(controlPort),
      '--proto', protoPath,
      '--connect-timeout-ms', '3000',
    ], { cwd: here, stdio: ['ignore', 'pipe', 'pipe'] });
    const stdout = jsonLines(child.stdout);
    let stderr = '';
    child.stderr.setEncoding('utf8');
    child.stderr.on('data', (chunk) => { stderr += chunk; });
    t.after(async () => {
      child.kill('SIGTERM');
      await Promise.race([
        new Promise((resolve) => child.once('exit', resolve)),
        new Promise((resolve) => setTimeout(resolve, 1_000)),
      ]);
      grpcServer.forceShutdown();
      fs.rmSync(temporary, { recursive: true, force: true });
    });

    const ready = await stdout.nextMatching((value) => value.event === 'ready');
    assert.equal(ready.protocol, PROTOCOL);
    // resetTouchscreenState completes before ready and is not part of a timed plan.
    inputEvents.length = 0;

    let socket = net.createConnection({ host: '127.0.0.1', port: controlPort });
    socket.setNoDelay(true);
    let control = jsonLines(socket);
    await new Promise((resolve, reject) => {
      socket.once('connect', resolve);
      socket.once('error', reject);
    });
    t.after(() => socket.destroy());
    assert.equal((await control.next()).type, 'hello');

    const abortedRunToken = 'protocol-2-abort-test';
    writeJson(socket, { type: 'arm', protocol: 1, runToken: abortedRunToken });
    const removedProtocolOne = await control.next();
    assert.equal(removedProtocolOne.type, 'error');
    assert.match(removedProtocolOne.error, /unsupported_protocol:1/);
    writeJson(socket, { type: 'arm', protocol: PROTOCOL, runToken: abortedRunToken });
    assert.equal((await control.next()).type, 'armed');

    writeJson(socket, { type: 'start', protocol: PROTOCOL, runToken: abortedRunToken });
    const removedFallback = await control.next();
    assert.equal(removedFallback.type, 'error');
    assert.match(removedFallback.error, /unknown_request_type:start/);
    assert.equal(inputEvents.length, 0);

    const directions = new Array(59).fill(1);
    directions[19] = -1;
    directions[20] = -1;
    directions[39] = -1;
    const plan = {
      x: 540,
      startY: 2100,
      endY: 240,
      gestures: 59,
      steps: 4,
      sampleIntervalMs: 12,
      gestureGapMs: 24,
      maxLatenessMs: 16,
      display: 0,
      directions,
    };
    const digest = planDigest(plan);
    const abortReleaseNonce = 987654321n;
    writeJson(socket, {
      type: 'prepare',
      protocol: PROTOCOL,
      runToken: abortedRunToken,
      releaseNonce: abortReleaseNonce.toString(),
      guestPlanDigest: digest,
      ...plan,
    });
    const abortPrepared = await control.next();
    assert.equal(abortPrepared.type, 'prepared');
    assert.equal(abortPrepared.preparedPlanDigest, digest);
    assert.equal(abortPrepared.earlyInputCount, 0);
    const abortClosed = new Promise((resolve) => socket.once('close', resolve));
    socket.destroy();
    await abortClosed;
    const abortLog = await stdout.nextMatching((value) =>
      value.event === 'plan_prepare_aborted' && value.runToken === abortedRunToken);
    assert.equal(abortLog.preparedPlanDigest, digest);
    assert.equal(abortLog.earlyInputCount, 0);
    assert.equal(inputEvents.length, 0, 'aborted prepared state emitted physical input');

    socket = net.createConnection({ host: '127.0.0.1', port: controlPort });
    socket.setNoDelay(true);
    control = jsonLines(socket);
    await new Promise((resolve, reject) => {
      socket.once('connect', resolve);
      socket.once('error', reject);
    });
    assert.equal((await control.next()).type, 'hello');
    const runToken = 'protocol-2-state-test';
    writeJson(socket, { type: 'arm', protocol: PROTOCOL, runToken });
    assert.equal((await control.next()).type, 'armed');

    const releaseNonce = 123456789n;
    writeJson(socket, {
      type: 'prepare',
      protocol: PROTOCOL,
      runToken,
      releaseNonce: releaseNonce.toString(),
      guestPlanDigest: digest,
      ...plan,
    });
    const prepared = await control.next();
    assert.equal(prepared.type, 'prepared');
    assert.equal(prepared.preparedPlanDigest, digest);
    assert.equal(prepared.preparedGestures, 59);
    assert.equal(prepared.preparedEvents, 295);
    assert.equal(prepared.earlyInputCount, 0);
    await new Promise((resolve) => setTimeout(resolve, 30));
    assert.equal(inputEvents.length, 0, 'prepared state emitted input before release');

    const release = Buffer.alloc(32);
    release.writeUInt32BE(RELEASE_MAGIC, 0);
    release.writeUInt32BE(PROTOCOL, 4);
    release.writeBigUInt64BE(releaseNonce, 8);
    release.writeBigUInt64BE(1_000_000n, 16);
    release.writeBigUInt64BE(1_000_001n, 24);
    socket.write(release);

    const started = await control.next();
    assert.equal(started.type, 'started');
    assert.equal(started.preparedPlanDigest, digest);
    assert.equal(started.earlyInputCount, 0);
    assert.ok(BigInt(started.releaseReceiveHostNs) <= BigInt(started.firstDownHostNs));
    assert.ok(started.releaseToFirstDownMicros <= 16_000);
    const result = await control.next();
    assert.equal(result.type, 'result');
    assert.equal(result.ok, true, `${result.error || ''}\n${stderr}`);
    assert.equal(result.preparedPlanDigest, digest);
    assert.equal(result.queuedEvents, 295);
    assert.equal(result.totalEvents, 295);
    assert.equal(result.reverseGestures, 3);
    assert.equal(result.earlyInputCount, 0);
    assert.equal(inputEvents.length, 295);

    const preparedLog = await stdout.nextMatching((value) =>
      value.event === 'plan_prepared' && value.runToken === runToken);
    const releasedLog = await stdout.nextMatching((value) =>
      value.event === 'plan_released' && value.runToken === runToken);
    const startedLog = await stdout.nextMatching((value) =>
      value.event === 'plan_started' && value.runToken === runToken);
    const completedLog = await stdout.nextMatching((value) =>
      value.event === 'plan_complete' && value.runToken === runToken);
    assert.equal(preparedLog.preparedPlanDigest, digest);
    assert.equal(releasedLog.earlyInputCount, 0);
    assert.ok(BigInt(startedLog.releaseReceiveHostNs) <= BigInt(startedLog.firstDownHostNs));
    assert.equal(completedLog.queuedEvents, 295);
  });

function planDigest(plan) {
  const canonical = `v=2;x=${plan.x};startY=${plan.startY};endY=${plan.endY}`
    + `;gestures=${plan.gestures};steps=${plan.steps}`
    + `;sampleIntervalMs=${plan.sampleIntervalMs};gestureGapMs=${plan.gestureGapMs}`
    + `;maxLatenessMs=${plan.maxLatenessMs};display=${plan.display}`
    + `;directions=${plan.directions.join(',')}`;
  return crypto.createHash('sha256').update(canonical, 'utf8').digest('hex');
}

function writeJson(socket, value) {
  socket.write(`${JSON.stringify(value)}\n`);
}

function jsonLines(stream) {
  stream.setEncoding('utf8');
  const queued = [];
  const waiters = [];
  let buffer = '';
  stream.on('data', (chunk) => {
    buffer += chunk;
    while (true) {
      const newline = buffer.indexOf('\n');
      if (newline < 0) break;
      const line = buffer.slice(0, newline).trim();
      buffer = buffer.slice(newline + 1);
      if (!line) continue;
      const value = JSON.parse(line);
      const waiter = waiters.shift();
      if (waiter) waiter.resolve(value);
      else queued.push(value);
    }
  });
  stream.on('error', (error) => {
    while (waiters.length) waiters.shift().reject(error);
  });
  return {
    next() {
      if (queued.length) return Promise.resolve(queued.shift());
      return new Promise((resolve, reject) => waiters.push({ resolve, reject }));
    },
    async nextMatching(predicate) {
      while (true) {
        const value = await this.next();
        if (predicate(value)) return value;
      }
    },
  };
}

function bindGrpc(server) {
  return new Promise((resolve, reject) => {
    server.bindAsync('127.0.0.1:0', grpc.ServerCredentials.createInsecure(),
      (error, port) => {
        if (error) reject(error);
        else resolve(port);
      });
  });
}

async function reservePort() {
  const server = net.createServer();
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  const { port } = server.address();
  await new Promise((resolve) => server.close(resolve));
  return port;
}
