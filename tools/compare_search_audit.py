"""Compare app IDs with independently parsed response HTML and separate site captures.

Usage: python tools/compare_search_audit.py --native DIR --reference DIR --output FILE
Uses audit_search_reference.py's BeautifulSoup parser, not the app's Jsoup parser.
Incomplete walks and request failures remain failed gates even if partial IDs agree.
"""
import argparse
import json
import pathlib
import urllib.parse

from bs4 import BeautifulSoup
from audit_search_reference import cards


def load(path):
    return json.loads(path.read_text(encoding="utf-8"))


def response_items(directory, provider):
    batches = []
    for line in (directory / "network.jsonl").read_text(encoding="utf-8").splitlines():
        record = json.loads(line)
        if record["phase"] != "BODY_COMPLETE":
            continue
        path = directory / f"response-{record['id']}.html"
        if not path.exists():
            continue
        url = urllib.parse.urlparse(record["url"])
        params = urllib.parse.parse_qs(url.query, encoding="euc-kr" if provider == "wfwf" else "utf-8")
        if "q" not in params:
            continue
        html = path.read_bytes().decode("euc-kr" if provider == "wfwf" else "utf-8")
        batches.append(dict(query=params["q"][0].strip(), field=params.get("field", ["title"])[0],
                            items=dict(cards(provider, BeautifulSoup(html, "html.parser"))), file=path.name))
    return batches


def reconciled_goodtoon_reference(directory):
    paths = sorted(directory.glob("round-*-page-*.html"))
    if not paths:
        raise ValueError(f"No independent Goodtoon page captures in {directory}")
    items = {key: title for path in paths
             for key, title in cards("goodtoon", BeautifulSoup(path.read_text(encoding="utf-8"), "html.parser"))}
    return dict(name="love", items=items, complete=True,
                basis="independent repeated full-page captures", files=[str(p) for p in paths])


def compare(native_roots, reference, goodtoon_repair_captures=None):
    report = []
    for provider in ("ntk", "wfwf", "newxtoon", "goodtoon"):
        directories = [root / provider for root in reversed(native_roots)
                       if (root / provider / "results.json").exists()]
        responses = {directory: response_items(directory, provider) for directory in directories}
        reference_path = reference / provider / "reference.json"
        references = load(reference_path) if reference_path.exists() else []
        if provider == "goodtoon" and goodtoon_repair_captures is not None:
            references = [r for r in references if r["name"] != "love"]
            references.append(reconciled_goodtoon_reference(goodtoon_repair_captures))
        for name in ("survival", "love", "author"):
            directory = next((d for d in directories if (d / f"{name}-complete.json").exists()), None)
            if directory is None:
                continue
            path = directory / f"{name}-complete.json"
            results = load(directory / "results.json")
            batches = responses[directory]
            walk = load(path)
            pages = [r for r in results if r["case"].startswith(name + "-page-")]
            errors = [r["case"] for r in pages if "error" in r]
            complete = bool(pages) and not errors and pages[-1].get("nextCursor") is None
            field = "author" if provider == "ntk" and name == "author" else "title"
            selected = [b for b in batches if b["query"] == walk["query"] and b["field"] == field]
            raw = {key: title for b in selected for key, title in b["items"].items()}
            actual = walk["items"]
            row = dict(provider=provider, case=name, query=walk["query"], complete=complete,
                       nativeDirectory=str(directory),
                       successfulPages=sum("error" not in p for p in pages), failedPages=errors,
                       appCount=len(actual), responseCount=len(raw),
                       parserMissing=sorted(raw.keys() - actual.keys()),
                       parserExtra=sorted(actual.keys() - raw.keys()),
                       responseFiles=[b["file"] for b in selected])
            independent = next((r for r in references if r["name"] == name), None)
            # Old partial Newxtoon captures must not masquerade as a complete reference.
            if independent and (provider != "newxtoon" or independent.get("complete") is True):
                expected = independent["items"]
                row.update(referenceCount=len(expected),
                           separateCaptureMissing=sorted(expected.keys() - actual.keys()),
                           separateCaptureExtra=sorted(actual.keys() - expected.keys()))
                if "basis" in independent:
                    row.update(referenceBasis=independent["basis"], referenceFiles=independent["files"])
            report.append(row)
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--native", required=True, type=pathlib.Path, nargs="+",
                        help="Native run directories; later runs override cases present in both")
    parser.add_argument("--reference", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--goodtoon-repair-captures", type=pathlib.Path,
                        help="Independent repeated Goodtoon 사랑 page captures; union replaces its single-pass reference")
    args = parser.parse_args()
    report = compare(args.native, args.reference, args.goodtoon_repair_captures)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    for r in report:
        separate = len(r["separateCaptureMissing"]) if "separateCaptureMissing" in r else "not-captured"
        extra = len(r["separateCaptureExtra"]) if "separateCaptureExtra" in r else "not-captured"
        print(f"{r['provider']} {r['case']}: app={r['appCount']} raw={r['responseCount']} "
              f"complete={r['complete']} parser-missing/extra={len(r['parserMissing'])}/{len(r['parserExtra'])} "
              f"separate-capture-missing/extra={separate}/{extra}")


if __name__ == "__main__":
    main()
