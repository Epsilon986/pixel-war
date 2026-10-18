"""Rebuild audit tables/figures from individual JSON and actual JFR exports. No invented data."""
import argparse
import csv
import json
import math
from pathlib import Path
import statistics
import hashlib


def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def write_csv(path, rows):
    if not rows:
        return
    with Path(path).open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def stats(values):
    return {"n": len(values), "mean": statistics.mean(values), "median": statistics.median(values),
            "stdev": statistics.stdev(values) if len(values) > 1 else None,
            "sampleVariance": statistics.variance(values) if len(values) > 1 else None,
            "minimum": min(values), "maximum": max(values)}


def summarize(root):
    runs = [load(path) for path in sorted((root / "raw").glob("*.json"))]
    observations = []
    groups = {}
    for run in runs:
        if run["mode"] not in ("engine", "application") or run["runId"].startswith("application-profile"):
            continue
        metrics = {key: run[key] for key in ("elapsedNanos", "attemptsPerSecond", "modificationsPerSecond", "cpuNanos",
                   "peakObservedHeapBytes", "allocatedBytesPerAction") if run.get(key) is not None}
        metrics["colorsArrayBytes"] = run["colorsLayout"]["totalArrayBytes"]
        for key in ("httpP95Nanos", "httpP99Nanos", "processCpuNanos"):
            if run.get(key) is not None:
                metrics[key] = run[key]
        for metric, value in metrics.items():
            key = (run["mode"], run["size"], run["initial"], run["seed"], run["version"], metric)
            groups.setdefault(key, []).append(value)
            observations.append(dict(runId=run["runId"], mode=run["mode"], size=run["size"], initial=run["initial"],
                                     seed=run["seed"], version=run["version"], metric=metric, value=value))
    summary = [dict(zip(("mode", "size", "initial", "seed", "version", "metric"), key), **stats(values))
               for key, values in sorted(groups.items())]
    write_csv(root / "observations.csv", observations)
    write_csv(root / "summary.csv", summary)
    pairs = []
    paired = {}
    for run in runs:
        if run["mode"] not in ("engine", "application") or run["runId"].startswith("application-profile"):
            continue
        pair = run["runId"].rsplit("-", 1)[0]
        paired.setdefault(pair, {})[run["version"]] = run
    for name, versions in sorted(paired.items()):
        if set(versions) != {"before", "after"}:
            continue
        before, after = versions["before"], versions["after"]
        pairs.append(dict(pair=name, mode=before["mode"], size=before["size"], initial=before["initial"],
                          seed=before["seed"], fixedLoadSpeedup=before["elapsedNanos"] / after["elapsedNanos"] if before["mode"] == "engine" else None,
                          throughputRatio=after["attemptsPerSecond"] / before["attemptsPerSecond"],
                          throughputVariationPercent=100 * (after["attemptsPerSecond"] / before["attemptsPerSecond"] - 1)))
    write_csv(root / "pairs.csv", pairs)
    ratio_groups = {}
    for pair in pairs:
        for key in ("fixedLoadSpeedup", "throughputRatio", "throughputVariationPercent"):
            if pair[key] is not None:
                ratio_groups.setdefault((pair["mode"], pair["size"], pair["initial"], pair["seed"], key), []).append(pair[key])
    write_csv(root / "pair-summary.csv", [dict(zip(("mode", "size", "initial", "seed", "metric"), key), **stats(values)) for key, values in sorted(ratio_groups.items())])
    replays = []
    replay_groups = {}
    for run in runs:
        if run["mode"] in ("replay", "engine"):
            replay_groups.setdefault(run["runId"].rsplit("-", 1)[0], {})[run["version"]] = run
    for name, versions in sorted(replay_groups.items()):
        if set(versions) != {"before", "after"}:
            continue
        a, b = versions["before"], versions["after"]
        equivalent = a["before"] == b["before"] and a["after"] == b["after"] and a["spatialSha256"] == b["spatialSha256"]
        replays.append(dict(pair=name, equivalent=equivalent, spatialSha256=a["spatialSha256"],
                            actions=a["executedActions"], beforeCommit=a["commit"], afterCommit=b["commit"]))
    (root / "replay-verification.json").write_text(json.dumps(replays, indent=2), encoding="utf-8")
    if any(not row["equivalent"] for row in replays):
        raise RuntimeError("Deterministic replay failed; see replay-verification.json")
    return runs, summary


