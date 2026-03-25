#!/usr/bin/env python3
import argparse
import pathlib
import re
import sys


def replace_once(text: str, pattern: str, replacement: str, label: str) -> str:
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.MULTILINE)
    if count != 1:
        raise ValueError(f"Could not update {label} in formula")
    return updated


def main() -> int:
    parser = argparse.ArgumentParser(description="Update d2t Homebrew formula fields.")
    parser.add_argument("--formula", required=True, help="Path to Formula/d2t.rb")
    parser.add_argument("--homepage", required=True)
    parser.add_argument("--url", required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--version", required=True)
    args = parser.parse_args()

    formula_path = pathlib.Path(args.formula)
    original = formula_path.read_text()

    updated = original
    updated = replace_once(updated, r'^  homepage ".*"$', f'  homepage "{args.homepage}"', "homepage")
    updated = replace_once(updated, r'^  url ".*"$', f'  url "{args.url}"', "url")
    updated = replace_once(updated, r'^  sha256 ".*"$', f'  sha256 "{args.sha256}"', "sha256")
    updated = replace_once(updated, r'^  version ".*"$', f'  version "{args.version}"', "version")

    if updated == original:
        print("Formula already up to date.")
        return 0

    formula_path.write_text(updated)
    print(f"Updated formula: {formula_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
