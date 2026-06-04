#!/usr/bin/env python3
"""PR security review with ordered AI provider fallback."""

import fnmatch
import os
import re
import sys
import json
import urllib.error
import urllib.request

_SHA_RE = re.compile(r"^[a-f0-9]{40}$")

GITHUB_TOKEN = os.environ["GITHUB_TOKEN"]
GITHUB_REPOSITORY = os.environ["GITHUB_REPOSITORY"]
PR_NUMBER = os.environ.get("PR_NUMBER", "")
BEFORE_SHA = os.environ.get("BEFORE_SHA", "")
AFTER_SHA = os.environ.get("AFTER_SHA", "")
GITHUB_STEP_SUMMARY = os.environ.get("GITHUB_STEP_SUMMARY", "")
MAX_DIFF_CHARS = int(os.environ.get("MAX_DIFF_CHARS", "30000"))
DISCORD_WEBHOOK = os.environ.get("DISCORD_WEBHOOK", "")

SECURITY_PROMPT = """あなたはJava 21バックエンドサービス（Jetty HTTPサーバー、Firestore REST API、MySQL、Cloud Run上でデプロイ）のgit diffをレビューするシニアセキュリティエンジニアです。OWASP Top 10およびCWEタクソノミーを適用してください。

Report only genuine, exploitable vulnerabilities with clear evidence in the diff. Omit theoretical issues that require physical access, internal trust, or non-existing attack paths. If you are not confident a finding is exploitable, omit it or downgrade to LOW with a clear caveat.

Check for:
- Injection: SQL injection, LDAP injection, log injection (user input in log arguments without sanitization), command injection, JNDI lookup via log4j-style sinks
- XSS / template injection in rendered HTML or JSON output
- SSRF: user-controlled values passed to HTTP clients or Firestore document path construction
- Path traversal: user input used in file paths or Firestore document paths without allowlist validation
- Broken access control: IDOR (user-supplied IDs used without ownership validation), missing authorization checks, privilege escalation
- Hardcoded secrets, credentials, API keys, or tokens in source or config files
- Insecure deserialization: untrusted data in ObjectInputStream, Jackson polymorphic types, or YAML parsers
- Race conditions / TOCTOU: must involve mutable shared state that can change between check and use; do not flag immutable values (e.g. Java String) or lambdas that capture already-validated variables
- Sensitive data exposure: PII, tokens, or passwords in logs, error responses, or HTTP response headers
- Cryptography: weak algorithms (MD5/SHA-1 for security purposes), improper IV/key management, predictable randomness
- Authentication / JWT: algorithm confusion (none/HS256/RS256 swap), missing signature validation
- Missing input validation at trust boundaries (HTTP request parameters, path params, headers, body fields)
- Java-specific: XXE via DocumentBuilder or SAXParser without disabling external entities, ReDoS via user-controlled input matched against backtracking-prone regex, unsafe reflection or class loading from user input

Respond in this exact JSON format:
{
  "findings": [
    {
      "severity": "HIGH|MEDIUM|LOW",
      "file": "path/to/file.java",
      "line": "approximate line number or short code snippet for navigation",
      "description": "脆弱性の簡潔な説明と現実的な攻撃シナリオ(日本語で)",
      "recommendation": "具体的な修復手順(日本語)"
    }
  ],
  "summary": "全体的なセキュリティ評価を1〜2文の日本語で記述"
}

If no genuine issues found, return {"findings": [], "summary": "No security issues found."}
Diff to analyze:
"""


def load_providers() -> list[dict]:
    providers = []
    i = 1
    while True:
        name = os.environ.get(f"PROVIDER_{i}_NAME")
        if not name:
            break
        key = os.environ.get(f"PROVIDER_{i}_KEY", "")
        base_url = os.environ.get(f"PROVIDER_{i}_BASE_URL", "")
        model = os.environ.get(f"PROVIDER_{i}_MODEL", "")
        if key and base_url and model:
            providers.append({"name": name, "base_url": base_url.rstrip("/"), "key": key, "model": model})
        else:
            print(f"[{name}] skipped: missing KEY, BASE_URL, or MODEL")
        i += 1
    return providers


def gh_request(url: str, accept: str = "application/vnd.github+json", method: str = "GET", body: dict | None = None):
    headers = {
        "Authorization": f"Bearer {GITHUB_TOKEN}",
        "Accept": accept,
        "X-GitHub-Api-Version": "2022-11-28",
    }
    data = json.dumps(body).encode() if body else None
    if body:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    return urllib.request.urlopen(req)


