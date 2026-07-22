import fs from 'node:fs';
import crypto from 'node:crypto';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

import grpc from '@grpc/grpc-js';
import protoLoader from '@grpc/proto-loader';

const PROTOCOL_VERSION = 2;
const MAX_CONTROL_LINE_BYTES = 64 * 1024;
const RELEASE_FRAME_BYTES = 32;
const RELEASE_MAGIC = 0x4e545232;
const DEFAULT_GRPC_PORT = 8554;
const DEFAULT_CONTROL_PORT = 38081;
const DEFAULT_SAMPLE_INTERVAL_MS = 12;
const DEFAULT_GESTURE_GAP_MS = 24;
const DEFAULT_MAX_LATENESS_MS = 16;
const STRICT_GESTURES = 59;
const STRICT_STEPS = 4;
const STRICT_TOTAL_EVENTS = 295;
const NS_PER_MS = 1_000_000n;

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const options = parseArguments(process.argv.slice(2));
const protoPath = resolveProtoPath(options.proto);
const discovery = resolveDiscovery(options.discoveryDir, options.grpcPort);
const grpcPort = options.grpcPort ?? discovery?.grpcPort ?? DEFAULT_GRPC_PORT;
const grpcToken = options.grpcToken ?? discovery?.grpcToken ?? '';

const packageDefinition = protoLoader.loadSync(protoPath, {
  keepCase: true,
  longs: String,
  enums: Number,
  defaults: true,
  oneofs: true,
});
const loaded = grpc.loadPackageDefinition(packageDefinition);
const EmulatorController = loaded?.android?.emulation?.control?.EmulatorController;
if (!EmulatorController) {
  throw new Error(`EmulatorController service missing from ${protoPath}`);
}

const grpcAddress = `${options.grpcHost}:${grpcPort}`;
const client = new EmulatorController(grpcAddress, grpc.credentials.createInsecure(), {
  'grpc.keepalive_time_ms': 10_000,
  'grpc.keepalive_timeout_ms': 3_000,
  'grpc.max_reconnect_backoff_ms': 1_000,
});
const metadata = new grpc.Metadata();
if (grpcToken) {
  metadata.set('authorization', `Bearer ${grpcToken}`);
}

let inputStream = null;
let inputStreamFailure = null;
let inputStreamCompletion = null;
let activePlan = null;
let preparedPlan = null;
let activeTouch = null;
let physicalPlanWriteCount = 0;
let shuttingDown = false;
const sockets = new Set();

try {
  await waitForGrpcReady(client, metadata, options.connectTimeoutMs);
} catch (error) {
  log('startup_failed', {
    grpcAddress,
    message: error?.message || String(error),
    code: error?.code,
    discoveryFile: discovery?.file ?? '',
  });
  client.close();
  process.exit(2);
}
await resetTouchscreenState();
openInputStream();

const server = net.createServer((socket) => {
  sockets.add(socket);
  socket.setNoDelay(true);
  socket.setKeepAlive(true, 5_000);
  socket.ntkState = {
    buffer: '',
    armedToken: '',
    mode: 'json',
    releaseBuffer: null,
    releaseOffset: 0,
  };
  send(socket, {
    type: 'hello',
    protocol: PROTOCOL_VERSION,
    grpcAddress,
    producer: 'android-emulator-grpc-streamInputEvent',
  });

  socket.on('data', (chunk) => consumeControlData(socket, chunk));
  socket.on('error', (error) => {
    log('control_socket_error', { message: error.message });
  });
  socket.on('close', () => {
    sockets.delete(socket);
    if (preparedPlan?.socket === socket) {
      log('plan_prepare_aborted', {
        runToken: preparedPlan.runToken,
        preparedPlanDigest: preparedPlan.preparedPlanDigest,
        earlyInputCount: preparedPlan.earlyInputCount,
      });
      preparedPlan = null;
    }
    // A physical source does not stop because its observer disconnected.
  });
});

