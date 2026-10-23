#!/usr/bin/env python3
"""Exercise the real prepare hook using isolated fake Gradle and SDK executables."""
import base64
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SOURCE = Path(__file__).resolve().with_name('build_release.sh')

class ReleasePreparationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='sms-prepare-release-')
        self.root = Path(self.temp.name)
        (self.root / 'tools').mkdir()
        (self.root / 'runner').mkdir()
        shutil.copyfile(SOURCE, self.root / 'tools/build_release.sh')
        gradle = self.root / 'gradlew'
        gradle.write_text('''#!/usr/bin/env bash
set -euo pipefail
printf '%s\\n' "$@" > gradle-arguments
[[ -f "$ANDROID_KEYSTORE_PATH" ]]
printf '%s' "$ANDROID_KEYSTORE_PATH" > signing-path
[[ "${MOCK_GRADLE_FAIL:-false}" != true ]] || exit 42
mkdir -p app/build/outputs/apk/release
printf 'signed-apk-fixture' > app/build/outputs/apk/release/app-release.apk
''')
        gradle.chmod(0o700)
        signer = self.root / 'sdk/build-tools/35.0.0/apksigner'
        signer.parent.mkdir(parents=True)
        signer.write_text('''#!/usr/bin/env bash
[[ "$1" == verify && -f "$3" && "${MOCK_SIGNATURE_FAIL:-false}" != true ]]
''')
        signer.chmod(0o700)
        self.env = dict(os.environ,
            GITHUB_RUN_NUMBER='42', RUNNER_TEMP=str(self.root / 'runner'),
            ANDROID_HOME=str(self.root / 'sdk'), GITHUB_OUTPUT=str(self.root / 'outputs'),
            ANDROID_KEYSTORE_BASE64=base64.b64encode(b'private-key-fixture').decode(),
            ANDROID_KEYSTORE_PASSWORD='test-password', ANDROID_KEY_ALIAS='test-alias',
            ANDROID_KEY_PASSWORD='test-password')
    def tearDown(self):
        self.temp.cleanup()
    def prepare(self, version='2.0.0'):
        return subprocess.run(['bash', 'tools/build_release.sh', version], cwd=self.root,
                              env=self.env, text=True, capture_output=True)
    def assert_key_removed(self):
        self.assertEqual([], list((self.root / 'runner').iterdir()))
    def test_version_reaches_gradle_and_verified_artifacts_have_checksums(self):
        result = self.prepare()
        self.assertEqual(0, result.returncode, result.stderr)
        arguments = (self.root / 'gradle-arguments').read_text().splitlines()
        for value in ('testReleaseUnitTest', 'lintRelease', 'assembleRelease',
                      '-PreleaseVersionName=2.0.0', '-PreleaseVersionCode=1042'):
            self.assertIn(value, arguments)
        apk = self.root / 'dist/sms-forwarder-2.0.0.apk'
        self.assertEqual(b'signed-apk-fixture', apk.read_bytes())
        self.assertEqual(f'{hashlib.sha256(apk.read_bytes()).hexdigest()}  {apk.name}\n',
                         (self.root / 'dist/SHA256SUMS').read_text())
        self.assertEqual('version=2.0.0\n', (self.root / 'outputs').read_text())
        self.assertNotIn('test-password', result.stdout + result.stderr)
        self.assert_key_removed()
    def test_invalid_versions_stop_before_gradle(self):
        for version in ('', 'v2.0.0', '2.0', '2.0.0;false', '02.0.0'):
            with self.subTest(version=version):
                self.assertNotEqual(0, self.prepare(version).returncode)
        self.assertFalse((self.root / 'gradle-arguments').exists())
    def test_missing_signing_secret_fails_without_building(self):
        del self.env['ANDROID_KEYSTORE_PASSWORD']
        result = self.prepare()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Missing Actions secret: ANDROID_KEYSTORE_PASSWORD', result.stderr)
        self.assertFalse((self.root / 'gradle-arguments').exists())
    def test_failed_gradle_does_not_prepare_assets_and_cleans_key(self):
        self.env['MOCK_GRADLE_FAIL'] = 'true'
        self.assertNotEqual(0, self.prepare().returncode)
        self.assertFalse((self.root / 'dist').exists())
        self.assert_key_removed()
    def test_failed_signature_prevents_assets_and_cleans_key(self):
        self.env['MOCK_SIGNATURE_FAIL'] = 'true'
        self.assertNotEqual(0, self.prepare().returncode)
        self.assertFalse((self.root / 'dist').exists())
        self.assertFalse((self.root / 'outputs').exists())
        self.assert_key_removed()
    def test_invalid_key_encoding_is_rejected_and_cleaned(self):
        self.env['ANDROID_KEYSTORE_BASE64'] = 'invalid base64!'
        self.assertNotEqual(0, self.prepare().returncode)
        self.assertFalse((self.root / 'gradle-arguments').exists())
        self.assert_key_removed()
    def test_non_numeric_run_number_cannot_enter_arithmetic(self):
        self.env['GITHUB_RUN_NUMBER'] = '42;false'
        self.assertNotEqual(0, self.prepare().returncode)
        self.assertFalse((self.root / 'gradle-arguments').exists())

if __name__ == '__main__':
    unittest.main(verbosity=2)
