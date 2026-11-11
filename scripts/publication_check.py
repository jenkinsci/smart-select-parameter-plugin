"""Local source-preparation checks, not the official Jenkins hosting checker."""
import argparse
import hashlib
import ipaddress
import json
from pathlib import Path
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ROOT_FILES = {
    "pom.xml", "LICENSE", "README.md", "README.zh-CN.md", "CHANGELOG.md",
    "CONTRIBUTING.md", "SECURITY.md", "Jenkinsfile", "verify-offline.sh",
    ".gitignore", ".gitattributes",
}
PREFIXES = {"src", "examples", "docs", "scripts", "tests", ".github", ".mvn", "publishing"}
EXCLUDED = {"target", "dist", "output", ".git", ".idea", ".vscode", ".playwright-cli", "__pycache__"}
BAD_EXTENSIONS = {".pem", ".key", ".dpapi", ".hpi", ".jpi", ".zip", ".class", ".log", ".pyc"}
PRIVATE_NAMES = {"credentials.xml", "settings.xml", "secrets.json", "id_rsa", "id_ed25519"}
PUBLIC_HOSTS = (
    "github.com", "githubusercontent.com", "jenkins.io", "jenkins-ci.org",
    "apache.org", "opensource.org", "w3.org", "python.org",
)
DUMMIES = {"secret-for-test", "bearer-test-secret", "local-test-token"}
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def issue(rule, path="", line=None):
    result = {"rule": rule, "path": str(path)}
    if line is not None:
        result["line"] = line
    return result


def safe_relative(path, root):
    return path.resolve().relative_to(root.resolve())


def source_files(root):
    root = Path(root).resolve()
    result = []
    for path in root.rglob("*"):
        relative = path.relative_to(root)
        if not path.is_file() or any(part in EXCLUDED for part in relative.parts):
            continue
        if relative.parts[0] not in PREFIXES and relative.as_posix() not in ROOT_FILES:
            continue
        if (path.suffix.lower() in BAD_EXTENSIONS or path.name.startswith(".env")
                or path.name in PRIVATE_NAMES):
            continue
        safe_relative(path, root)
        result.append(path)
    return sorted(result, key=lambda p: p.relative_to(root).as_posix())


def public_host(host):
    host = host.lower().rstrip(".")
    if host in {"localhost", "127.0.0.1", "::1"} or host.endswith(".localhost"):
        return True
    if host == "example.com" or host.endswith(".example.com") or host.endswith(".test") or host.endswith(".invalid"):
        return True
    return any(host == domain or host.endswith("." + domain) for domain in PUBLIC_HOSTS)


def scan_text(path, text):
    found = []
    test_source = "test" in path.parts or "tests" in path.parts
    patterns = {
        "github-token": r"\bgh[pousr]_[A-Za-z0-9]{30,}\b|\bgithub_pat_[A-Za-z0-9_]{40,}\b",
        "private-key": r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----",
        "cloud-key": r"\bAKIA[A-Z0-9]{16}\b",
        "user-home": r"\b[A-Za-z]:[\\/]+Users[\\/]+[^\\/\s]+",
        "query-credential": r"[?&](?:access_token|api[_-]?key|key|token|secret)=[A-Za-z0-9_-]{12,}",
    }
    for line_number, line in enumerate(text.splitlines(), 1):
        for rule, pattern in patterns.items():
            if re.search(pattern, line, re.IGNORECASE if rule in {"query-credential", "user-home"} else 0):
                found.append(issue(rule, path, line_number))
        for match in re.finditer(r"\bsetRestToken\(\s*[\"']([^\"'\r\n]{8,})[\"']", line):
            if not (test_source and match.group(1) in DUMMIES):
                found.append(issue("literal-bearer", path, line_number))
        for match in re.finditer(r"(?i)\b(?:password|api[_-]?token|restToken|secret)\s*[:=]\s*[\"']([^\"'\r\n]{8,})[\"']", line):
            if not (test_source and match.group(1) in DUMMIES):
                found.append(issue("literal-credential", path, line_number))
        for match in re.finditer(r"https?://[^\s\"'<>)\x60]+", line):
            try:
                host = urllib.parse.urlsplit(match.group(0)).hostname
                if host and not public_host(host):
                    found.append(issue("unreviewed-endpoint", path, line_number))
            except ValueError:
                pass
        for address in re.findall(r"(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])", line):
            try:
                ip = ipaddress.ip_address(address)
                documentation = any(ip in ipaddress.ip_network(net) for net in (
                    "192.0.2.0/24", "198.51.100.0/24", "203.0.113.0/24"))
                if (ip.is_private or ip.is_link_local) and not ip.is_loopback and not documentation:
                    found.append(issue("private-address", path, line_number))
            except ValueError:
                pass
    # Findings intentionally contain only a rule, path and line, never matched secret text.
    return found


def release_issues(settings):
    result = []
    if not settings.get("jenkins_community_users"):
        result.append(issue("community-account", "publishing/maintainers.json"))
    if not settings.get("copyright_and_publication_confirmed"):
        result.append(issue("publication-consent", "publishing/maintainers.json"))
    if not settings.get("hosting_approved"):
        result.append(issue("hosting-approval", "publishing/maintainers.json"))
    return result