server.on('error', (error) => fatal('control_server_error', error));
server.listen(options.controlPort, options.controlHost, () => {
  const address = server.address();
  log('ready', {
    protocol: PROTOCOL_VERSION,
    controlHost: typeof address === 'object' && address ? address.address : options.controlHost,
    controlPort: typeof address === 'object' && address ? address.port : options.controlPort,
    guestControlAddress: `10.0.2.2:${options.controlPort}`,
    grpcAddress,
    grpcToken: grpcToken ? 'present' : 'absent',
    discoveryFile: discovery?.file ?? '',
    protoPath,
  });
});

process.on('SIGINT', () => void shutdown(130));
process.on('SIGTERM', () => void shutdown(143));
process.on('uncaughtException', (error) => fatal('uncaught_exception', error));
process.on('unhandledRejection', (error) => fatal('unhandled_rejection', error));

function parseArguments(args) {
  const parsed = {
    grpcHost: '127.0.0.1',
    grpcPort: null,
    grpcToken: '',
    controlHost: '127.0.0.1',
    controlPort: DEFAULT_CONTROL_PORT,
    proto: '',
    discoveryDir: '',
    connectTimeoutMs: 8_000,
  };
  for (let index = 0; index < args.length; index += 1) {
    const key = args[index];
    if (key === '--help' || key === '-h') {
      process.stdout.write([
        'Usage: node ntk_host_input.mjs [options]',
        '  --grpc-host HOST          Emulator gRPC host (default 127.0.0.1)',
        '  --grpc-port PORT          Emulator gRPC port (discovery/default 8554)',
        '  --grpc-token TOKEN        Override discovery authorization token',
        '  --control-host HOST       JSONL control listener (default 127.0.0.1)',
        '  --control-port PORT       JSONL control port (default 38081)',
        '  --proto PATH              emulator_controller.proto override',
        '  --discovery-dir PATH      Emulator discovery directory override',
        '  --connect-timeout-ms MS   gRPC readiness timeout (default 8000)',
        '',
      ].join('\n'));
      process.exit(0);
    }
    const value = args[index + 1];
    if (value == null || value.startsWith('--')) {
      throw new Error(`Missing value for ${key}`);
    }
    index += 1;
    switch (key) {
      case '--grpc-host': parsed.grpcHost = value; break;
      case '--grpc-port': parsed.grpcPort = integer(value, key, 1, 65_535); break;
      case '--grpc-token': parsed.grpcToken = value; break;
      case '--control-host': parsed.controlHost = value; break;
      case '--control-port': parsed.controlPort = integer(value, key, 1, 65_535); break;
      case '--proto': parsed.proto = value; break;
      case '--discovery-dir': parsed.discoveryDir = value; break;
      case '--connect-timeout-ms':
        parsed.connectTimeoutMs = integer(value, key, 100, 120_000);
        break;
      default: throw new Error(`Unknown argument ${key}`);
    }
  }
  return parsed;
}

function integer(value, label, minimum, maximum) {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < minimum || parsed > maximum) {
    throw new Error(`${label} must be an integer in [${minimum}, ${maximum}], got ${value}`);
  }
  return parsed;
}

function resolveProtoPath(override) {
  const candidates = [];
  if (override) candidates.push(override);
  for (const sdkRoot of [process.env.ANDROID_SDK_ROOT, process.env.ANDROID_HOME]) {
    if (sdkRoot) candidates.push(path.join(sdkRoot, 'emulator', 'lib', 'emulator_controller.proto'));
  }
  candidates.push(path.join(os.homedir(), 'AndroidTools', 'sdk', 'emulator', 'lib',
    'emulator_controller.proto'));
  candidates.push(path.resolve(scriptDir, '..', '..', '..', 'AndroidTools', 'sdk', 'emulator',
    'lib', 'emulator_controller.proto'));
  for (const candidate of candidates) {
    const resolved = path.resolve(candidate);
    if (fs.existsSync(resolved)) return resolved;
  }
  throw new Error(`emulator_controller.proto not found; tried ${candidates.join(', ')}`);
}

