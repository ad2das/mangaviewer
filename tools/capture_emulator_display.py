"""Observe emulator output independently; timestamps are capture bounds, never present fences.

Run with grpcio and stubs generated from the installed emulator_controller.proto.
The generated directory is supplied explicitly; credentials are read only from the
selected running emulator's discovery file and are never written to evidence.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys
import time


def capture(args: argparse.Namespace) -> dict:
    sys.path.insert(0, str(args.generated_directory.resolve()))
    import grpc
    import emulator_controller_pb2 as messages
    import emulator_controller_pb2_grpc as services

    discovery = dict(line.split("=", 1) for line in args.discovery_file.read_text().splitlines() if "=" in line)
    if discovery.get("port.serial") != args.serial.removeprefix("emulator-"):
        raise ValueError("Discovery file belongs to another emulator")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    metadata = [("authorization", "Bearer " + discovery["grpc.token"])]
    sequence = None
    missing = 0
    count = 0
    first = last = None
    status = "NOT_STARTED"
    start = time.time_ns()
    with grpc.insecure_channel("localhost:" + discovery["grpc.port"],
                               options=[("grpc.max_receive_message_length", 32 * 1024 * 1024)]) as channel:
        stream = services.EmulatorControllerStub(channel).streamScreenshot(
            messages.ImageFormat(format=messages.ImageFormat.RGB888, width=args.width, height=args.height),
            metadata=metadata, timeout=args.seconds)
        with (output / "observations.jsonl").open("w", encoding="utf-8") as evidence:
            try:
                for frame in stream:
                    received = time.time_ns()
                    if sequence is not None:
                        if frame.seq <= sequence:
                            raise ValueError("Non-increasing capture sequence")
                        missing += frame.seq - sequence - 1
                    sequence = frame.seq
                    width, height = frame.format.width, frame.format.height
                    if width * height * 3 != len(frame.image):
                        raise ValueError("Incomplete screenshot payload")
                    path = output / f"frame-{count:06d}.rgb"
                    path.write_bytes(frame.image)
                    record = dict(sequence=frame.seq, estimatedGenerationEpochMicros=frame.timestampUs,
                                  observedByEpochNanos=received, width=width, height=height,
                                  pixelOrder="UNVERIFIED", sdkDeclaredPixelOrder="left-to-right,bottom-up", file=path.name,
                                  sha256=hashlib.sha256(frame.image).hexdigest())
                    evidence.write(json.dumps(record, separators=(",", ":")) + "\n")
                    if first is None:
                        first = record
                    last = record
                    count += 1
                status = "STREAM_ENDED"
            except grpc.RpcError as failure:
                if failure.code() != grpc.StatusCode.DEADLINE_EXCEEDED:
                    raise RuntimeError("Screenshot RPC failed: " + failure.code().name) from None
                status = "DURATION_REACHED"
            finally:
                stream.cancel()
    summary = dict(schema=1, status=status, emulatorSerial=args.serial,
                   emulatorVersion=discovery.get("emulator.version"), requestedWidth=args.width,
                   requestedHeight=args.height,
                   startedAtEpochNanos=start, finishedAtEpochNanos=time.time_ns(),
                   frames=count, missingSequences=missing, first=first, last=last,
                   qualifiesPhysicalPresentation=False,
                   timestampMeaning="Estimated generation plus independently observed capture upper bound")
    (output / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--generated-directory", type=Path, required=True)
    parser.add_argument("--discovery-file", type=Path, required=True)
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--seconds", type=float, default=12)
    parser.add_argument("--width", type=int, default=96)
    parser.add_argument("--height", type=int, default=208)
    args = parser.parse_args()
    if args.seconds <= 0 or args.width <= 0 or args.height <= 0:
        parser.error("Duration and width must be positive")
    summary = capture(args)
    print(json.dumps({key: value for key, value in summary.items() if key not in ("first", "last")}))
    return 0 if summary["frames"] > 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