# This is the Jenkins CD workflow template reviewed in the CD onboarding PR.
# Any change to its content requires an explicit digest update and review.
def approved_cd_workflow(path, text):
    return (path.as_posix() == ".github/workflows/cd.yaml"
            and hashlib.sha256(text.encode("utf-8")).hexdigest() == "590d6bf0ac45fcfa76d73b9ec472e145b7a988402ef1397da42a8377e9c169b0")


def inspect_source(root, release=False):
    root = Path(root).resolve()
    errors, warnings = [], []
    files = source_files(root)
    names = {p.relative_to(root).as_posix() for p in files}
    required = ROOT_FILES | {
        ".github/workflows/ci.yml", "publishing/maintainers.json",
        "publishing/README.md", "publishing/hosting-request.md",
        "docs/testing.md", "docs/images/multi-select.png",
        "scripts/publication_check.py", "scripts/build_source_archive.py",
    }
    for name in sorted(required - names):
        errors.append(issue("missing-file", name))
    for file in files:
        if file.suffix.lower() == ".png":
            continue
        try:
            text = file.read_text(encoding="utf-8-sig")
        except UnicodeDecodeError:
            errors.append(issue("non-text-source", file.relative_to(root)))
            continue
        relative = file.relative_to(root)
        errors.extend(scan_text(relative, text))
        if file.suffix.lower() == ".md":
            for link in re.findall(r"!?\[[^\]]*\]\(([^)]+)\)", text):
                if "://" in link or link.startswith(("#", "mailto:")):
                    continue
                target = file.parent / urllib.parse.unquote(link.split("#", 1)[0])
                try:
                    safe_relative(target, root)
                except ValueError:
                    errors.append(issue("link-outside-source", relative))
                    continue
                if not target.exists():
                    errors.append(issue("broken-local-link", relative))
        if relative.parts[:2] == (".github", "workflows") and file.suffix in {".yml", ".yaml"}:
            for action in re.findall(r"^\s*-?\s*uses:\s*(\S+)", text, re.MULTILINE):
                if not action.startswith("./") and not re.fullmatch(r"[\w./-]+@[a-f0-9]{40}", action):
                    errors.append(issue("unpinned-action", relative))
            if re.search(r"\b(?:mvn|mvnw)\b[^\n]*\bdeploy\b", text):
                errors.append(issue("active-deployment-workflow", relative))
            if (re.search(r"^\s*contents:\s*write", text, re.MULTILINE)
                    and not approved_cd_workflow(relative, text)):
                errors.append(issue("active-write-permission", relative))
    try:
        pom = ET.parse(root / "pom.xml").getroot()
        values = {name: pom.findtext("m:" + name, namespaces=NS) for name in ("groupId", "artifactId", "version", "url")}
        if values["groupId"] != "io.jenkins.plugins":
            errors.append(issue("group-id", "pom.xml"))
        if values["artifactId"] != "smart-select-parameter":
            errors.append(issue("artifact-id", "pom.xml"))
        if not pom.findtext("m:scm/m:connection", namespaces=NS):
            errors.append(issue("missing-scm", "pom.xml"))
        if values["url"] == "https://www.jenkins.io/doc/developer/":
            errors.append(issue("generic-homepage", "pom.xml"))
        if pom.findtext("m:licenses/m:license/m:name", namespaces=NS) != "MIT License":
            errors.append(issue("license-metadata", "pom.xml"))
        if release and values["version"].endswith("-SNAPSHOT"):
            errors.append(issue("snapshot-not-release", "pom.xml"))
    except (ET.ParseError, FileNotFoundError, AttributeError):
        errors.append(issue("invalid-pom", "pom.xml"))
    settings_file = root / "publishing/maintainers.json"
    if settings_file.exists():
        settings = json.loads(settings_file.read_text(encoding="utf-8-sig"))
        pending = release_issues(settings)
        (errors if release else warnings).extend(pending)
        if release and not pending:
            repository = settings.get("proposed_repository", "")
            if not re.fullmatch(r"[\w.-]+/[\w.-]+", repository):
                errors.append(issue("repository-name", "publishing/maintainers.json"))
            else:
                try:
                    request = urllib.request.Request(
                        "https://api.github.com/repos/" + repository,
                        headers={"User-Agent": "smart-select-publication-check"})
                    with urllib.request.urlopen(request, timeout=15) as response:
                        data = json.load(response)
                    if data.get("private", True):
                        errors.append(issue("repository-not-public", "publishing/maintainers.json"))
                except (urllib.error.URLError, TimeoutError):
                    errors.append(issue("repository-not-public-or-unreachable", "publishing/maintainers.json"))
    return {"local_preparation_passed": not errors, "scanned_files": len(files), "errors": errors, "pending": warnings}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--release", action="store_true")
    parser.add_argument("--json", type=Path)
    args = parser.parse_args()
    result = inspect_source(args.root, args.release)
    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))
    return 0 if result["local_preparation_passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
