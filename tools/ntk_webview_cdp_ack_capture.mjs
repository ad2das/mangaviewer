#!/usr/bin/env node

import { spawn, spawnSync } from "node:child_process";
import { mkdirSync, writeFileSync, appendFileSync, readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");

function parseArgs(argv) {
  const opts = {
    serial: "emulator-5556",
    timeoutMs: 70000,
    port: 9222,
    urlMatches: ["/api/ad/ack"],
    successMatch: "",
    targetPath: "/webtoon/17332/1515337",
    siteRoot: "https://sbxh9.com",
    packageName: "ml.melun.mangaview",
    outDir: "",
    runProbe: false,
    maxMs: 65000,
    skipBuild: true,
    skipInstall: true,
    forceStopBeforeRun: true,
    probeStartDelayMs: 0,
    stopWhenProbeExits: true,
    probeExitGraceMs: 3000,
    cdpHttpTimeoutMs: 700,
    adbCommandTimeoutMs: 5000,
    stopOnRootStageTimeout: false,
    stopOnRootTerminalFailure: true,
    allowExistingPidTarget: false,
    initialPids: [],
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    const next = () => {
      if (i + 1 >= argv.length) throw new Error(`Missing value for ${arg}`);
      i += 1;
      return argv[i];
    };
    if (arg === "--serial" || arg === "-s") opts.serial = next();
    else if (arg === "--timeout-ms") opts.timeoutMs = Number(next());
    else if (arg === "--port") opts.port = Number(next());
    else if (arg === "--url-match") opts.urlMatches.push(next());
    else if (arg === "--success-match") opts.successMatch = next();
    else if (arg === "--target-path") opts.targetPath = next();
    else if (arg === "--site-root") opts.siteRoot = next();
    else if (arg === "--package") opts.packageName = next();
    else if (arg === "--out-dir") opts.outDir = next();
    else if (arg === "--run-probe") opts.runProbe = true;
    else if (arg === "--max-ms") opts.maxMs = Number(next());
    else if (arg === "--probe-start-delay-ms") opts.probeStartDelayMs = Number(next());
    else if (arg === "--probe-exit-grace-ms") opts.probeExitGraceMs = Number(next());
    else if (arg === "--cdp-http-timeout-ms") opts.cdpHttpTimeoutMs = Number(next());
    else if (arg === "--adb-command-timeout-ms") opts.adbCommandTimeoutMs = Number(next());
    else if (arg === "--keep-capturing-after-probe-exit") opts.stopWhenProbeExits = false;
    else if (arg === "--stop-on-root-stage-timeout") opts.stopOnRootStageTimeout = true;
    else if (arg === "--allow-root-terminal-failure") opts.stopOnRootTerminalFailure = false;
    else if (arg === "--allow-existing-pid-target") opts.allowExistingPidTarget = true;
    else if (arg === "--build") opts.skipBuild = false;
    else if (arg === "--install") opts.skipInstall = false;
    else if (arg === "--no-force-stop") opts.forceStopBeforeRun = false;
    else if (arg === "--help" || arg === "-h") {
      printHelp();
      process.exit(0);
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }
  if (opts.urlMatches.length > 1 && opts.urlMatches[0] === "/api/ad/ack") {
    opts.urlMatches = opts.urlMatches.slice(1);
  }
  if (opts.urlMatches.length === 0) opts.urlMatches = ["/api/ad/ack"];
  if (!opts.successMatch) opts.successMatch = opts.urlMatches[0];
  return opts;
}

function printHelp() {
  process.stdout.write(`Usage:
  node tools/ntk_webview_cdp_ack_capture.mjs [options]

Options:
  --serial <adb serial>          Default: emulator-5556
  --timeout-ms <ms>              Capture timeout. Default: 70000
  --port <port>                  Local CDP forward port. Default: 9222
  --url-match <text>             URL substring to capture. Repeatable. Default: /api/ad/ack
  --success-match <text>         Match substring that must return HTTP 200 for exit 0.
                                  Default: first --url-match value
  --out-dir <dir>                Artifact dir. Default: build/ntk-cdp-ack/<timestamp>
  --run-probe                    Start tools/ntk_ack_ux_probe.ps1 and capture in parallel
  --site-root <url>              Probe site root / CDP target hint. Default: https://sbxh9.com
  --target-path <path>           Probe target path. Default: /webtoon/17332/1515337
  --package <name>               Android app package. Default: ml.melun.mangaview
  --max-ms <ms>                  Probe max ms when --run-probe is set. Default: 65000
  --probe-start-delay-ms <ms>    Delay after starting probe before CDP attach. Default: 0
  --probe-exit-grace-ms <ms>     Extra capture time after probe exits. Default: 3000
  --cdp-http-timeout-ms <ms>     Timeout for each CDP /json request. Default: 700
  --adb-command-timeout-ms <ms>  Timeout for helper adb commands. Default: 5000
  --keep-capturing-after-probe-exit
                                  Keep CDP capture alive until --timeout-ms even if probe exits
  --stop-on-root-stage-timeout    Pass -StopOnRootStageTimeout to the probe
  --allow-root-terminal-failure   Do not pass -StopOnRootTerminalFailure to the probe
  --allow-existing-pid-target     Allow attaching to app PIDs that existed before --run-probe
  --build                        Let probe build APKs when --run-probe is set
  --install                      Let probe install APKs when --run-probe is set
  --no-force-stop                Do not force-stop before probe when --run-probe is set
`);
}

function nowStamp() {
  const d = new Date();
  const pad = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}_${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}_${Math.random().toString(16).slice(2, 8)}`;
}

function run(cmd, args, opts = {}) {
  const result = spawnSync(cmd, args, {
    cwd: repoRoot,
    encoding: "utf8",
    timeout: 10000,
    windowsHide: true,
    ...opts,
  });
  return {
    code: result.status ?? 1,
    stdout: result.stdout ?? "",
    stderr: result.stderr ?? "",
    error: result.error ? String(result.error.message || result.error) : "",
  };
}

function append(path, line) {
  appendFileSync(path, `${new Date().toISOString()} ${line}\n`, "utf8");
}

function discoverSockets(serial, timeoutMs = 5000) {
  const result = run("adb", ["-s", serial, "shell", "cat", "/proc/net/unix"], { timeout: timeoutMs });
  if (result.code !== 0) {
    return { sockets: [], raw: result.stdout + result.stderr + result.error, error: `adb socket discovery failed: ${result.code}` };
  }
  const sockets = [];
  const re = /@?(webview_devtools_remote(?:_\d+)?)/g;
  let match;
  while ((match = re.exec(result.stdout)) !== null) {
    if (!sockets.includes(match[1])) sockets.push(match[1]);
  }
  return { sockets, raw: result.stdout, error: "" };
}

function appPids(serial, packageName, timeoutMs = 5000) {
  const result = run("adb", ["-s", serial, "shell", "pidof", packageName], { timeout: timeoutMs });
  let stdout = result.stdout || "";
  if (result.code !== 0 || !stdout.trim()) {
    const ps = run("adb", ["-s", serial, "shell", "ps", "-A"], { timeout: timeoutMs });
    if (ps.code !== 0) return [];
    const escaped = packageName.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    const exactPackage = new RegExp(`\\s${escaped}\\s*$`);
    stdout = ps.stdout
      .split(/\r?\n/)
      .filter((line) => exactPackage.test(line))
      .map((line) => line.trim().split(/\s+/)[1] || "")
      .join(" ");
  }
  return stdout
    .trim()
    .split(/\s+/)
    .map((pid) => pid.trim())
    .filter(Boolean);
}

async function getJson(port, path, timeoutMs) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetch(`http://127.0.0.1:${port}${path}`, {
      cache: "no-store",
      signal: controller.signal,
    });
    if (!res.ok) throw new Error(`GET ${path} returned HTTP ${res.status}`);
    return await res.json();
  } finally {
    clearTimeout(timer);
  }
}