def get_diff() -> str:
    if PR_NUMBER:
        url = f"https://api.github.com/repos/{GITHUB_REPOSITORY}/pulls/{PR_NUMBER}"
        print(f"Fetching diff for PR #{PR_NUMBER}...")
    elif BEFORE_SHA and AFTER_SHA:
        if not (_SHA_RE.match(BEFORE_SHA) and _SHA_RE.match(AFTER_SHA)):
            print("Invalid commit SHA format, skipping.")
            return ""
        url = f"https://api.github.com/repos/{GITHUB_REPOSITORY}/compare/{BEFORE_SHA}...{AFTER_SHA}"
        print(f"Fetching diff for push {BEFORE_SHA[:7]}...{AFTER_SHA[:7]}...")
    else:
        print("No PR number or commit SHAs available.")
        return ""

    with gh_request(url, accept="application/vnd.github.v3.diff") as resp:
        diff = resp.read().decode("utf-8", errors="replace")
    return diff[:MAX_DIFF_CHARS]


def load_ignore_patterns() -> list[str]:
    try:
        with open(".github/security_review_ignore.txt") as f:
            return [l.strip() for l in f if l.strip() and not l.startswith("#")]
    except FileNotFoundError:
        return []


def filter_diff(diff: str, patterns: list[str]) -> str:
    if not patterns:
        return diff
    # diff を各ファイルセクション（"diff --git ..." で始まる）に分割してフィルタ
    sections = re.split(r"(?=^diff --git )", diff, flags=re.MULTILINE)
    kept = []
    for section in sections:
        if not section.startswith("diff --git "):
            kept.append(section)
            continue
        m = re.match(r"^diff --git a/(.*?) b/", section)
        if m and any(fnmatch.fnmatch(m.group(1), p) for p in patterns):
            continue
        kept.append(section)
    return "".join(kept)


def ai_chat(provider: dict, prompt: str, max_continuation: int = 3) -> str:
    url = f"{provider['base_url']}/chat/completions"
    headers = {
        "Authorization": f"Bearer {provider['key']}",
        "Content-Type": "application/json",
    }
    messages = [{"role": "user", "content": prompt}]
    accumulated = ""

    for attempt in range(max_continuation):
        payload = {
            "model": provider["model"],
            "messages": messages,
            "temperature": 0.1,
            "max_tokens": 4096,
        }
        req = urllib.request.Request(url, data=json.dumps(payload).encode(), headers=headers, method="POST")
        with urllib.request.urlopen(req, timeout=120) as resp:
            result = json.loads(resp.read())

        choice = result["choices"][0]
        content = choice["message"]["content"]
        finish_reason = choice.get("finish_reason", "stop")
        accumulated += content

        if finish_reason != "length":
            break

        print(f"[{provider['name']}] truncated at attempt {attempt + 1}/{max_continuation}, continuing...")
        messages.append({"role": "assistant", "content": content})
        messages.append({"role": "user", "content": "途中から続けてください。JSON の残りをそのまま完成させてください。"})

    return accumulated


def output_results(body: str) -> None:
    if PR_NUMBER:
        url = f"https://api.github.com/repos/{GITHUB_REPOSITORY}/issues/{PR_NUMBER}/comments"
        with gh_request(url, method="POST", body={"body": body}):
            pass
        print("Posted comment to PR.")
    elif GITHUB_STEP_SUMMARY:
        summary_path = os.path.realpath(GITHUB_STEP_SUMMARY)
        if ".." in GITHUB_STEP_SUMMARY or not os.path.isabs(summary_path):
            print("Invalid GITHUB_STEP_SUMMARY path, skipping write.")
            print(body)
            return
        with open(summary_path, "a") as f:
            f.write(body + "\n")
        print("Written to job summary.")
    else:
        print(body)


def format_comment(result: dict, provider_name: str) -> str:
    findings = result.get("findings", [])
    summary = result.get("summary", "")
    severity_icon = {"HIGH": "🔴", "MEDIUM": "🟡", "LOW": "🔵"}

    if not findings:
        return f"## セキュリティレビュー\n\n✅ {summary}\n\n*レビュー実施: {provider_name}*"

    lines = ["## セキュリティレビュー\n"]
    for f in findings:
        icon = severity_icon.get(f.get("severity", "LOW"), "⚪")
        lines.append(f"### {icon} [{f.get('severity')}] `{f.get('file', '')}`")
        lines.append(f"{f.get('description', '')}")
        lines.append(f"**Fix:** {f.get('recommendation', '')}\n")

    lines.append(f"**サマリー:** {summary}")
    lines.append(f"\n*レビュー実施: {provider_name}*")
    return "\n".join(lines)