function resolveDiscovery(overrideDir, requestedPort) {
  const discoveryDir = overrideDir || path.join(
    process.env.LOCALAPPDATA || path.join(os.homedir(), 'AppData', 'Local'),
    'Temp', 'avd', 'running');
  if (!fs.existsSync(discoveryDir)) return null;
  const candidates = fs.readdirSync(discoveryDir)
    .filter((name) => /^pid_\d+(?:_info)?\.ini$/i.test(name))
    .map((name) => {
      const file = path.join(discoveryDir, name);
      return { file, mtimeMs: fs.statSync(file).mtimeMs, values: parseIni(file) };
    })
    .filter((entry) => entry.values['grpc.port'])
    .filter((entry) => requestedPort == null
      || Number(entry.values['grpc.port']) === requestedPort)
    .sort((left, right) => right.mtimeMs - left.mtimeMs);
  const selected = candidates[0];
  if (!selected) return null;
  return {
    file: selected.file,
    grpcPort: Number(selected.values['grpc.port']),
    grpcToken: selected.values['grpc.token'] || '',
  };
}

function parseIni(file) {
  const values = {};
  for (const rawLine of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const separator = rawLine.indexOf('=');
    if (separator <= 0) continue;
    values[rawLine.slice(0, separator).trim()] = rawLine.slice(separator + 1).trim();
  }
  return values;
}

async function waitForGrpcReady(grpcClient, authMetadata, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  await new Promise((resolve, reject) => {
    grpcClient.waitForReady(deadline, (error) => error ? reject(error) : resolve());
  });
  await new Promise((resolve, reject) => {
    grpcClient.getStatus({}, authMetadata, { deadline }, (error) => {
      if (error) reject(error);
      else resolve();
    });
  });
}

function openInputStream() {
  inputStreamFailure = null;
  let resolveCompletion;
  let rejectCompletion;
  inputStreamCompletion = new Promise((resolve, reject) => {
    resolveCompletion = resolve;
    rejectCompletion = reject;
  });
  inputStreamCompletion.catch(() => {});
  inputStream = client.streamInputEvent(metadata, (error) => {
    if (error) {
      rejectCompletion(error);
    } else {
      resolveCompletion();
    }
    if (!shuttingDown && error) {
      inputStreamFailure = error;
      failActivePlan(error);
      log('grpc_input_stream_callback_error', { message: error.message, code: error.code });
    }
  });
  inputStream.on('error', (error) => {
    rejectCompletion(error);
    if (shuttingDown) return;
    inputStreamFailure = error;
    failActivePlan(error);
    log('grpc_input_stream_error', { message: error.message, code: error.code });
  });
}

function consumeControlData(socket, chunk) {
  const state = socket.ntkState;
  if (state.mode === 'release') {
    if (!Buffer.isBuffer(chunk)) chunk = Buffer.from(chunk);
    const remaining = RELEASE_FRAME_BYTES - state.releaseOffset;
    if (chunk.length > remaining) {
      sendError(socket, preparedPlan?.runToken || '', 'release_frame_overflow');
      socket.destroy();
      return;
    }
    chunk.copy(state.releaseBuffer, state.releaseOffset);
    state.releaseOffset += chunk.length;
    if (state.releaseOffset === RELEASE_FRAME_BYTES) {
      handleReleaseFrame(socket, state.releaseBuffer);
    }
    return;
  }
  if (state.mode !== 'json') {
    sendError(socket, '', 'control_data_after_release');
    socket.destroy();
    return;
  }
  state.buffer += Buffer.isBuffer(chunk) ? chunk.toString('utf8') : chunk;
  if (Buffer.byteLength(state.buffer, 'utf8') > MAX_CONTROL_LINE_BYTES) {
    sendError(socket, '', 'control_line_too_large');
    socket.destroy();
    return;
  }
  while (true) {
    const newline = state.buffer.indexOf('\n');
    if (newline < 0) return;
    const line = state.buffer.slice(0, newline).trim();
    state.buffer = state.buffer.slice(newline + 1);
    if (!line) continue;
    let request;
    try {
      request = JSON.parse(line);
    } catch (error) {
      sendError(socket, '', `invalid_json:${error.message}`);
      continue;
    }
    handleControlRequest(socket, request);
  }
}

