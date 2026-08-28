import http.server
import json
import os
import shutil
import subprocess
import tempfile
import threading
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "client_mod_extractor_app" / "ClientModExtractor.java"
LEASE_ROOT = Path(os.environ.get("CLIENT_MOD_EXTRACTOR_TEST_TMP", tempfile.gettempdir()))


class FixtureServer(http.server.ThreadingHTTPServer):
    allow_reuse_address = True


class FixtureHandler(http.server.BaseHTTPRequestHandler):
    payloads = {}

    def do_GET(self):
        payload = self.payloads.get(self.path)
        if payload is None:
            self.send_error(404)
            return
        encoded = payload.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, format, *args):
        pass


def write_jar(path, metadata_name, metadata):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr(metadata_name, metadata)


def toml_metadata(mod_id, *extra_mod_lines, dependency_side="BOTH"):
    lines = [
        'modLoader="javafml"',
        'loaderVersion="[1,)"',
        'license="Value # preserved"',
        '',
        '[[mods]]',
        f'modId="{mod_id}"',
        'version="test"',
        *extra_mod_lines,
        '',
        f'[[dependencies.{mod_id}]]',
        'modId="minecraft"',
        'mandatory=true',
        'versionRange="[1,)"',
        'ordering="NONE"',
        f'side="{dependency_side}"',
    ]
    return "\n".join(lines)


class Issue10RegressionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp_root = Path(tempfile.mkdtemp(prefix="cme-issue10-", dir=LEASE_ROOT))
        cls.classes = cls.temp_root / "classes"
        cls.classes.mkdir()
        subprocess.run(
            ["javac", "--release", "21", "-d", str(cls.classes), str(SOURCE)],
            check=True,
            cwd=ROOT,
        )

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.temp_root, ignore_errors=True)

    def create_fixture(self):
        run_root = Path(tempfile.mkdtemp(prefix="run-", dir=self.temp_root))
        exact_root = os.environ.get("CME_ISSUE10_ARTIFACTS")
        if exact_root:
            for artifact in Path(exact_root).glob("*.jar"):
                shutil.copy2(artifact, run_root / artifact.name)
        copied_cases = {
            "commented-client.jar": toml_metadata(
                "commented_client",
                "# clientSideOnly=true",
                '# side="CLIENT"',
                '# displayTest="IGNORE_ALL_VERSION"',
                'description="Keeps # inside quoted values" # trailing comment',
                'displayTest="""',
                '# clientSideOnly=true inside a multiline string',
                'clientSideOnly=true',
                '"""',
            ),
            "ignore-all.jar": toml_metadata("ignore_all", 'displayTest="IGNORE_ALL_VERSION"'),
            "dependency-client.jar": toml_metadata("dependency_client", dependency_side="CLIENT"),
            "iceberg.jar": toml_metadata("iceberg"),
            "particular-forge.jar": toml_metadata("particular"),
            "particlerain-forge.jar": toml_metadata("particlerain"),
        }
        for name, metadata in copied_cases.items():
            write_jar(run_root / name, "META-INF/mods.toml", metadata)

        write_jar(
            run_root / "direct-client.jar",
            "META-INF/mods.toml",
            toml_metadata("direct_client", "clientSideOnly=true"),
        )
        write_jar(
            run_root / "direct-side-client.jar",
            "META-INF/mods.toml",
            toml_metadata("direct_side_client", 'side="CLIENT"'),
        )
        write_jar(
            run_root / "particular-direct-client.jar",
            "META-INF/mods.toml",
            toml_metadata("particular", "clientSideOnly=true"),
        )
        for mod_id in ("particular", "particlerain"):
            write_jar(
                run_root / f"{mod_id}-neoforge.jar",
                "META-INF/neoforge.mods.toml",
                toml_metadata(mod_id),
            )
        return run_root

    def run_fixture(self, use_remote_rules):
        run_root = self.create_fixture()
        project_rules = "+iceberg,+forge:particular,+forge:particlerain"
        (run_root / "custom-excludes.txt").write_text(project_rules, encoding="utf-8")
        payloads = {
            "/cf.json": json.dumps({
                "globalExcludes": ["iceberg", "particular", "particlerain"],
                "globalForceIncludes": [],
            }),
            "/modrinth.json": json.dumps({"globalExcludes": [], "globalForceIncludes": []}),
            "/custom.txt": project_rules,
            "/latest.json": json.dumps({"tag_name": "v1.0.10"}),
        }
        FixtureHandler.payloads = payloads
        server = FixtureServer(("127.0.0.1", 0), FixtureHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base_url = f"http://127.0.0.1:{server.server_port}"
        custom_url = f"{base_url}/custom.txt" if use_remote_rules else "http://127.0.0.1:1/unavailable"
        command = [
            "java",
            f"-Dcme.cf-excludes-url={base_url}/cf.json",
            f"-Dcme.modrinth-excludes-url={base_url}/modrinth.json",
            f"-Dcme.custom-excludes-url={custom_url}",
            f"-Dcme.latest-version-url={base_url}/latest.json",
            "-cp",
            str(self.classes),
            "client_mod_extractor_app.ClientModExtractor",
        ]
        try:
            result = subprocess.run(command, cwd=run_root, capture_output=True, text=True, check=True)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)
        copied = {path.name for path in (run_root / "Save_For_Server_Mods").glob("*.jar")}
        return result.stdout, copied

    def test_active_toml_only_and_loader_scoped_force_include_precedence(self):
        output, copied = self.run_fixture(use_remote_rules=True)

        expected = {
            "commented-client.jar",
            "dependency-client.jar",
            "iceberg.jar",
            "ignore-all.jar",
            "particular-forge.jar",
            "particlerain-forge.jar",
        }
        exact_root = os.environ.get("CME_ISSUE10_ARTIFACTS")
        if exact_root:
            expected.update(path.name for path in Path(exact_root).glob("*.jar"))
        self.assertEqual(copied, expected)
        self.assertIn("iceberg.jar (force-included)", output)
        self.assertIn("particular-forge.jar (force-included)", output)
        self.assertIn("particlerain-forge.jar (force-included)", output)
        self.assertNotIn("direct-client.jar", copied)
        self.assertNotIn("direct-side-client.jar", copied)
        self.assertNotIn("particular-direct-client.jar", copied)
        self.assertIn("particular-neoforge.jar (mislabeled)", output)
        self.assertIn("particlerain-neoforge.jar (mislabeled)", output)

    def test_online_and_local_project_rules_have_identical_outcomes(self):
        _, remote_copied = self.run_fixture(use_remote_rules=True)
        _, local_copied = self.run_fixture(use_remote_rules=False)
        self.assertEqual(remote_copied, local_copied)

    def test_exact_issue_10_artifacts_are_copied_when_supplied(self):
        exact_root = os.environ.get("CME_ISSUE10_ARTIFACTS")
        if not exact_root:
            self.skipTest("exact issue #10 artifacts not supplied")
        expected = {path.name for path in Path(exact_root).glob("*.jar")}
        self.assertEqual(len(expected), 11)
        _, copied = self.run_fixture(use_remote_rules=True)
        self.assertTrue(expected.issubset(copied), expected.difference(copied))


if __name__ == "__main__":
    unittest.main()
