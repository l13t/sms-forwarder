#!/usr/bin/env python3
"""Regression tests for SDK selection and AVD lifecycle without booting Android."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SOURCE = Path(__file__).resolve().with_name('capture_screenshots.sh')

class ScreenshotLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='sms-capture-check-')
        self.root = Path(self.temp.name)
        (self.root / 'tools').mkdir()
        (self.root / 'runtime').mkdir()
        shutil.copyfile(SOURCE, self.root / 'tools/capture_screenshots.sh')
        selected = self.root / 'sdk/cmdline-tools/22.0/bin'
        self.executable(selected / 'avdmanager', '''import json, os, sys
from pathlib import Path
if sys.argv[1:3] == ['list', 'avd']:
    print('AVD diagnostics'); sys.exit(0)
assert sys.argv[1:3] == ['create', 'avd']
if os.getenv('MOCK_CREATE_FAIL') == 'true':
    print('AVD creation failed'); sys.exit(3)
home = Path(os.environ['ANDROID_AVD_HOME'])
assert home == Path(os.environ['ANDROID_USER_HOME']) / 'avd'
assert os.environ['ANDROID_EMULATOR_HOME'] == os.environ['ANDROID_USER_HOME']
assert 'ANDROID_SDK_HOME' not in os.environ
name = sys.argv[sys.argv.index('--name') + 1]
path = Path(sys.argv[sys.argv.index('--path') + 1])
assert path == home / (name + '.avd')
path.mkdir(parents=True)
(path / 'config.ini').write_text('AvdId=' + name)
if os.getenv('MOCK_MISSING_INDEX') != 'true':
    (home / (name + '.ini')).write_text('path=' + str(path))
Path('avd-trace.json').write_text(json.dumps({'manager': __file__, 'avd_home': str(home)}))
''')
        self.executable(self.root / 'sdk/cmdline-tools/latest/bin/avdmanager',
                        "raise SystemExit('Old preinstalled latest tools must not be selected')\n")
        self.executable(self.root / 'sdk/emulator/emulator', '''import json, os, sys, time
from pathlib import Path
home = Path(os.environ['ANDROID_AVD_HOME'])
if '-list-avds' in sys.argv:
    if os.getenv('MOCK_INVISIBLE_AVD') != 'true':
        print('\\n'.join(p.stem for p in home.glob('*.ini')))
    sys.exit(0)
name = sys.argv[sys.argv.index('-avd') + 1]
assert (home / (name + '.ini')).is_file()
Path('emulator-trace.json').write_text(json.dumps({'avd_home': str(home)}))
while True:
    time.sleep(0.05)
''')
        self.executable(self.root / 'sdk/platform-tools/adb', '''import os, sys
from pathlib import Path
args = sys.argv[1:]
if args[:1] == ['-s']:
    args = args[2:]
if args == ['devices']:
    print('List of devices attached')
    if os.getenv('MOCK_DEVICE') == 'true': print('other-device\\tdevice')
elif args[:3] == ['shell', 'getprop', 'sys.boot_completed']:
    print('1')
elif args[:1] == ['pull']:
    target = Path(args[-1])
    for language in ('en', 'uk'):
        for theme in ('light', 'dark'):
            for name in ('status', 'webhooks', 'rules', 'history', 'settings', 'about', 'rule-editor'):
                path = target / f'{language}-{theme}' / f'{name}.png'
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b'\\x89PNG\\r\\n\\x1a\\nfixture')
''')
        self.executable(self.root / 'gradlew', '''import json, os, sys
from pathlib import Path
with Path('gradle-trace.jsonl').open('a') as file:
    file.write(json.dumps(sys.argv[1:]) + '\\n')
if os.getenv('MOCK_GRADLE_FAIL') == 'true': sys.exit(42)
''')
        self.env = dict(os.environ, ANDROID_HOME=str(self.root / 'sdk'),
                        PATH=str(selected) + os.pathsep + os.environ['PATH'],
                        RUNNER_TEMP=str(self.root / 'runtime'),
                        ANDROID_AVD_HOME=str(self.root / 'caller-avds'),
                        ANDROID_USER_HOME=str(self.root / 'caller-user'),
                        ANDROID_EMULATOR_HOME=str(self.root / 'caller-emulator'),
                        ANDROID_SDK_HOME=str(self.root / 'legacy-home'))
        (self.root / 'caller-avds').mkdir()
        (self.root / 'caller-avds/preserve.txt').write_text('existing caller AVD data')
    def executable(self, path, code):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('#!/usr/bin/env python3\n' + code)
        path.chmod(0o700)
    def tearDown(self):
        self.temp.cleanup()
    def capture(self):
        return subprocess.run(['bash', 'tools/capture_screenshots.sh'], cwd=self.root,
                              env=self.env, capture_output=True, text=True, timeout=20)
    def assert_clean(self):
        self.assertEqual([], list((self.root / 'runtime').glob('sms-forwarder-docs.*')))
        self.assertEqual('existing caller AVD data', (self.root / 'caller-avds/preserve.txt').read_text())
    def test_path_tools_and_both_avd_lookups_use_the_same_private_directory(self):
        result = self.capture()
        self.assertEqual(0, result.returncode, result.stderr)
        manager = json.loads((self.root / 'avd-trace.json').read_text())
        emulator = json.loads((self.root / 'emulator-trace.json').read_text())
        self.assertIn('/cmdline-tools/22.0/bin/avdmanager', manager['manager'])
        self.assertEqual(manager['avd_home'], emulator['avd_home'])
        self.assertTrue(manager['avd_home'].startswith(str(self.root / 'runtime')))
        self.assertEqual(2, len((self.root / 'gradle-trace.jsonl').read_text().splitlines()))
        self.assertEqual(28, len(list((self.root / 'artifacts/screenshots').rglob('*.png'))))
        self.assert_clean()
    def test_success_exit_without_avd_index_stops_before_emulator_boot(self):
        self.env['MOCK_MISSING_INDEX'] = 'true'
        result = self.capture()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('did not create the expected AVD index', result.stderr)
        self.assertFalse((self.root / 'emulator-trace.json').exists())
        self.assert_clean()
    def test_avd_not_visible_to_emulator_is_reported_before_boot(self):
        self.env['MOCK_INVISIBLE_AVD'] = 'true'
        result = self.capture()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Emulator cannot resolve', result.stderr)
        self.assertFalse((self.root / 'gradle-trace.jsonl').exists())
        self.assert_clean()
    def test_creation_failure_preserves_log_and_cleans_temporary_home(self):
        self.env['MOCK_CREATE_FAIL'] = 'true'
        result = self.capture()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('AVD creation failed', result.stdout)
        self.assertIn('AVD creation failed', (self.root / 'runtime/sms-forwarder-avd.log').read_text())
        self.assert_clean()
    def test_test_failure_still_stops_emulator_and_cleans_temporary_home(self):
        self.env['MOCK_GRADLE_FAIL'] = 'true'
        result = self.capture()
        self.assertNotEqual(0, result.returncode)
        self.assert_clean()
    def test_connected_device_is_left_untouched(self):
        self.env['MOCK_DEVICE'] = 'true'
        result = self.capture()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Disconnect other devices', result.stderr)
        self.assertFalse((self.root / 'avd-trace.json').exists())
        self.assert_clean()

if __name__ == '__main__':
    unittest.main(verbosity=2)