function handleControlRequest(socket, request) {
  const requestType = String(request?.type || '');
  const runToken = String(request?.runToken || '');
  try {
    if (Number(request?.protocol) !== PROTOCOL_VERSION) {
      throw new Error(`unsupported_protocol:${request?.protocol}`);
    }
    if (!runToken || runToken.length > 160) throw new Error('invalid_run_token');
    if (requestType === 'arm') {
      if (activePlan) throw new Error(`producer_busy:${activePlan.runToken}`);
      if (preparedPlan) throw new Error(`producer_prepared:${preparedPlan.runToken}`);
      if (inputStreamFailure) throw inputStreamFailure;
      socket.ntkState.armedToken = runToken;
      send(socket, { type: 'armed', protocol: PROTOCOL_VERSION, runToken });
      return;
    }
    if (requestType === 'prepare') {
      if (socket.ntkState.armedToken !== runToken) throw new Error('prepare_without_matching_arm');
      if (activePlan) throw new Error(`producer_busy:${activePlan.runToken}`);
      if (preparedPlan) throw new Error(`producer_prepared:${preparedPlan.runToken}`);
      if (inputStreamFailure) throw inputStreamFailure;
      const plan = normalizePlan(request);
      socket.ntkState.armedToken = '';
      preparedPlan = {
        ...plan,
        socket,
        failed: false,
        preparedAckHostNs: 0n,
        writeCountAtPrepare: physicalPlanWriteCount,
        earlyInputCount: 0,
      };
      socket.ntkState.mode = 'release';
      socket.ntkState.releaseBuffer = Buffer.alloc(RELEASE_FRAME_BYTES);
      socket.ntkState.releaseOffset = 0;
      const preparedAckHostNs = process.hrtime.bigint();
      preparedPlan.preparedAckHostNs = preparedAckHostNs;
      const prepared = {
        type: 'prepared',
        protocol: PROTOCOL_VERSION,
        runToken,
        preparedPlanDigest: plan.preparedPlanDigest,
        preparedGestures: plan.gestures,
        preparedEvents: plan.totalEvents,
        preparedAckHostNs: preparedAckHostNs.toString(),
        earlyInputCount: 0,
      };
      log('plan_prepared', prepared);
      if (!send(socket, prepared)) throw new Error('prepared_ack_write_failed');
      return;
    }
    throw new Error(`unknown_request_type:${requestType}`);
  } catch (error) {
    sendError(socket, runToken, error.message || String(error));
  }
}

function handleReleaseFrame(socket, frame) {
  const releaseReceiveHostNs = process.hrtime.bigint();
  try {
    const plan = preparedPlan;
    if (!plan || plan.socket !== socket) throw new Error('release_without_prepared_plan');
    if (frame.readUInt32BE(0) !== RELEASE_MAGIC) throw new Error('release_magic_mismatch');
    if (frame.readUInt32BE(4) !== PROTOCOL_VERSION) throw new Error('release_protocol_mismatch');
    const releaseNonce = frame.readBigUInt64BE(8);
    if (releaseNonce !== plan.releaseNonce) throw new Error('release_nonce_mismatch');
    const activeCommitGuestNs = frame.readBigUInt64BE(16);
    const releaseSendGuestNs = frame.readBigUInt64BE(24);
    if (activeCommitGuestNs === 0n || releaseSendGuestNs < activeCommitGuestNs) {
      throw new Error('release_guest_timestamp_order');
    }
    plan.activeCommitGuestNs = activeCommitGuestNs;
    plan.releaseSendGuestNs = releaseSendGuestNs;
    plan.releaseReceiveHostNs = releaseReceiveHostNs;
    plan.earlyInputCount = physicalPlanWriteCount - plan.writeCountAtPrepare;
    if (plan.earlyInputCount !== 0) throw new Error('input_before_release');
    socket.ntkState.mode = 'released';
    socket.ntkState.releaseBuffer = null;
    preparedPlan = null;
    activePlan = plan;
    void runPlan(plan);
  } catch (error) {
    sendError(socket, preparedPlan?.runToken || '', error.message || String(error));
    if (preparedPlan?.socket === socket) preparedPlan = null;
    socket.destroy();
  }
}