async function getDevToolsTargets(port, timeoutMs) {
  try {
    const version = await getJson(port, "/json/version", timeoutMs);
    if (!version || typeof version !== "object") throw new Error("empty /json/version response");
  } catch (error) {
    throw new Error(`/json/version unavailable: ${error.message}`);
  }
  try {
    const list = await getJson(port, "/json/list", timeoutMs);
    if (Array.isArray(list) && list.length > 0) return list;
  } catch {}
  const targets = await getJson(port, "/json", timeoutMs);
  return Array.isArray(targets) ? targets : [];
}

function chooseTarget(targets, siteRoot) {
  const host = siteRoot.replace(/^https?:\/\//, "").split("/")[0];
  const withSocket = targets.filter((t) => t.webSocketDebuggerUrl || t.id);
  return (
    withSocket.find((t) => String(t.url || "").includes(host) && String(t.type || "").toLowerCase() === "page") ||
    withSocket.find((t) => String(t.url || "").includes(host)) ||
    withSocket.find((t) => String(t.type || "").toLowerCase() === "page") ||
    withSocket[0]
  );
}

function startProbe(opts, logPath) {
  const powershell = run("pwsh", ["-NoProfile", "-Command", "$PSVersionTable.PSVersion.ToString()"]).code === 0
    ? "pwsh"
    : "powershell";
  const args = [
    "-NoProfile",
    "-ExecutionPolicy",
    "Bypass",
    "-File",
    "tools\\ntk_ack_ux_probe.ps1",
    "-DeviceSerial",
    opts.serial,
    "-SiteRoot",
    opts.siteRoot,
    "-TargetEpisodePath",
    opts.targetPath,
    "-MaxMs",
    String(opts.maxMs),
    "-EnableWebViewDebuggingForDiagnostics",
    "-NoForceStopAfterRun",
  ];
  if (opts.stopOnRootTerminalFailure) args.push("-StopOnRootTerminalFailure");
  if (opts.stopOnRootStageTimeout) args.push("-StopOnRootStageTimeout");
  if (opts.skipBuild) args.push("-SkipBuild");
  if (opts.skipInstall) args.push("-SkipInstall");
  if (opts.forceStopBeforeRun) args.push("-ForceStopBeforeRun");
  append(logPath, `starting probe: ${powershell} ${args.join(" ")}`);
  const child = spawn(powershell, args, {
    cwd: repoRoot,
    stdio: ["ignore", "pipe", "pipe"],
    windowsHide: true,
  });
  child.stdout.on("data", (chunk) => appendFileSync(logPath, chunk));
  child.stderr.on("data", (chunk) => appendFileSync(logPath, chunk));
  return child;
}

async function sleep(ms) {
  await new Promise((resolveSleep) => setTimeout(resolveSleep, ms));
}

function makeProbeExitWatcher(probe, opts, logPath) {
  let probeExitSeenAt = 0;
  return () => {
    if (!probe || !opts.stopWhenProbeExits || probe.exitCode === null) return "";
    if (probeExitSeenAt === 0) {
      probeExitSeenAt = Date.now();
      append(logPath, `probe exited with code ${probe.exitCode}; waiting ${opts.probeExitGraceMs}ms grace before stopping CDP capture`);
    }
    if (Date.now() - probeExitSeenAt >= opts.probeExitGraceMs) {
      return `probe exited with code ${probe.exitCode}`;
    }
    return "";
  };
}

async function waitForTarget(opts, logPath, deadlineMs, shouldStop = () => "") {
  let lastError = "";
  const historyPath = resolve(opts.outDir, "cdp_targets_history.jsonl");
  while (Date.now() < deadlineMs) {
    const stopReason = shouldStop();
    if (stopReason) throw new Error(`Stopped before CDP target became available: ${stopReason}`);
    const discovered = discoverSockets(opts.serial, opts.adbCommandTimeoutMs);
    const rawPids = appPids(opts.serial, opts.packageName, opts.adbCommandTimeoutMs);
    const pids = opts.runProbe && !opts.allowExistingPidTarget
      ? rawPids.filter((pid) => !opts.initialPids.includes(pid))
      : rawPids;
    writeFileSync(resolve(opts.outDir, "webview_debug_sockets.txt"), discovered.raw || discovered.error || "NO_OUTPUT", "utf8");
    if (discovered.error) {
      lastError = discovered.error;
      append(logPath, lastError);
      await sleep(750);
      continue;
    }
    const pidSockets = pids.map((pid) => `webview_devtools_remote_${pid}`).filter((socket) => discovered.sockets.includes(socket));
    const recentSockets = discovered.sockets
      .filter((socket) => socket !== "webview_devtools_remote_1542")
      .slice(-4)
      .reverse();
    const candidates = pids.length > 0 ? pidSockets : [...new Set(recentSockets)];
    if (rawPids.length > 0) {
      append(logPath, `app pids=${rawPids.join(",")} usablePids=${pids.join(",") || "none"} candidateSockets=${candidates.join(",") || "none"}`);
    }
    if (opts.runProbe && !opts.allowExistingPidTarget && rawPids.length > 0 && pids.length === 0) {
      lastError = `waiting for a fresh app pid; ignoring pre-existing pid(s): ${opts.initialPids.join(",") || "none"}`;
      append(logPath, lastError);
      await sleep(500);
      continue;
    }
    if (pids.length > 0 && candidates.length === 0) {
      lastError = `app pid(s) exist but no matching WebView DevTools socket yet: ${pids.join(",")}`;
      append(logPath, lastError);
      await sleep(350);
      continue;
    }
    for (const socket of candidates) {
      run("adb", ["-s", opts.serial, "forward", "--remove", `tcp:${opts.port}`], { timeout: opts.adbCommandTimeoutMs });
      const forward = run("adb", ["-s", opts.serial, "forward", `tcp:${opts.port}`, `localabstract:${socket}`], { timeout: opts.adbCommandTimeoutMs });
      if (forward.code !== 0) {
        lastError = `forward failed for ${socket}: ${forward.stderr || forward.stdout || forward.error}`;
        append(logPath, lastError);
        continue;
      }
      try {
        const targets = await getDevToolsTargets(opts.port, opts.cdpHttpTimeoutMs);
        appendFileSync(historyPath, `${JSON.stringify({ time: new Date().toISOString(), socket, targets })}\n`, "utf8");
        writeFileSync(resolve(opts.outDir, "cdp_targets.json"), JSON.stringify(targets, null, 2), "utf8");
        const target = chooseTarget(targets, opts.siteRoot);
        if (target?.webSocketDebuggerUrl || target?.id) {
          if (!target.webSocketDebuggerUrl && target.id) {
            target.webSocketDebuggerUrl = `ws://127.0.0.1:${opts.port}/devtools/page/${target.id}`;
          }
          append(logPath, `selected socket=${socket} target=${target.id || ""} url=${target.url || ""}`);
          return { socket, target };
        }
        lastError = `no websocket target on socket ${socket}`;
        append(logPath, lastError);
      } catch (error) {
        lastError = `target discovery failed on ${socket}: ${error.message}`;
        append(logPath, lastError);
      }
    }
    await sleep(750);
  }
  throw new Error(lastError || "No WebView DevTools target found before timeout");
}

async function captureNetwork(wsUrl, opts, logPath, deadlineMs, shouldStop = () => "") {
  const eventsPath = resolve(opts.outDir, "cdp_network_events.jsonl");
  const matchesPath = resolve(opts.outDir, "ack_matches.jsonl");
  const requestsPath = resolve(opts.outDir, "ack_requests.jsonl");
  let nextId = 1;
  let opened = false;
  let matched = false;
  const pending = new Map();
  const requests = new Map();
  const matches = [];
  const matchedRequests = [];

  const ws = new WebSocket(wsUrl);
  const urlMatchFor = (url) => opts.urlMatches.find((matchText) => String(url || "").includes(matchText)) || "";
  const send = (method, params = {}) => {
    const id = nextId++;
    ws.send(JSON.stringify({ id, method, params }));
    pending.set(id, method);
    return id;
  };

  await new Promise((resolveOpen, rejectOpen) => {
    const timer = setTimeout(() => rejectOpen(new Error("CDP websocket open timeout")), 5000);
    ws.addEventListener("open", () => {
      clearTimeout(timer);
      opened = true;
      resolveOpen();
    });
    ws.addEventListener("error", () => {
      clearTimeout(timer);
      rejectOpen(new Error("CDP websocket error before open"));
    });
  });
  append(logPath, `CDP websocket opened: ${wsUrl}`);

  ws.addEventListener("message", (event) => {
    const text = String(event.data);
    appendFileSync(eventsPath, `${text}\n`, "utf8");
    let msg;
    try {
      msg = JSON.parse(text);
    } catch {
      return;
    }
    if (msg.id) {
      pending.delete(msg.id);
      return;
    }
    if (msg.method === "Network.requestWillBeSent") {
      const requestId = msg.params?.requestId;
      const request = msg.params?.request || {};
      if (requestId) {
        requests.set(requestId, {
          url: request.url || "",
          method: request.method || "",
          postData: request.postData || "",
          timestamp: msg.params?.timestamp,
        });
        const matchText = urlMatchFor(request.url);
        if (matchText) {
          const record = {
            time: new Date().toISOString(),
            event: "Network.requestWillBeSent",
            urlMatch: matchText,
            requestId,
            url: request.url || "",
            method: request.method || "",
            hasPostData: Boolean(request.hasPostData || request.postData),
            postDataPrefix: request.postData ? String(request.postData).slice(0, 500) : "",
          };
          matchedRequests.push(record);
          appendFileSync(requestsPath, `${JSON.stringify(record)}\n`, "utf8");
          append(logPath, `matched request method=${record.method} match=${matchText} url=${record.url}`);
        }
      }
    }
    if (msg.method === "Network.responseReceived") {
      const response = msg.params?.response || {};
      const url = response.url || requests.get(msg.params?.requestId)?.url || "";
      const matchText = urlMatchFor(url);
      if (matchText) {
        const request = requests.get(msg.params?.requestId) || {};
        const record = {
          time: new Date().toISOString(),
          event: "Network.responseReceived",
          urlMatch: matchText,
          requestId: msg.params?.requestId,
          url,
          method: request.method || "",
          status: response.status,
          statusText: response.statusText,
          mimeType: response.mimeType,
          fromDiskCache: response.fromDiskCache,
          fromServiceWorker: response.fromServiceWorker,
          protocol: response.protocol,
        };
        matched = true;
        matches.push(record);
        appendFileSync(matchesPath, `${JSON.stringify(record)}\n`, "utf8");
        append(logPath, `matched response status=${record.status} method=${record.method} match=${matchText} url=${url}`);
      }
    }
    if (msg.method === "Network.loadingFailed") {
      const request = requests.get(msg.params?.requestId) || {};
      const matchText = urlMatchFor(request.url);
      if (matchText) {
        const record = {
          time: new Date().toISOString(),
          event: "Network.loadingFailed",
          urlMatch: matchText,
          requestId: msg.params?.requestId,
          url: request.url,
          method: request.method || "",
          errorText: msg.params?.errorText,
          blockedReason: msg.params?.blockedReason,
          canceled: msg.params?.canceled,
        };
        appendFileSync(matchesPath, `${JSON.stringify(record)}\n`, "utf8");
        append(logPath, `matched request failed error=${record.errorText || ""} blocked=${record.blockedReason || ""} match=${matchText} url=${record.url}`);
      }
    }
  });

  send("Network.enable", { maxTotalBufferSize: 10000000, maxResourceBufferSize: 5000000 });
  send("Page.enable");
  append(logPath, "Network.enable sent");

  while (Date.now() < deadlineMs) {
    if (matched && matches.some((m) => m.urlMatch === opts.successMatch && Number(m.status) === 200)) break;
    const stopReason = shouldStop();
    if (stopReason) {
      append(logPath, `stopping CDP capture: ${stopReason}`);
      break;
    }
    await sleep(500);
  }
  if (opened) {
    try {
      ws.close();
    } catch {}
  }
  return { matches, matchedRequests };
}

function summarizeByMatch(urlMatches, records, statusRecords = []) {
  return Object.fromEntries(urlMatches.map((matchText) => {
    const requests = records.filter((m) => m.urlMatch === matchText);
    const responses = statusRecords.filter((m) => m.urlMatch === matchText);
    return [matchText, {
      requestCount: requests.length,
      responseCount: responses.length,
      methods: [...new Set(requests.map((m) => m.method).filter(Boolean))],
      statuses: responses.map((m) => m.status).filter((v) => v !== undefined),
      success: responses.some((m) => Number(m.status) === 200),
    }];
  }));
}

function explainFailure(opts, perMatch) {
  const success = perMatch[opts.successMatch];
  if (!success) return `No records matched required success URL substring: ${opts.successMatch}`;
  if (success.responseCount === 0 && success.requestCount === 0) {
    return `No CDP request or response was observed for required success URL substring: ${opts.successMatch}`;
  }
  if (success.responseCount === 0) {
    return `CDP observed request(s) for ${opts.successMatch}, but no Network.responseReceived status before timeout`;
  }
  return `CDP observed ${opts.successMatch}, but no HTTP 200 status before timeout`;
}

function extractProbeResult(probeLogPath) {
  let text = "";
  try {
    text = readFileSync(probeLogPath, "utf8");
  } catch {
    return {};
  }
  const lines = text.split(/\r?\n/);
  const valueFor = (name) => {
    for (let i = 0; i < lines.length - 1; i += 1) {
      const nameMatch = lines[i].match(/^\s*Name\s*:\s*(.*?)\s*$/i);
      if (!nameMatch || nameMatch[1] !== name) continue;
      const valueMatch = lines[i + 1].match(/^\s*Value\s*:\s*(.*?)\s*$/i);
      return valueMatch ? valueMatch[1].trim() : "";
    }
    return "";
  };
  const runDir = valueFor("runDir");
  return {
    probeRunDir: runDir,
    probeSummaryPath: runDir ? `${runDir}\\summary.json` : "",
    probeStoppedAfterMarker: valueFor("stoppedAfterMarker"),
    probeRootStageTimeout: valueFor("rootStageTimeout"),
    probeRootStart: valueFor("rootStart"),
    probeInstrumentationTimedOut: valueFor("instrumentationTimedOut"),
    probeExitedWithoutMarker: valueFor("exitedWithoutMarker"),
    probeInstrumentationCrashed: valueFor("instrumentationCrashed"),
    probeSystemAnr: valueFor("systemAnr"),
  };
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (!opts.outDir) {
    opts.outDir = resolve(repoRoot, "build", "ntk-cdp-ack", nowStamp());
  } else {
    opts.outDir = resolve(repoRoot, opts.outDir);
  }
  mkdirSync(opts.outDir, { recursive: true });
  const logPath = resolve(opts.outDir, "capture.log");
  writeFileSync(resolve(opts.outDir, "metadata.json"), JSON.stringify(opts, null, 2), "utf8");
  append(logPath, `capture starting outDir=${opts.outDir}`);

  const deadlineMs = Date.now() + opts.timeoutMs;
  let probe = null;
  const probeLogPath = resolve(opts.outDir, "probe.log");
  if (opts.runProbe) {
    opts.initialPids = appPids(opts.serial, opts.packageName, opts.adbCommandTimeoutMs);
    if (opts.initialPids.length > 0 && !opts.allowExistingPidTarget) {
      append(logPath, `pre-existing app pid(s) will be ignored for this probe: ${opts.initialPids.join(",")}`);
    }
    if (opts.forceStopBeforeRun) {
      append(logPath, `pre-clearing app and forwarded CDP port for ${opts.packageName}`);
      run("adb", ["-s", opts.serial, "shell", "am", "force-stop", opts.packageName], { timeout: opts.adbCommandTimeoutMs });
      run("adb", ["-s", opts.serial, "forward", "--remove", `tcp:${opts.port}`], { timeout: opts.adbCommandTimeoutMs });
      await sleep(600);
    }
    probe = startProbe(opts, probeLogPath);
    await sleep(Math.max(0, opts.probeStartDelayMs));
  }

  let exitCode = 2;
  let summary;
  const shouldStopForProbeExit = makeProbeExitWatcher(probe, opts, logPath);
  try {
    let socket = "";
    let target = null;
    let matches = [];
    let matchedRequests = [];
    let lastCaptureError = null;
    while (Date.now() < deadlineMs) {
      ({ socket, target } = await waitForTarget(opts, logPath, deadlineMs, shouldStopForProbeExit));
      try {
        ({ matches, matchedRequests } = await captureNetwork(target.webSocketDebuggerUrl, opts, logPath, deadlineMs, shouldStopForProbeExit));
        lastCaptureError = null;
        break;
      } catch (error) {
        lastCaptureError = error;
        append(logPath, `CDP attach/capture retry after error: ${error.message}`);
        run("adb", ["-s", opts.serial, "forward", "--remove", `tcp:${opts.port}`], { timeout: opts.adbCommandTimeoutMs });
        await sleep(750);
      }
    }
    if (lastCaptureError) throw lastCaptureError;
    if (!target) throw new Error("No CDP target was captured before timeout");
    const success = matches.some((m) => m.urlMatch === opts.successMatch && Number(m.status) === 200);
    const perMatch = summarizeByMatch(opts.urlMatches, matchedRequests, matches);
    summary = {
      outDir: opts.outDir,
      socket,
      targetUrl: target.url || "",
      urlMatches: opts.urlMatches,
      successMatch: opts.successMatch,
      requestMatchCount: matchedRequests.length,
      responseMatchCount: matches.length,
      statuses: matches.map((m) => m.status).filter((v) => v !== undefined),
      perMatch,
      success,
    };
    if (!success) summary.failureReason = explainFailure(opts, perMatch);
    exitCode = success ? 0 : 3;
  } catch (error) {
    summary = {
      outDir: opts.outDir,
      urlMatches: opts.urlMatches,
      successMatch: opts.successMatch,
      success: false,
      error: error.message,
    };
    append(logPath, `capture error: ${error.stack || error.message}`);
    exitCode = 2;
  }

  if (probe) {
    await Promise.race([
      new Promise((resolveWait) => probe.once("exit", resolveWait)),
      sleep(5000),
    ]);
    if (probe.exitCode === null) {
      try {
        probe.kill();
      } catch {}
      await Promise.race([
        new Promise((resolveWait) => probe.once("exit", resolveWait)),
        sleep(1000),
      ]);
      summary.probeKilled = true;
    }
    summary.probeExitCode = probe.exitCode;
    const probeResult = extractProbeResult(probeLogPath);
    Object.assign(summary, probeResult);
    if (!summary.success && probeResult.probeStoppedAfterMarker && String(probeResult.probeStoppedAfterMarker).includes(opts.successMatch)) {
      summary.success = true;
      summary.successSource = "probeStoppedAfterMarker";
      summary.failureReason = undefined;
      exitCode = 0;
    }
  }

  run("adb", ["-s", opts.serial, "forward", "--remove", `tcp:${opts.port}`], { timeout: opts.adbCommandTimeoutMs });
  writeFileSync(resolve(opts.outDir, "summary.json"), JSON.stringify(summary, null, 2), "utf8");
  process.stdout.write(`${JSON.stringify(summary, null, 2)}\n`);
  process.exit(exitCode);
}

main().catch((error) => {
  process.stderr.write(`${error.stack || error.message}\n`);
  process.exit(2);
});
