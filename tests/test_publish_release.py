import importlib.util
import io
import os
import subprocess
import tempfile
import unittest
import zipfile
from contextlib import redirect_stderr
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).resolve().parents[1] / 'tools' / 'publish_release.py'
SPEC = importlib.util.spec_from_file_location('publish_release', SCRIPT)
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)


class PublishReleaseTests(unittest.TestCase):
    def test_release_command_targets_explicit_repository(self):
        command = release.build_release_command('v2.0.0', 'v2.0.0 - 2026-07-20', 'abc123')
        self.assertIn('--repo', command)
        self.assertEqual(command[command.index('--repo') + 1], release.GITHUB_REPOSITORY)
        self.assertEqual(command[command.index('--target') + 1], 'abc123')

    def test_cleanup_removes_both_temporary_files(self):
        with tempfile.TemporaryDirectory() as directory:
            zip_path = os.path.join(directory, release.FILE_ZIP_OUTPUT)
            notes_path = os.path.join(directory, release.FILE_TEMP_NOTES)
            Path(zip_path).write_bytes(b'zip')
            Path(notes_path).write_text('notes', encoding='utf-8')
            with mock.patch.object(release, 'PATH_ZIP_OUTPUT', zip_path), mock.patch.object(
                release, 'PATH_TEMP_NOTES', notes_path
            ):
                release.cleanup_temporary_files()
            self.assertFalse(os.path.exists(zip_path))
            self.assertFalse(os.path.exists(notes_path))

    def test_archive_contains_only_app_files_and_strips_java_package(self):
        with tempfile.TemporaryDirectory() as directory:
            app = Path(directory) / release.DIR_MAIN_APP
            app.mkdir()
            (app / 'ClientModExtractor.java').write_text(
                'package client_mod_extractor_app;\npublic class ClientModExtractor {}\n', encoding='utf-8'
            )
            (app / 'Run-Extractor-Linux.sh').write_text('#!/bin/sh\n', encoding='utf-8')
            (app / 'Run-Extractor-Windows.bat').write_text('@echo off\r\n', encoding='utf-8')
            zip_path = str(Path(directory) / release.FILE_ZIP_OUTPUT)
            with mock.patch.object(release, 'PATH_MAIN_APP', str(app)), mock.patch.object(
                release, 'PATH_ZIP_OUTPUT', zip_path
            ):
                release.create_zip()
            with zipfile.ZipFile(zip_path) as archive:
                self.assertEqual(
                    sorted(archive.namelist()),
                    ['ClientModExtractor.java', 'Run-Extractor-Linux.sh', 'Run-Extractor-Windows.bat'],
                )
                self.assertNotIn('package client_mod_extractor_app;', archive.read('ClientModExtractor.java').decode())

    def test_repository_preflight_rejects_dirty_worktree(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tracked = root / 'tracked.txt'
            tracked.write_text('clean\n', encoding='utf-8')
            self.run_git(root, 'init', '-b', 'main')
            self.run_git(root, 'config', 'user.name', 'Test User')
            self.run_git(root, 'config', 'user.email', 'test@example.com')
            self.run_git(root, 'remote', 'add', 'origin', f'https://github.com/{release.GITHUB_REPOSITORY}.git')
            self.run_git(root, 'add', '.')
            self.run_git(root, 'commit', '-m', 'initial')
            tracked.write_text('dirty\n', encoding='utf-8')
            with mock.patch.object(release, 'PROJECT_ROOT', str(root)):
                with self.assertRaisesRegex(release.ReleaseError, 'must be clean'):
                    release.ensure_clean_repository()

    def test_release_preflight_rejects_unpublished_head(self):
        local = subprocess.CompletedProcess([], 0, stdout='local-sha\n', stderr='')
        remote = subprocess.CompletedProcess([], 0, stdout='remote-sha\n', stderr='')
        with mock.patch.object(release, 'run_git', return_value=local), mock.patch.object(
            release.subprocess, 'run', return_value=remote
        ):
            with self.assertRaisesRegex(release.ReleaseError, 'is not published'):
                release.ensure_head_is_published()

    def test_path_limited_bump_commit_excludes_unrelated_staged_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            java = root / release.FILE_JAVA
            techspec = root / release.FILE_TECHSPEC
            unrelated = root / 'unrelated.txt'
            java.parent.mkdir(parents=True)
            techspec.parent.mkdir(parents=True)
            java.write_text('version 1.0.0\n', encoding='utf-8')
            techspec.write_text('version 1.0.0\n', encoding='utf-8')
            unrelated.write_text('before\n', encoding='utf-8')
            self.run_git(root, 'init', '-b', 'main')
            self.run_git(root, 'config', 'user.name', 'Test User')
            self.run_git(root, 'config', 'user.email', 'test@example.com')
            self.run_git(root, 'add', '.')
            self.run_git(root, 'commit', '-m', 'initial')

            java.write_text('version 1.0.1\n', encoding='utf-8')
            techspec.write_text('version 1.0.1\n', encoding='utf-8')
            unrelated.write_text('after\n', encoding='utf-8')
            self.run_git(root, 'add', 'unrelated.txt')

            with self.assertRaises(release.ReleaseError):
                release.stage_and_commit_changes(list(release.VERSION_FILES), '1.0.1', project_root=root)

            committed = self.run_git(
                root, 'diff-tree', '--no-commit-id', '--name-only', '-r', 'HEAD', capture=True
            ).stdout.splitlines()
            self.assertEqual(set(committed), {path.replace('\\', '/') for path in release.VERSION_FILES})
            staged = self.run_git(root, 'diff', '--cached', '--name-only', capture=True).stdout.splitlines()
            self.assertEqual(staged, ['unrelated.txt'])

    def test_main_reports_partial_success_after_publish(self):
        stderr = io.StringIO()
        with mock.patch.object(release, 'parse_latest_changelog', return_value=('2.0.0', '2026-07-20', 'notes')), \
             mock.patch.object(release, 'preflight_release'), \
             mock.patch.object(release, 'create_zip'), \
             mock.patch.object(release, 'publish_release'), \
             mock.patch.object(release, 'update_project_files_version', return_value=list(release.VERSION_FILES)), \
             mock.patch.object(release, 'stage_and_commit_changes', side_effect=release.ReleaseError('commit failed')), \
             mock.patch.object(release, 'cleanup_temporary_files'), \
             redirect_stderr(stderr):
            result = release.main()
        self.assertEqual(result, 2)
        self.assertIn('PARTIAL SUCCESS', stderr.getvalue())

    @staticmethod
    def run_git(root, *args, capture=False):
        return subprocess.run(
            ['git', *args], cwd=root, check=True, capture_output=True, text=True
        )


if __name__ == '__main__':
    unittest.main()
