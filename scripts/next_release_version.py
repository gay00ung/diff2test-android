#!/usr/bin/env python3
import argparse
import pathlib
import re
import sys


SEMVER_RE = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)$")
BUILD_VERSION_RE = re.compile(r'^\s*version\s*=\s*"([^"]+)"\s*$', re.MULTILINE)
RELEASE_COMMIT_RE = re.compile(r"^build(?:\([^)]+\))?:\s*release\s+v?(\d+\.\d+\.\d+)\s*$", re.MULTILINE | re.IGNORECASE)
MAJOR_PATTERNS = (
    re.compile(r"BREAKING CHANGE", re.IGNORECASE),
    re.compile(r"^[a-zA-Z0-9_-]+(?:\([^)]+\))?!:", re.MULTILINE),
)
MINOR_PATTERNS = (
    re.compile(r"^feat(?:\([^)]+\))?:", re.MULTILINE),
)
PATCH_PATTERNS = (
    re.compile(r"^fix(?:\([^)]+\))?:", re.MULTILINE),
    re.compile(r"^refactor(?:\([^)]+\))?:", re.MULTILINE),
    re.compile(r"^perf(?:\([^)]+\))?:", re.MULTILINE),
    re.compile(r"^build(?:\([^)]+\))?:", re.MULTILINE),
    re.compile(r"^ci(?:\([^)]+\))?:", re.MULTILINE),
)
SKIP_PATTERNS = (
    re.compile(r"\[skip release\]", re.IGNORECASE),
    re.compile(r"\[no release\]", re.IGNORECASE),
)


def parse_semver(tag: str) -> tuple[int, int, int]:
    match = SEMVER_RE.fullmatch(tag.strip())
    if not match:
        raise ValueError(f"Unsupported tag format: {tag}")
    return tuple(int(part) for part in match.groups())


def normalize_semver(value: str) -> str | None:
    match = SEMVER_RE.fullmatch(value.strip())
    if not match:
        return None
    major, minor, patch = match.groups()
    return f"{int(major)}.{int(minor)}.{int(patch)}"


def parse_project_version(version_file: pathlib.Path) -> str | None:
    content = version_file.read_text(encoding="utf-8")
    match = BUILD_VERSION_RE.search(content)
    if not match:
        return None
    return normalize_semver(match.group(1))


def compare_versions(left: str, right: str) -> int:
    left_parts = parse_semver(left)
    right_parts = parse_semver(right)
    if left_parts < right_parts:
        return -1
    if left_parts > right_parts:
        return 1
    return 0


def detect_release_commit_version(commits: str) -> str | None:
    matches = RELEASE_COMMIT_RE.findall(commits)
    if not matches:
        return None
    return normalize_semver(matches[-1])


def detect_bump(commits: str) -> tuple[str | None, str]:
    text = commits.strip()
    if not text:
        return None, "no commits since the latest release tag"

    if any(pattern.search(text) for pattern in SKIP_PATTERNS):
        return None, "commit history requested release skip"

    if any(pattern.search(text) for pattern in MAJOR_PATTERNS):
        return "major", "detected BREAKING CHANGE or !: commit"

    if any(pattern.search(text) for pattern in MINOR_PATTERNS):
        return "minor", "detected feat commit"

    if any(pattern.search(text) for pattern in PATCH_PATTERNS):
        return "patch", "detected patch-level release commit"

    return None, "no release-worthy commit prefixes detected"


def bump_version(tag: str, bump: str) -> str:
    major, minor, patch = parse_semver(tag)
    if bump == "major":
        return f"v{major + 1}.0.0"
    if bump == "minor":
        return f"v{major}.{minor + 1}.0"
    if bump == "patch":
        return f"v{major}.{minor}.{patch + 1}"
    raise ValueError(f"Unsupported bump type: {bump}")


def write_output(path: pathlib.Path, values: dict[str, str]) -> None:
    with path.open("a", encoding="utf-8") as handle:
        for key, value in values.items():
            handle.write(f"{key}={value}\n")


def main() -> int:
    parser = argparse.ArgumentParser(description="Calculate the next release tag from the project version and commit messages.")
    parser.add_argument("--latest-tag", required=True, help="Latest release tag, for example v0.1.0")
    parser.add_argument("--commits-file", required=True, help="Path to a file with commit messages since the last tag")
    parser.add_argument("--version-file", required=True, help="Path to build.gradle.kts or another file containing the project version")
    parser.add_argument("--github-output", help="Optional GitHub Actions output file")
    args = parser.parse_args()

    latest_tag = args.latest_tag.strip()
    commits = pathlib.Path(args.commits_file).read_text(encoding="utf-8")
    project_version = parse_project_version(pathlib.Path(args.version_file))
    release_commit_version = detect_release_commit_version(commits)

    values = {
        "latest_tag": latest_tag,
        "project_version": project_version or "",
    }

    if project_version is not None and compare_versions(f"v{project_version}", latest_tag) > 0:
        values["skip"] = "false"
        values["reason"] = "project version is ahead of the latest tag"
        values["source"] = "build.gradle.kts"
        values["next_tag"] = f"v{project_version}"
    elif release_commit_version is not None and compare_versions(f"v{release_commit_version}", latest_tag) > 0:
        values["skip"] = "false"
        values["reason"] = "detected explicit release commit"
        values["source"] = "commit message"
        values["next_tag"] = f"v{release_commit_version}"
    else:
        bump, reason = detect_bump(commits)
        if bump is None:
            values["skip"] = "true"
            values["reason"] = reason
        else:
            values["skip"] = "false"
            values["bump"] = bump
            values["reason"] = reason
            values["source"] = "commit message"
            values["next_tag"] = bump_version(latest_tag, bump)

    if args.github_output:
        write_output(pathlib.Path(args.github_output), values)
    else:
        for key, value in values.items():
            print(f"{key}={value}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