function normalizePlan(request) {
  const directions = Array.isArray(request.directions) ? request.directions.map((value) =>
    integer(value, 'direction', -1, 1)) : [];
  if (directions.length !== STRICT_GESTURES || directions.some((value) => value !== -1 && value !== 1)) {
    throw new Error('strict_direction_plan_mismatch');
  }
  const plan = {
    runToken: String(request.runToken),
    x: integer(request.x, 'x', 0, 100_000),
    startY: integer(request.startY, 'startY', 0, 100_000),
    endY: integer(request.endY, 'endY', 0, 100_000),
    gestures: integer(request.gestures, 'gestures', STRICT_GESTURES, STRICT_GESTURES),
    steps: integer(request.steps ?? STRICT_STEPS, 'steps', STRICT_STEPS, STRICT_STEPS),
    sampleIntervalMs: integer(request.sampleIntervalMs ?? DEFAULT_SAMPLE_INTERVAL_MS,
      'sampleIntervalMs', 1, 1_000),
    gestureGapMs: integer(request.gestureGapMs ?? DEFAULT_GESTURE_GAP_MS,
      'gestureGapMs', 0, 10_000),
    maxLatenessMs: integer(request.maxLatenessMs ?? DEFAULT_MAX_LATENESS_MS,
      'maxLatenessMs', 1, DEFAULT_MAX_LATENESS_MS),
    display: integer(request.display ?? 0, 'display', 0, 32),
    directions,
  };
  let releaseNonce;
  try {
    releaseNonce = BigInt(String(request.releaseNonce || ''));
  } catch {
    throw new Error('invalid_release_nonce');
  }
  if (releaseNonce <= 0n || releaseNonce > 0x7fff_ffff_ffff_ffffn) {
    throw new Error('invalid_release_nonce');
  }
  plan.releaseNonce = releaseNonce;
  plan.gesturePeriodMs = plan.sampleIntervalMs * plan.steps + plan.gestureGapMs;
  plan.totalEvents = plan.gestures * (plan.steps + 1);
  if (plan.sampleIntervalMs !== DEFAULT_SAMPLE_INTERVAL_MS
      || plan.gestureGapMs !== DEFAULT_GESTURE_GAP_MS
      || plan.totalEvents !== STRICT_TOTAL_EVENTS) {
    throw new Error(`strict_plan_mismatch:${JSON.stringify(plan)}`);
  }
  plan.preparedPlanDigest = planDigest(plan);
  if (String(request.guestPlanDigest || '') !== plan.preparedPlanDigest) {
    throw new Error('guest_plan_digest_mismatch');
  }
  plan.preparedSchedule = buildPreparedSchedule(plan);
  if (plan.preparedSchedule.length !== STRICT_TOTAL_EVENTS) {
    throw new Error(`prepared_event_conservation:${plan.preparedSchedule.length}`);
  }
  return plan;
}

function planDigest(plan) {
  const canonical = `v=2;x=${plan.x};startY=${plan.startY};endY=${plan.endY}`
    + `;gestures=${plan.gestures};steps=${plan.steps}`
    + `;sampleIntervalMs=${plan.sampleIntervalMs};gestureGapMs=${plan.gestureGapMs}`
    + `;maxLatenessMs=${plan.maxLatenessMs};display=${plan.display}`
    + `;directions=${plan.directions.join(',')}`;
  return crypto.createHash('sha256').update(canonical, 'utf8').digest('hex');
}

