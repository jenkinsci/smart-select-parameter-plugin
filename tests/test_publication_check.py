import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "scripts" / "publication_check.py"
checker = None
if MODULE.exists():
    spec = importlib.util.spec_from_file_location("publication_check", MODULE)
    checker = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(checker)


class PublicationCheckTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(checker, "Publication checker has not been implemented")

    def test_recognizable_token_is_rejected_without_echoing_it(self):
        token = "ghp_" + "a" * 36
        issues = checker.scan_text(Path("README.md"), "example " + token)
        self.assertTrue(issues)
        self.assertNotIn(token, str(issues))

    def test_private_key_is_rejected(self):
        marker = "-----BEGIN " + "PRIVATE KEY-----"
        self.assertTrue(checker.scan_text(Path("notes.md"), marker))

    def test_loopback_and_reserved_example_urls_are_allowed(self):
        self.assertFalse(checker.scan_text(Path("docs/demo.md"),
            "http://127.0.0.1:18767/options https://services.example.com/options"))

    def test_private_ip_and_unreviewed_host_are_rejected(self):
        address = "10." + "23.45.67"
        host = "build." + "corp"
        self.assertTrue(checker.scan_text(Path("README.md"), "http://" + address))
        self.assertTrue(checker.scan_text(Path("README.md"), "https://" + host))

    def test_markdown_example_url_and_ipv6_loopback_are_allowed(self):
        text = "Use `https://services.example.com` or http://[::1]:8080/options"
        self.assertFalse(checker.scan_text(Path("README.md"), text))

    def test_real_user_home_path_is_rejected(self):
        home = "C:" + chr(92) + "Users" + chr(92) + "someone" + chr(92) + "workspace"
        self.assertTrue(checker.scan_text(Path("notes.md"), home))

    def test_dummy_bearer_is_allowed_only_in_test_sources(self):
        text = 'parameter.setRestToken("bearer-test-secret");'
        self.assertFalse(checker.scan_text(Path("src/test/java/FixtureTest.java"), text))
        self.assertTrue(checker.scan_text(Path("src/main/java/Bad.java"), text))

    def test_source_allowlist_excludes_outputs_and_private_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            for name in ("pom.xml", "README.md", "src/main/java/Sample.java", ".mvn/extensions.xml",
                         ".env", "target/app.hpi", "output/private.log",
                         "src/main/resources/secret.key", ".git/config"):
                file = root / name
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text("sample", encoding="utf-8")
            files = {p.relative_to(root).as_posix() for p in checker.source_files(root)}
            self.assertEqual({"pom.xml", "README.md", "src/main/java/Sample.java", ".mvn/extensions.xml"}, files)

    def test_path_outside_root_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            with self.assertRaises(ValueError):
                checker.safe_relative(root.parent / "outside.txt", root)

    def test_approved_cd_template_allows_its_required_write_permission(self):
        path = Path(".github/workflows/cd.yaml")
        template = (ROOT / path).read_text(encoding="utf-8")
        self.assertTrue(checker.approved_cd_workflow(path, template))
        self.assertFalse(checker.approved_cd_workflow(path, template + "\n  injected: true\n"))
        self.assertFalse(checker.approved_cd_workflow(Path(".github/workflows/other.yaml"), template))

    def test_release_gate_does_not_claim_missing_accounts_or_approval(self):
        issues = checker.release_issues({
            "jenkins_community_users": [],
            "copyright_and_publication_confirmed": False,
            "hosting_approved": False,
        })
        codes = {item["rule"] for item in issues}
        self.assertIn("community-account", codes)
        self.assertIn("publication-consent", codes)
        self.assertIn("hosting-approval", codes)


if __name__ == "__main__":
    unittest.main()
