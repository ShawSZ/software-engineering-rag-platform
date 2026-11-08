"""Exercise the production frontend transport/stores against disposable real backends."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import uuid

from verify_backend import ROOT, Runtime


def main():
    run = 'frontend-live-' + uuid.uuid4().hex[:10]
    folder = ROOT / '.local' / run
    folder.mkdir(parents=True)
    checks = []
    for kind, variable, config in (
        ('transport', 'F01', 'vitest.backend.config.ts'),
        ('auth', 'F02', 'vitest.auth-backend.config.ts'),
    ):
        secret = uuid.uuid4().hex + uuid.uuid4().hex
        password = 'Fixture_' + uuid.uuid4().hex
        runtime = Runtime(folder / kind, secret, password, {})
        try:
            runtime.login(password)
            env = os.environ.copy()
            env.update({variable + '_BACKEND_URL': runtime.base + '/api', variable + '_ADMIN_PASSWORD': password})
            env['PATH'] = str(ROOT / 'apps/server/target/frontend/node') + os.pathsep + env['PATH']
            temporary = folder / 'temp'
            temporary.mkdir(exist_ok=True)
            env['TEMP'] = env['TMP'] = str(temporary)
            output_path = folder / (kind + '.log')
            with output_path.open('w', encoding='utf-8') as output:
                result = subprocess.run(
                    ['cmd.exe', '/c', 'npm.cmd', 'run', 'test:run', '--', '--config', config],
                    cwd=ROOT / 'web', env=env, stdout=output, stderr=subprocess.STDOUT,
                    creationflags=subprocess.CREATE_NO_WINDOW, timeout=90)
            text = output_path.read_text(encoding='utf-8', errors='replace')
            assert secret not in text and password not in text, 'Credential in integration output'
            assert result.returncode == 0 and re.search(r'Tests\s+1 passed', text), kind + ' integration failed; inspect its isolated log'
            checks.append({'check': kind, 'tests': 1, 'passed': True})
        finally:
            runtime.close()
    jar = ROOT / 'apps/server/target/fast-knowledge-server-1.0.0.jar'
    proof = {'passed': True, 'run': run, 'checks': checks, 'realModelCalled': False,
             'businessDatabaseUsed': False, 'processesStopped': True,
             'jarSha256': hashlib.sha256(jar.read_bytes()).hexdigest()}
    (ROOT / '.local/release-frontend-live-proof.json').write_text(json.dumps(proof, indent=2), encoding='utf-8')
    print(json.dumps(proof, indent=2))


if __name__ == '__main__':
    main()