def send_discord(result: dict, provider_name: str) -> None:
    if not DISCORD_WEBHOOK:
        return
    webhook_urls = [
        u for u in (
            raw.strip().replace("canary.discord.com", "discord.com").replace("ptb.discord.com", "discord.com")
            for raw in DISCORD_WEBHOOK.split(",")
        )
        if u.startswith("https://discord.com/api/webhooks/")
    ]
    if not webhook_urls:
        print("Discord notification skipped: no valid webhook URLs found")
        return
    findings = result.get("findings", [])
    summary  = result.get("summary", "")

    severity_max = "none"
    for f in findings:
        s = f.get("severity", "LOW")
        if s == "HIGH":   severity_max = "HIGH";   break
        if s == "MEDIUM": severity_max = "MEDIUM"
        elif severity_max == "none": severity_max = "LOW"

    color = {"HIGH": 0xED4245, "MEDIUM": 0xFEE75C, "LOW": 0x5865F2}.get(severity_max, 0x57F287)

    ref = PR_NUMBER and f"PR #{PR_NUMBER}" or (AFTER_SHA[:7] if AFTER_SHA else "push")
    repo_url = f"https://github.com/{GITHUB_REPOSITORY}"
    run_url  = f"{repo_url}/actions"

    fields = []
    for f in findings[:10]:
        icon = {"HIGH": "🔴", "MEDIUM": "🟡", "LOW": "🔵"}.get(f.get("severity", "LOW"), "⚪")
        name  = f"{icon} [{f.get('severity')}] `{f.get('file', '')}`"
        value = f.get("description", "")
        rec   = f.get("recommendation", "")
        if rec:
            value += f"\n**Fix:** {rec}"
        fields.append({"name": name, "value": value[:1024], "inline": False})

    title = "✅ Security Review — No issues" if not findings else f"🔒 Security Review — {len(findings)} finding(s)"
    embed = {
        "title": title,
        "description": summary[:2048],
        "color": color,
        "fields": fields,
        "footer": {"text": f"{ref} · {GITHUB_REPOSITORY} · {provider_name}"},
        "url": run_url,
    }

    payload = json.dumps({"embeds": [embed]}).encode()
    headers = {
        "Content-Type": "application/json",
        "User-Agent": "DiscordBot (https://github.com/C-lab-works/rsai-backend, 1.0)",
    }
    for i, url in enumerate(webhook_urls):
        label = f"webhook #{i + 1}"
        req = urllib.request.Request(url, data=payload, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=10):
                pass
            print(f"Discord notification sent: {label}")
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", errors="replace")
            print(f"Discord notification failed ({label}): HTTP {e.code} {e.reason} — {body}")
        except Exception:
            print(f"Discord notification failed ({label}): network error")


def main() -> None:
    providers = load_providers()
    if not providers:
        print("No providers configured. Set PROVIDER_1_NAME, PROVIDER_1_KEY, etc.")
        sys.exit(1)

    diff = get_diff()
    if not diff.strip():
        print("No diff found, skipping.")
        return

    ignore_patterns = load_ignore_patterns()
    if ignore_patterns:
        diff = filter_diff(diff, ignore_patterns)
        print(f"Ignore patterns applied ({len(ignore_patterns)}). Remaining diff: {len(diff)} chars.")
    if not diff.strip():
        print("All changes matched ignore patterns, skipping.")
        return

    raw = None
    used_provider = None
    for provider in providers:
        print(f"Trying provider: {provider['name']} ({provider['model']})...")
        try:
            raw = ai_chat(provider, SECURITY_PROMPT + diff)
            used_provider = provider["name"]
            print(f"Success with {provider['name']}")
            break
        except Exception as e:
            safe_msg = str(e).replace(provider["key"], "***") if provider["key"] in str(e) else str(e)
            print(f"[{provider['name']}] failed: {safe_msg}, trying next...")

    if raw is None or used_provider is None:
        output_results("## Security Review\n\n All AI providers failed. Check workflow logs.")
        sys.exit(1)

    try:
        start = raw.find("{")
        end = raw.rfind("}") + 1
        result = json.loads(raw[start:end])
    except (ValueError, json.JSONDecodeError):
        result = {"findings": [], "summary": raw[:500]}

    findings_count = len(result.get("findings", []))
    print(f"Found {findings_count} security issue(s).")

    output_results(format_comment(result, used_provider))
    send_discord(result, used_provider)

    if any(f.get("severity") == "HIGH" for f in result.get("findings", [])):
        sys.exit(1)


if __name__ == "__main__":
    main()
