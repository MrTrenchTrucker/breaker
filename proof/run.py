#!/usr/bin/env python3
"""Proof runner for a dev/proof-* branch: failing-first (red) runs, the tip control (green) and
mutants, read from proof/proof.json. GitHub runner only; the workspace is thrown away after.

  run.py --matrix          print matrix=<json> (one entry per shard) for the plan job
  run.py --shard N         run shard N and write its table to the job summary

Per shard: check that the checkout equals the declared tip tree plus proof files only; run the
module baseline (every test of each module the shard touches) on the tip; run both controls;
run the shard's mutants (full module, KILLED only on failures beyond the baseline); run the
shard's red entries (named classes on the red tree). The workspace is restored and checked clean
after every run. A failed tree check, a misbehaving control, a baseline with zero tests or a
dirty restore makes the shard VOID (exit 1). Results are reported as measured; nothing is
re-labelled."""
import glob, json, os, re, subprocess, sys, tempfile, time
import xml.etree.ElementTree as ET

P = json.load(open("proof/proof.json"))
GRADLE = os.environ.get("PROOF_GRADLE", "./gradlew")
OWN = ["proof", ".github/workflows/proof.yml"]
LOG = []


def sh(cmd, check=True, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if check and r.returncode:
        raise SystemExit("command failed: %s\n%s" % (" ".join(cmd), r.stderr[-2000:]))
    return r


def tree_without_own():
    with tempfile.TemporaryDirectory() as d:
        env = dict(os.environ, GIT_INDEX_FILE=os.path.join(d, "idx"))
        sh(["git", "read-tree", "--empty"], env=env)
        sh(["git", "add", "-A", "--", "."] + [":(exclude)%s" % o for o in OWN], env=env)
        return sh(["git", "write-tree"], env=env).stdout.strip()


def restore():
    sh(["git", "checkout", "--", "."])
    sh(["git", "clean", "-fdq", "--", "."] + [":(exclude)%s" % o for o in OWN])
    left = sh(["git", "status", "--porcelain"]).stdout.strip()
    return left == ""


def results_dir(module):
    return os.path.join(module, "build", "test-results")


def gradle(module, tests):
    for f in glob.glob(os.path.join(results_dir(module), "**", "TEST-*.xml"), recursive=True):
        os.remove(f)
    cmd = [GRADLE, P["modules"][module], "--rerun", "--continue", "--max-workers=2"]
    for t in tests or []:
        cmd += ["--tests", t]
    t0 = time.time()
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=1800)
    total, failed = 0, []
    for f in glob.glob(os.path.join(results_dir(module), "**", "TEST-*.xml"), recursive=True):
        s = ET.parse(f).getroot()
        total += int(s.get("tests", 0))
        for tc in s.iter("testcase"):
            if tc.find("failure") is not None or tc.find("error") is not None:
                failed.append("%s#%s" % (tc.get("classname").rsplit(".", 1)[-1], tc.get("name")))
    all_errs = [l.strip() for l in (r.stdout + r.stderr).splitlines() if l.startswith("e: ")]
    errs = all_errs[:8]
    # every unresolved symbol with the file that names it, so a compile red can be checked against
    # the members the item adds
    unresolved = sorted({"%s: %s" % (os.path.basename(m.group(1)), m.group(2)) for m in
                         (re.search(r"e: (?:file://)?(\S+?\.kt)\S*.*?Unresolved reference:? '?([\w.]+)'?", l) for l in all_errs) if m})
    return {"rc": r.returncode, "total": total, "failed": sorted(set(failed)), "compile_errors": errs, "unresolved": unresolved,
            "secs": int(time.time() - t0)}


def apply_edits(edits):
    for e in edits:
        t = open(e["file"], encoding="utf-8").read()
        if t.count(e["anchor"]) != 1:
            return "anchor count %d in %s" % (t.count(e["anchor"]), e["file"])
        open(e["file"], "w", encoding="utf-8").write(t.replace(e["anchor"], e["replacement"], 1))
    return None


def shard_of(entries, n):
    """Round-robin over the entries, mutants first then red legs, so every shard gets a fair share;
    each shard baselines only the modules its own entries touch (plus the app module for the controls)."""
    out = [[] for _ in range(n)]
    ordered = [e for e in entries if e["kind"] == "mut"] + [e for e in entries if e["kind"] != "mut"]
    for i, e in enumerate(ordered):
        out[i % n].append(e)
    return out


