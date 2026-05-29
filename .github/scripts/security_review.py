#!/usr/bin/env python3
"""PR security review with ordered AI provider fallback."""

import os
import sys
import json
import urllib.request

GITHUB_TOKEN = os.environ["GITHUB_TOKEN"]
GITHUB_REPOSITORY = os.environ["GITHUB_REPOSITORY"]
PR_NUMBER = os.environ.get("PR_NUMBER", "")
BEFORE_SHA = os.environ.get("BEFORE_SHA", "")
AFTER_SHA = os.environ.get("AFTER_SHA", "")
GITHUB_STEP_SUMMARY = os.environ.get("GITHUB_STEP_SUMMARY", "")
MAX_DIFF_CHARS = int(os.environ.get("MAX_DIFF_CHARS", "30000"))

SECURITY_PROMPT = """You are a security code reviewer. Analyze the following git diff for security vulnerabilities.

Focus on:
- SQL injection, XSS, SSRF, path traversal, command injection
- Hardcoded secrets or credentials
- Insecure authentication/authorization
- Unsafe deserialization

Respond in this exact JSON format:
{
  "findings": [
    {
      "severity": "HIGH|MEDIUM|LOW",
      "file": "path/to/file.java",
      "description": "brief description",
      "recommendation": "how to fix"
    }
  ],
  "summary": "overall security assessment in 1-2 sentences"
}

If no issues found, return {"findings": [], "summary": "No security issues found."}

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
        url = f"https://api.github.com/repos/{GITHUB_REPOSITORY}/compare/{BEFORE_SHA}...{AFTER_SHA}"
        print(f"Fetching diff for push {BEFORE_SHA[:7]}...{AFTER_SHA[:7]}...")
    else:
        print("No PR number or commit SHAs available.")
        return ""

    with gh_request(url, accept="application/vnd.github.v3.diff") as resp:
        diff = resp.read().decode("utf-8", errors="replace")
    return diff[:MAX_DIFF_CHARS]


def ai_chat(provider: dict, prompt: str) -> str:
    url = f"{provider['base_url']}/chat/completions"
    payload = {
        "model": provider["model"],
        "messages": [{"role": "user", "content": prompt}],
        "temperature": 0.1,
        "max_tokens": 2048,
    }
    headers = {
        "Authorization": f"Bearer {provider['key']}",
        "Content-Type": "application/json",
    }
    req = urllib.request.Request(url, data=json.dumps(payload).encode(), headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=60) as resp:
        result = json.loads(resp.read())
    return result["choices"][0]["message"]["content"]


def output_results(body: str) -> None:
    if PR_NUMBER:
        url = f"https://api.github.com/repos/{GITHUB_REPOSITORY}/issues/{PR_NUMBER}/comments"
        with gh_request(url, method="POST", body={"body": body}):
            pass
        print("Posted comment to PR.")
    elif GITHUB_STEP_SUMMARY:
        with open(GITHUB_STEP_SUMMARY, "a") as f:
            f.write(body + "\n")
        print("Written to job summary.")
    else:
        print(body)


def format_comment(result: dict, provider_name: str) -> str:
    findings = result.get("findings", [])
    summary = result.get("summary", "")
    severity_icon = {"HIGH": "🔴", "MEDIUM": "🟡", "LOW": "🔵"}

    if not findings:
        return f"## Security Review\n\n✅ {summary}\n\n*Analyzed by {provider_name}*"

    lines = ["## Security Review\n"]
    for f in findings:
        icon = severity_icon.get(f.get("severity", "LOW"), "⚪")
        lines.append(f"### {icon} [{f.get('severity')}] `{f.get('file', '')}`")
        lines.append(f"{f.get('description', '')}")
        lines.append(f"**Fix:** {f.get('recommendation', '')}\n")

    lines.append(f"**Summary:** {summary}")
    lines.append(f"\n*Analyzed by {provider_name}*")
    return "\n".join(lines)


def main() -> None:
    providers = load_providers()
    if not providers:
        print("No providers configured. Set PROVIDER_1_NAME, PROVIDER_1_KEY, etc.")
        sys.exit(1)

    diff = get_diff()
    if not diff.strip():
        print("No diff found, skipping.")
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
            print(f"[{provider['name']}] failed: {e}, trying next...")

    if raw is None:
        output_results("## Security Review\n\n⚠️ All AI providers failed. Check workflow logs.")
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

    if any(f.get("severity") == "HIGH" for f in result.get("findings", [])):
        sys.exit(1)


if __name__ == "__main__":
    main()