function buildPreparedSchedule(plan) {
  const schedule = new Array(plan.totalEvents);
  let sequence = 0;
  for (let gesture = 0; gesture < plan.gestures; gesture += 1) {
    const reverse = plan.directions[gesture] < 0;
    const gestureStartY = reverse ? plan.endY : plan.startY;
    const gestureEndY = reverse ? plan.startY : plan.endY;
    const gestureOffsetMs = gesture * plan.gesturePeriodMs;
    for (let sample = 0; sample <= plan.steps; sample += 1) {
      const isUp = sample === plan.steps;
      const fraction = sample / plan.steps;
      const y = isUp
        ? gestureEndY
        : Math.round(gestureStartY + (gestureEndY - gestureStartY) * fraction);
      const action = sample === 0 ? 'DOWN' : (isUp ? 'UP' : 'MOVE');
      const request = {
        touch_event: {
          touches: [{
            x: plan.x,
            y,
            identifier: 0,
            pressure: isUp ? 0 : 1024,
            touch_major: isUp ? 0 : 1,
            touch_minor: isUp ? 0 : 1,
            expiration: 1,
            orientation: 0,
          }],
          display: plan.display,
        },
      };
      schedule[sequence] = {
        sequence: sequence + 1,
        gesture,
        sample,
        action,
        isUp,
        y,
        targetOffsetNs: BigInt(gestureOffsetMs + sample * plan.sampleIntervalMs) * NS_PER_MS,
        request,
      };
      sequence += 1;
    }
  }
  return schedule;
}