def main():
    if sys.argv[1] == "--matrix":
        print("matrix=" + json.dumps({"shard": list(range(P["shards"]))}))
        return
    n = int(sys.argv[2])
    mine = shard_of(P["entries"], P["shards"])[n]
    rows, void = [], []
    head = ["## %s, shard %d of %d" % (P["name"], n, P["shards"]),
            "",
            "Declared tip tree: `%s`. Checkout commit: `%s`." % (
                P["tip"]["tree"], sh(["git", "rev-parse", "HEAD"]).stdout.strip()),
            "Items: " + "; ".join("%s = base tree `%s`, item tip tree `%s`, new production classes: %s" % (
                i["item"], i["base_tree"][:12], i["item_tip_tree"][:12], ", ".join(i["new_production_classes"]) or "none") for i in P["items"]),
            "Red-tree stubs: " + ("; ".join("item %s: %s (%s)" % (i["item"], i["red_tree_stubs"]["note"], ", ".join(i["red_tree_stubs"]["files"]))
                                         for i in P["items"] if i.get("red_tree_stubs")) or "none"),
            "This shard: %d red, %d mutants, 2 controls. Whole run: %d red, %d mutants." % (
                sum(e["kind"] == "red" for e in mine), sum(e["kind"] == "mut" for e in mine),
                sum(e["kind"] == "red" for e in P["entries"]), sum(e["kind"] == "mut" for e in P["entries"])),
            ""]
    got = tree_without_own()
    if got != P["tip"]["tree"]:
        void.append("checkout tree %s is not the declared tip tree" % got)
    modules = sorted({e["module"] for e in mine} | {"android/app"})
    base = {}
    for m in modules:
        b = gradle(m, None)
        base[m] = set(b["failed"])
        rows.append(("baseline", m, "GREEN" if b["total"] and not b["failed"] else "NOT GREEN",
                     "%d run, %d failed %s" % (b["total"], len(b["failed"]), ", ".join(b["failed"][:5])), b["secs"]))
        if not b["total"]:
            void.append("baseline ran 0 tests in " + m)
        if not restore():
            void.append("dirty after baseline " + m)

    def mutate(eid, module, edits, expect, extra=""):
        bad = apply_edits(edits)
        if bad:
            res, detail, secs = "ANCHOR ERROR", bad, 0
        else:
            g = gradle(module, None)
            new = [f for f in g["failed"] if f not in base[module]]
            secs = g["secs"]
            if g["total"] == 0:
                res, detail = "BROKE", "no tests ran; " + " | ".join(g["compile_errors"][:3])
            elif new:
                res, detail = "KILLED", "%d new failures: %s" % (len(new), ", ".join(new[:4]))
            else:
                res, detail = "SURVIVED", "%d run, no new failure" % g["total"]
        if not restore():
            void.append("dirty after " + eid)
        rows.append((eid, module, res, (extra + " " + detail).strip(), secs))
        return res

    for c in P["controls"]:
        res = mutate(c["id"], c["module"], c["edits"], c["expect"])
        want = "KILLED" if c["expect"] == "kill" else "SURVIVED"
        if res != want:
            void.append("control %s gave %s, expected %s" % (c["id"], res, want))
    for e in [e for e in mine if e["kind"] == "mut"]:
        tag = "[item %s, predicted %s]" % (e["item"], e["expect"])
        mutate(e["id"], e["module"], e["edits"], e["expect"], tag)
    for e in [e for e in mine if e["kind"] == "red"]:
        sh(["git", "apply", "--binary", e["patch"]])
        if tree_without_own() != e["tree"]:
            rows.append((e["id"], e["module"], "TREE MISMATCH", e["file"], 0))
            void.append("red tree mismatch " + e["id"])
        else:
            g = gradle(e["module"], e["tests"])
            cls = ", ".join(t.rsplit(".", 1)[-1] for t in e["tests"])
            item = [i for i in P["items"] if i["item"] == e["item"]][0]
            if g["total"] and g["failed"]:
                res, detail = "ASSERT RED", "%d run, %d failed: %s" % (g["total"], len(g["failed"]), ", ".join(g["failed"][:4]))
            elif not g["total"] and g["compile_errors"]:
                res = "COMPILE RED"
                detail = "item %s new production classes: %s | unresolved: %s | %s" % (
                    e["item"], ", ".join(item["new_production_classes"]) or "none",
                    ", ".join(g["unresolved"]) or "none parsed", " | ".join(g["compile_errors"][:3]))
            elif g["total"]:
                res, detail = "NOT RED", "%d run, 0 failed" % g["total"]
            else:
                res, detail = "NO RESULT", "rc %d, no tests and no compiler error line" % g["rc"]
            pred = (" (predicted: %s)" % e["predict"]) if e.get("predict") else ""
            rows.append((e["id"], e["module"], res, "[%s] %s%s" % (cls, detail, pred), g["secs"]))
        if not restore():
            void.append("dirty after " + e["id"])
    lines = head + ["| run | module | result | detail | s |", "|---|---|---|---|---|"]
    for r in rows:
        lines.append("| %s | %s | %s | %s | %s |" % (r[0], r[1].split("/")[-1], r[2], r[3].replace("|", "/"), r[4]))
    lines += ["", "**VOID: " + "; ".join(void) + "**" if void else "Controls behaved; tree checks passed."]
    text = "\n".join(lines) + "\n"
    print(text)
    s = os.environ.get("GITHUB_STEP_SUMMARY")
    if s:
        open(s, "a").write(text)
    sys.exit(1 if void else 0)


main()
