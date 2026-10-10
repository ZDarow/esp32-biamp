#!/usr/bin/env python3
"""Сверка матрицы владения путями (кроссплатформенный аналог check-owners.ps1).

Сравнивает разбор путей из .githooks/pre-commit с .github/CODEOWNERS.
Падает, если правила не покрывают один и тот же набор путей или владелец
расходится. Использование: python3 tools/check-owners.py
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HOOK = ROOT / ".githooks" / "pre-commit"
OWNERS = ROOT / ".github" / "CODEOWNERS"

if not HOOK.is_file():
    sys.exit(f"Не найден {HOOK}")
if not OWNERS.is_file():
    sys.exit(f"Не найден {OWNERS}")


def read_lines(path: Path) -> list[str]:
    text = path.read_text(encoding="utf-8-sig")
    return text.replace("\r\n", "\n").split("\n")


def parse_hook(lines: list[str]) -> dict[str, str]:
    """Извлекает правила из функции path_owner() в хуке."""
    rules: dict[str, str] = {}
    in_fn = False
    for line in lines:
        if line.startswith("path_owner("):
            in_fn = True
            continue
        if in_fn and line.strip() == "}":
            in_fn = False
            continue
        if not in_fn:
            continue
        m = re.match(r'^\s*(.+?)\)\s+echo\s+"([a-z-]+)"', line)
        if m:
            for pat in re.split(r"\s*\|\s*", m.group(1)):
                rules[pat.strip()] = m.group(2)
    if not rules:
        sys.exit("В хуке не найдено ни одного правила path_owner()")
    return rules


def parse_owners(lines: list[str]) -> dict[str, str]:
    rules: dict[str, str] = {}
    for line in lines:
        t = line.strip()
        if not t or t.startswith("#"):
            continue
        m = re.match(r"^(.+?)\s+@([\w\-\[\]]+)\s*$", t)
        if m:
            rules[m.group(1).strip()] = m.group(2)
    if not rules:
        sys.exit("В CODEOWNERS не найдено ни одного правила")
    return rules


ALIAS = {
    "firmware": "firmware",
    "android": "android",
    "pc-client": "pc-client",
    "shared": "integrator",
    "integrator": "integrator",
}


def resolve(owner: str) -> str:
    return ALIAS.get(owner, owner)


def normalize(pat: str) -> str:
    pat = pat.lstrip("/")
    pat = re.sub(r"\s*\*\s*$", "", pat)
    return pat.rstrip("/")


def covering(path: str, rules: dict[str, str]) -> str | None:
    needle = normalize(path)
    for rule, owner in rules.items():
        if normalize(rule) == needle:
            return owner
    best = None
    best_len = -1
    for rule, owner in rules.items():
        r = normalize(rule)
        if not r:
            continue
        if (needle == r or needle.startswith(r + "/")) and len(r) > best_len:
            best = owner
            best_len = len(r)
    return best


def covered(path: str, rules: dict[str, str]) -> bool:
    needle = normalize(path).replace("*", "")
    for rule in rules:
        r = normalize(rule).replace("*", "")
        if not r or not needle:
            continue
        if needle == r:
            return True
        if needle.startswith(r + "/") or r.startswith(needle + "/"):
            return True
    return False


hook_all = parse_hook(read_lines(HOOK))
owner_all = parse_owners(read_lines(OWNERS))

hook_rules = {k: v for k, v in hook_all.items() if k.lstrip("/") != "*"}
owner_rules = {k: v for k, v in owner_all.items() if k.lstrip("/") != "*"}

if not hook_rules:
    sys.exit("В хуке нет ни одного конкретного правила, кроме '*'")
if not owner_rules:
    sys.exit("В CODEOWNERS нет ни одного конкретного правила, кроме '*'")

problems: list[str] = []

if "*" not in hook_all:
    problems.append("В хуке отсутствует catch-all '*' (правило *) — новые файлы не будут проверяться на принадлежность направлению")
if "*" not in owner_all:
    problems.append("В CODEOWNERS отсутствует catch-all '*' — новые файлы получат владельца по умолчанию без явного назначения")

for path in owner_rules:
    if not covered(path, hook_rules):
        problems.append(f"CODEOWNERS описывает '{path}', но хук о нём не знает: правка из чужой ветки пройдёт молча")

for path in hook_rules:
    if not covered(path, owner_rules):
        problems.append(f"хук описывает '{path}' (владелец {resolve(hook_rules[path])}), но в CODEOWNERS его нет")

all_paths = {normalize(p) for p in hook_all if p.lstrip("/") != "*"}
all_paths |= {normalize(p) for p in owner_all if p.lstrip("/") != "*"}
for path in all_paths:
    ho = covering(path, hook_rules)
    co = covering(path, owner_rules)
    if ho and co:
        rh, rc = resolve(ho), resolve(co)
        if rh != rc:
            problems.append(f"Владелец расхождён: '{path}' — хук приписывает '{ho}' (→ {rh}), CODEOWNERS приписывает '@{co}' (→ {rc})")

if problems:
    print(f"::error::Матрица владения расходится ({len(problems)}):")
    for p in problems:
        print(f"  {p}")
    print()
    print("Правьте .githooks/pre-commit, .github/CODEOWNERS и docs/BRANCHING.md вместе.")
    sys.exit(1)

print(f"Матрица владения согласована: {len(hook_rules)} конкретных правил в хуке, {len(owner_rules)} в CODEOWNERS (плюс catch-all '*' в обоих).")