async function runPlan(plan) {
  const originNs = plan.releaseReceiveHostNs;
  let maxLatenessNs = 0n;
  let maxWriteNs = 0n;
  let queuedEvents = 0;
  let firstDownHostNs = 0n;
  let lastUpHostNs = 0n;
  let releaseToFirstDownMicros = 0;
  try {
    for (const preparedEvent of plan.preparedSchedule) {
        if (inputStreamFailure) throw inputStreamFailure;
        const targetNs = originNs + preparedEvent.targetOffsetNs;
        if (preparedEvent.targetOffsetNs > 0n) {
        await waitUntil(targetNs);
        }
        const injectionStartedNs = process.hrtime.bigint();
        const latenessNs = injectionStartedNs > targetNs ? injectionStartedNs - targetNs : 0n;
        if (latenessNs > maxLatenessNs) maxLatenessNs = latenessNs;
        const latenessMs = ceilMilliseconds(latenessNs);
        if (latenessMs > plan.maxLatenessMs) {
          throw new Error(`host_cadence_late:gesture=${preparedEvent.gesture},sample=${preparedEvent.sample},`
            + `latenessMs=${latenessMs},maxMs=${plan.maxLatenessMs}`);
        }

        const writeStartedNs = process.hrtime.bigint();
        const queued = writePreparedTouch(preparedEvent.request);
        const writeNs = process.hrtime.bigint() - writeStartedNs;
        if (writeNs > maxWriteNs) maxWriteNs = writeNs;
        if (!queued) {
          throw new Error(`grpc_backpressure:gesture=${preparedEvent.gesture},sample=${preparedEvent.sample}`);
        }
        if (preparedEvent.sequence === 1) {
          firstDownHostNs = writeStartedNs;
          releaseToFirstDownMicros = ceilMicroseconds(
            firstDownHostNs - plan.releaseReceiveHostNs);
          send(plan.socket, {
            type: 'started',
            protocol: PROTOCOL_VERSION,
            runToken: plan.runToken,
            preparedPlanDigest: plan.preparedPlanDigest,
            releaseReceiveHostNs: plan.releaseReceiveHostNs.toString(),
            firstDownHostNs: firstDownHostNs.toString(),
            releaseToFirstDownMicros,
            earlyInputCount: plan.earlyInputCount,
          });
          log('plan_released', {
            protocol: PROTOCOL_VERSION,
            runToken: plan.runToken,
            preparedPlanDigest: plan.preparedPlanDigest,
            releaseReceiveHostNs: plan.releaseReceiveHostNs.toString(),
            activeCommitGuestNs: plan.activeCommitGuestNs.toString(),
            releaseSendGuestNs: plan.releaseSendGuestNs.toString(),
            earlyInputCount: plan.earlyInputCount,
          });
          log('plan_started', {
            protocol: PROTOCOL_VERSION,
            runToken: plan.runToken,
            preparedPlanDigest: plan.preparedPlanDigest,
            releaseReceiveHostNs: plan.releaseReceiveHostNs.toString(),
            firstDownHostNs: firstDownHostNs.toString(),
            releaseToFirstDownMicros,
            earlyInputCount: plan.earlyInputCount,
          });
        }
        log('input_write', {
          protocol: PROTOCOL_VERSION,
          runToken: plan.runToken,
          preparedPlanDigest: plan.preparedPlanDigest,
          sequence: preparedEvent.sequence,
          hostHrtimeNs: writeStartedNs.toString(),
          gesture: preparedEvent.gesture,
          sample: preparedEvent.sample,
          action: preparedEvent.action,
        });
        activeTouch = preparedEvent.isUp
          ? null : { x: plan.x, y: preparedEvent.y, display: plan.display };
        queuedEvents += 1;
        if (preparedEvent.isUp) lastUpHostNs = writeStartedNs;
    }

    await finishInputStream(3_000);
    const result = {
      type: 'result',
      protocol: PROTOCOL_VERSION,
      ok: true,
      runToken: plan.runToken,
      preparedPlanDigest: plan.preparedPlanDigest,
      preparedGestures: plan.gestures,
      preparedEvents: plan.totalEvents,
      preparedAckHostNs: plan.preparedAckHostNs.toString(),
      releaseReceiveHostNs: plan.releaseReceiveHostNs.toString(),
      releaseToFirstDownMicros,
      earlyInputCount: plan.earlyInputCount,
      gestures: plan.gestures,
      steps: plan.steps,
      queuedEvents,
      totalEvents: plan.totalEvents,
      sampleIntervalMs: plan.sampleIntervalMs,
      gestureGapMs: plan.gestureGapMs,
      reverseGestures: plan.directions.filter((value) => value < 0).length,
      maxLatenessMs: ceilMilliseconds(maxLatenessNs),
      maxQueueWriteMicros: ceilMicroseconds(maxWriteNs),
      firstDownHostNs: firstDownHostNs.toString(),
      lastUpHostNs: lastUpHostNs.toString(),
    };
    send(plan.socket, result);
    log('plan_complete', result);
    openInputStream();
  } catch (error) {
    bestEffortLift();
    try {
      await finishInputStream(1_000);
    } catch (flushError) {
      log('failed_plan_stream_flush_error', {
        runToken: plan.runToken,
        message: flushError?.message || String(flushError),
      });
    }
    const result = {
      type: 'result',
      protocol: PROTOCOL_VERSION,
      ok: false,
      runToken: plan.runToken,
      preparedPlanDigest: plan.preparedPlanDigest,
      preparedGestures: plan.gestures,
      preparedEvents: plan.totalEvents,
      preparedAckHostNs: plan.preparedAckHostNs.toString(),
      releaseReceiveHostNs: plan.releaseReceiveHostNs.toString(),
      releaseToFirstDownMicros,
      earlyInputCount: plan.earlyInputCount,
      error: error.message || String(error),
      queuedEvents,
      totalEvents: plan.totalEvents,
      maxLatenessMs: ceilMilliseconds(maxLatenessNs),
      maxQueueWriteMicros: ceilMicroseconds(maxWriteNs),
    };
    send(plan.socket, result);
    log('plan_failed', result);
    if (!shuttingDown) openInputStream();
  } finally {
    activeTouch = null;
    activePlan = null;
  }
}

async function finishInputStream(timeoutMs) {
  const stream = inputStream;
  const completion = inputStreamCompletion;
  inputStream = null;
  inputStreamCompletion = null;
  if (!stream || !completion) throw new Error('grpc_input_stream_unavailable_at_finish');
  stream.end();
  let timeoutId;
  try {
    await Promise.race([
      completion,
      new Promise((_, reject) => {
        timeoutId = setTimeout(() => reject(new Error('grpc_input_stream_flush_timeout')),
          timeoutMs);
      }),
    ]);
  } finally {
    if (timeoutId) clearTimeout(timeoutId);
  }
}

