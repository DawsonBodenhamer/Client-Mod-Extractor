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
LEASE_ROOT = Path(os.environ.get("CME_ISSUE8_TEST_TMP", tempfile.gettempdir()))


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
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, format, *args):
        pass


def write_jar(path, entries):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, content in entries.items():
            archive.writestr(name, content)


def fabric_metadata(mod_id, environment="*"):
    return json.dumps({
        "schemaVersion": 1,
        "id": mod_id,
        "version": "test",
        "environment": environment,
        "mixins": [{"config": "client", "environment": "client"}],
    })


def neoforge_metadata(mod_id, dependency_side=None, client_only=False):
    lines = [
        'modLoader="javafml"',
        'loaderVersion="[1,)"',
        'license="MIT"',
        "",
        "[[mods]]",
        f'modId="{mod_id}"',
        'version="test"',
        f'displayName="{mod_id}"',
    ]
    if client_only:
        lines.append("clientSideOnly=true")
    if dependency_side:
        lines.extend([
            "",
            f"[[dependencies.{mod_id}]]",
            'modId="minecraft"',
            'mandatory=true',
            'versionRange="[1,)"',
            'ordering="NONE"',
            f'side="{dependency_side}"',
        ])
    return "\n".join(lines)


class Issue8RegressionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp_root = Path(tempfile.mkdtemp(prefix="cme-issue8-", dir=LEASE_ROOT))
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

    def run_fixture(self):
        run_root = Path(tempfile.mkdtemp(prefix="run-", dir=self.temp_root))

        neoforge_ids = ["coolrain", "eg_particle_interactions", "norealmsbutton", "windy"]
        for mod_id in neoforge_ids:
            write_jar(
                run_root / f"{mod_id}-neoforge.jar",
                {"META-INF/neoforge.mods.toml": neoforge_metadata(mod_id, dependency_side="BOTH")},
            )

        write_jar(
            run_root / "appleskin-neoforge.jar",
            {"META-INF/neoforge.mods.toml": neoforge_metadata("appleskin", dependency_side="CLIENT")},
        )
        write_jar(
            run_root / "appleskin-fabric.jar",
            {"fabric.mod.json": fabric_metadata("appleskin")},
        )
        write_jar(
            run_root / "particle-interactions-fabric-0.4.1.jar",
            {"fabric.mod.json": fabric_metadata("eg_particle_interactions")},
        )
        write_jar(
            run_root / "particle-interactions-fabric-patch.1.jar",
            {"fabric.mod.json": fabric_metadata("eg_particle_interactions", "client")},
        )
        write_jar(
            run_root / "norealmsbutton-fabric.jar",
            {"fabric.mod.json": fabric_metadata("norealmsbutton")},
        )
        write_jar(
            run_root / "windy-fabric.jar",
            {"fabric.mod.json": fabric_metadata("windy", "client")},
        )
        write_jar(
            run_root / "remote-force-include.jar",
            {"fabric.mod.json": fabric_metadata("remote_force_include")},
        )
        write_jar(
            run_root / "remote-force-client.jar",
            {"META-INF/neoforge.mods.toml": neoforge_metadata("remote-force-client", client_only=True)},
        )
        write_jar(
            run_root / "dependency-side-client.jar",
            {"META-INF/neoforge.mods.toml": neoforge_metadata("dependency-side-client", dependency_side="CLIENT")},
        )
        write_jar(
            run_root / "advancement-plaques-neoforge.jar",
            {"META-INF/neoforge.mods.toml": neoforge_metadata("advancementplaques")},
        )
        write_jar(
            run_root / "advancement-plaques-fabric.jar",
            {"fabric.mod.json": fabric_metadata("advancementplaques")},
        )
        for mod_id in (
            "betterbiomeblend",
            "flyspeed",
            "fwa",
            "lightweight-inventory-sorting",
            "modefite",
            "voxy",
        ):
            write_jar(
                run_root / f"{mod_id}-client-only.jar",
                {"fabric.mod.json": fabric_metadata(mod_id)},
            )

        payloads = {
            "/cf.json": json.dumps({
                "globalExcludes": ["AppleSkin", "REMOTE_FORCE_INCLUDE", "remote-force-client"],
                "globalForceIncludes": ["remote_force_include", "remote-force-client"],
            }),
            "/modrinth.json": json.dumps({
                "globalExcludes": [],
                "globalForceIncludes": [],
            }),
            "/custom.txt": (
                "betterbiomeblend,coolrain,eg_particle_interactions,flyspeed,fwa,"
                "lightweight-inventory-sorting,modefite,norealmsbutton,voxy,windy,"
                "+appleskin,neoforge:advancementplaques"
            ),
            "/latest.json": json.dumps({"tag_name": "v1.0.8"}),
        }
        FixtureHandler.payloads = payloads
        server = FixtureServer(("127.0.0.1", 0), FixtureHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base_url = f"http://127.0.0.1:{server.server_port}"
        command = [
            "java",
            f"-Dcme.cf-excludes-url={base_url}/cf.json",
            f"-Dcme.modrinth-excludes-url={base_url}/modrinth.json",
            f"-Dcme.custom-excludes-url={base_url}/custom.txt",
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

    def test_issue_8_cross_loader_outcomes_and_precedence(self):
        output, copied = self.run_fixture()

        self.assertEqual(
            copied,
            {
                "appleskin-neoforge.jar",
                "appleskin-fabric.jar",
                "remote-force-include.jar",
                "dependency-side-client.jar",
                "advancement-plaques-fabric.jar",
            },
        )
        for mod_id in ("coolrain", "eg_particle_interactions", "norealmsbutton", "windy"):
            self.assertIn(f"{mod_id}-neoforge.jar (mislabeled)", output)
        self.assertIn("appleskin-neoforge.jar (force-included)", output)
        self.assertIn("appleskin-fabric.jar (force-included)", output)
        self.assertIn("remote-force-include.jar (force-included)", output)
        self.assertIn("remote-force-client.jar", output)
        self.assertIn("remote-force-client.jar", output[output.index("remote-force-client.jar"):])
        self.assertNotIn("remote-force-client.jar (force-included)", output)
        self.assertIn("particle-interactions-fabric-patch.1.jar", output)
        self.assertIn("[CLIENT ONLY]", output)
        self.assertIn("mislabeled", output)
        self.assertIn("advancement-plaques-neoforge.jar (loader-specific)", output)
        for mod_id in (
            "betterbiomeblend",
            "flyspeed",
            "fwa",
            "lightweight-inventory-sorting",
            "modefite",
            "voxy",
        ):
            self.assertIn(f"{mod_id}-client-only.jar (mislabeled)", output)

    def test_dependency_side_client_is_not_a_consuming_mod_signal(self):
        output, copied = self.run_fixture()
        self.assertIn("dependency-side-client.jar", copied)
        self.assertIn("dependency-side-client.jar", output)

    def test_local_fallback_parses_legacy_force_and_loader_scoped_rules(self):
        run_root = Path(tempfile.mkdtemp(prefix="fallback-", dir=self.temp_root))
        (run_root / "custom-excludes.txt").write_text(
            "legacy_excluded,force_target,+force-target,neoforge:scoped_target\n",
            encoding="utf-8",
        )
        write_jar(
            run_root / "legacy-excluded.jar",
            {"fabric.mod.json": fabric_metadata("legacy-excluded")},
        )
        write_jar(
            run_root / "force-target.jar",
            {"fabric.mod.json": fabric_metadata("force_target")},
        )
        write_jar(
            run_root / "scoped-neoforge.jar",
            {"META-INF/neoforge.mods.toml": neoforge_metadata("scoped-target")},
        )
        write_jar(
            run_root / "scoped-fabric.jar",
            {"fabric.mod.json": fabric_metadata("scoped-target")},
        )

        unavailable = "http://127.0.0.1:1/unavailable"
        command = [
            "java",
            f"-Dcme.cf-excludes-url={unavailable}",
            f"-Dcme.modrinth-excludes-url={unavailable}",
            f"-Dcme.custom-excludes-url={unavailable}",
            f"-Dcme.latest-version-url={unavailable}",
            "-cp",
            str(self.classes),
            "client_mod_extractor_app.ClientModExtractor",
        ]
        result = subprocess.run(command, cwd=run_root, capture_output=True, text=True, check=True)
        copied = {path.name for path in (run_root / "Save_For_Server_Mods").glob("*.jar")}

        self.assertEqual(copied, {"force-target.jar", "scoped-fabric.jar"})
        self.assertIn("legacy-excluded.jar (mislabeled)", result.stdout)
        self.assertIn("force-target.jar (force-included)", result.stdout)
        self.assertIn("scoped-neoforge.jar (loader-specific)", result.stdout)

if __name__ == "__main__":
    unittest.main()