def plots(root, runs, summary):
    import os
    os.environ.setdefault("MPLCONFIGDIR", str(root / "matplotlib-cache"))
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    import numpy as np
    figures = root / "figures"
    figures.mkdir(exist_ok=True)
    plt.rcParams.update({"font.size": 10, "figure.dpi": 150, "axes.spines.top": False, "axes.spines.right": False})
    colors = {"before": "#526d82", "after": "#238b71"}
    for mode in ("engine", "application"):
        cases = [(500, "empty"), (500, "full"), (4000, "empty"), (4000, "full")] if mode == "engine" else [(500, "empty"), (4000, "empty")]
        fig, ax = plt.subplots(figsize=(10, 5.2))
        for offset, version in ((-.18, "before"), (.18, "after")):
            data = [next(row for row in summary if row["mode"] == mode and row["size"] == size and row["initial"] == initial and row["version"] == version and row["metric"] == "attemptsPerSecond") for size, initial in cases]
            ax.bar(np.arange(len(cases)) + offset, [row["mean"] / 1e6 for row in data], .34,
                   yerr=[(row["stdev"] or 0) / 1e6 for row in data], capsize=4, label="Avant" if version == "before" else "Après", color=colors[version])
        ax.set_xticks(range(len(cases)), [f"{size} × {size}\n{'vide' if initial == 'empty' else 'rempli'}" for size, initial in cases])
        ax.set_ylabel("Millions de tentatives / s")
        ax.set_title("Moteur déterministe · moyenne ± écart-type, 5 JVM par version" if mode == "engine" else "Application avec polling · moyenne ± écart-type, 3 JVM par version")
        ax.legend(); ax.grid(axis="y", alpha=.2); ax.set_axisbelow(True)
        fig.tight_layout(); fig.savefig(figures / f"throughput-{mode}.png"); plt.close(fig)
    fig, ax = plt.subplots(figsize=(8, 4.5))
    for offset, version in ((-.18, "before"), (.18, "after")):
        values = [next(run["colorsLayout"]["totalArrayBytes"] for run in runs if run["mode"] == "engine" and run["size"] == size and run["version"] == version) / 2**20 for size in (500, 4000)]
        bars = ax.bar(np.arange(2) + offset, values, .34, label="Avant" if version == "before" else "Après", color=colors[version])
        ax.bar_label(bars, fmt="%.2f Mio", padding=3)
    ax.set_xticks([0, 1], ["SMALL 500 × 500", "DEFAULT 4000 × 4000"])
    ax.set_ylabel("Mio · tableaux de couleurs uniquement")
    ax.set_title("Empreinte observée avec Instrumentation.getObjectSize")
    ax.legend(); fig.tight_layout(); fig.savefig(figures / "colors-footprint.png"); plt.close(fig)
    # Real profiles, exclusive CPU samples and estimated allocation weights, not fabricated timings.
    for prefix in ("profile-4000-full", "application-profile-4000-empty"):
        files = [root / "profiles" / f"{prefix}-s11-p0-{version}.summary.json" for version in ("before", "after")]
        if not all(path.exists() for path in files):
            continue
        profiles = [load(path) for path in files]
        for field, ylabel, suffix in (("cpuExclusiveSamples", "Échantillons CPU exclusifs", "cpu"),
                                      ("allocationEstimatedBytesByClass", "Mio alloués estimés · échantillonnage JFR", "allocations")):
            keys = sorted(set(profiles[0][field]) | set(profiles[1][field]), key=lambda key: max(profile[field].get(key, 0) for profile in profiles), reverse=True)[:10]
            fig, ax = plt.subplots(figsize=(12, 6.5))
            for i, version in enumerate(("before", "after")):
                scale = 2**20 if suffix == "allocations" else 1
                values = [profiles[i][field].get(key, 0) / scale for key in keys]
                ax.barh(np.arange(len(keys)) + (i-.5)*.36, values, .34, label="Avant" if i == 0 else "Après", color=colors[version])
            ax.set_yticks(range(len(keys)), [key.replace("org.pixelwar.pixelwar.domain.", "") for key in keys]); ax.invert_yaxis()
            ax.set_xlabel(ylabel); ax.legend(); ax.set_title(f"JFR réel · {'moteur DEFAULT rempli' if prefix.startswith('profile-') else 'application DEFAULT avec polling'}")
            fig.text(.02, .015, "CPU : méthode au sommet de pile, pas un temps absolu. Allocations : poids estimés, pas des compteurs exacts.\nCollectes séparées des essais principaux ; les tableaux inclusifs et piles complètes restent dans les exports JSON.", fontsize=8)
            fig.tight_layout(rect=(0,.07,1,1)); fig.savefig(figures / f"{prefix}-{suffix}.png"); plt.close(fig)
    (root / "plot-dependencies.json").write_text(json.dumps({"matplotlib": matplotlib.__version__, "numpy": np.__version__}, indent=2), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("results", type=Path)
    parser.add_argument("--no-plots", action="store_true")
    args = parser.parse_args()
    runs, summary = summarize(args.results)
    if not args.no_plots:
        plots(args.results, runs, summary)
    print(f"Summarized {len(runs)} runs; {len(summary)} statistical rows. Population scenarios remain separate.")


if __name__ == "__main__":
    main()