async function waitUntil(targetNs) {
  while (true) {
    const remainingNs = targetNs - process.hrtime.bigint();
    if (remainingNs <= 0n) return;
    if (remainingNs > 2_000_000n) {
      const sleepMs = Math.max(1, Number((remainingNs - 1_000_000n) / NS_PER_MS));
      await new Promise((resolve) => setTimeout(resolve, sleepMs));
      continue;
    }
    if (remainingNs > 300_000n) {
      await new Promise((resolve) => setImmediate(resolve));
      continue;
    }
    while (process.hrtime.bigint() < targetNs) {
      // A sub-0.3ms spin avoids rebasing or pre-submitting a future physical sample.
    }
    return;
  }
}

function writePreparedTouch(request) {
  if (!inputStream || inputStreamFailure) {
    throw inputStreamFailure || new Error('grpc_input_stream_unavailable');
  }
  const accepted = inputStream.write(request);
  if (accepted) physicalPlanWriteCount += 1;
  return accepted;
}

// A fail-fast runner may terminate the previous helper while its physical contact is still down.
// The emulator keeps that touchscreen state beyond the gRPC client's lifetime, so the next
// helper's first non-zero-pressure sample would be classified as MOVE instead of DOWN. Clear that
// cross-run device state before advertising readiness; the real timed plan starts much later on
// the same stream and remains exactly DOWN..MOVE..UP.
async function resetTouchscreenState() {
  let resolveCompletion;
  let rejectCompletion;
  const completion = new Promise((resolve, reject) => {
    resolveCompletion = resolve;
    rejectCompletion = reject;
  });
  const resetStream = client.streamInputEvent(metadata, (error) => {
    if (error) rejectCompletion(error);
    else resolveCompletion();
  });
  resetStream.on('error', rejectCompletion);
  const queued = resetStream.write({
    touch_event: {
      touches: [{
        x: 0, y: 0, identifier: 0, pressure: 0,
        touch_major: 0, touch_minor: 0, expiration: 1, orientation: 0,
      }],
      display: 0,
    },
  });
  if (!queued) throw new Error('grpc_touchscreen_reset_backpressure');
  resetStream.end();
  await Promise.race([
    completion,
    new Promise((_, reject) => setTimeout(
      () => reject(new Error('grpc_touchscreen_reset_timeout')), 3_000)),
  ]);
  log('touchscreen_reset_complete', { display: 0 });
}

function bestEffortLift() {
  const touch = activeTouch;
  activeTouch = null;
  if (!touch || !inputStream || inputStreamFailure) return;
  try {
    inputStream.write({
      touch_event: {
        touches: [{
          x: touch.x,
          y: touch.y,
          identifier: 0,
          pressure: 0,
          touch_major: 0,
          touch_minor: 0,
          expiration: 1,
          orientation: 0,
        }],
        display: touch.display,
      },
    });
  } catch {
    // The original strict producer error remains authoritative.
  }
}

function failActivePlan(error) {
  if (!activePlan || activePlan.failed) return;
  activePlan.failed = true;
  inputStreamFailure = error;
}

function ceilMilliseconds(nanoseconds) {
  return Number((nanoseconds + NS_PER_MS - 1n) / NS_PER_MS);
}

function ceilMicroseconds(nanoseconds) {
  return Number((nanoseconds + 999n) / 1_000n);
}

function send(socket, value) {
  if (!socket || socket.destroyed) return false;
  return socket.write(`${JSON.stringify(value)}\n`);
}

function sendError(socket, runToken, message) {
  send(socket, {
    type: 'error',
    protocol: PROTOCOL_VERSION,
    runToken,
    error: message,
  });
}

function log(event, fields = {}) {
  process.stdout.write(`${JSON.stringify({ event, at: new Date().toISOString(), ...fields })}\n`);
}

function fatal(event, error) {
  log(event, { message: error?.message || String(error), stack: error?.stack || '' });
  void shutdown(1);
}

async function shutdown(exitCode) {
  if (shuttingDown) return;
  shuttingDown = true;
  bestEffortLift();
  for (const socket of sockets) socket.destroy();
  await new Promise((resolve) => server.close(resolve));
  if (inputStream) inputStream.end();
  client.close();
  process.exitCode = exitCode;
}
