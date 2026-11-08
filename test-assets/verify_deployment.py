"""Windows deployment regression in disposable directories and H2 databases.

Run with JDK 21 selected after scripts/build.cmd -RunTests.
No protected docs, existing local config, or business database is copied or changed.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

from verify_backend import ROOT, Runtime


def clean_environment():
    env = os.environ.copy()
    for name in list(env):
        if name.startswith(('SPRING_', 'KNOWLEDGE_', 'DB_', 'STORAGE_', 'CACHE_', 'JWT_',
                            'APP_ADMIN_', 'DEEPSEEK_', 'LLM_', 'COURSE_DEV_')) or name in (
                                'SERVER_PORT', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS'):
            env.pop(name, None)
    return env


def main():
    folder = ROOT / '.local' / ('deployment-' + uuid.uuid4().hex[:10])
    folder.mkdir(parents=True)
    env = clean_environment()
    checks = []

    def run(label, command, expected=0, cwd=ROOT, timeout=30, environment=env):
        result = subprocess.run(command, cwd=cwd, env=environment, capture_output=True,
                                timeout=timeout, creationflags=subprocess.CREATE_NO_WINDOW)
        (folder / (label + '.log')).write_bytes(result.stdout + result.stderr)
        assert (result.returncode == 0) == (expected == 0), (label, result.returncode)
        checks.append({'check': label, 'passed': True})
        return result

    ps = ['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File']
    modern_shell = shutil.which('pwsh')
    portable = folder / 'portable project'
    (portable / 'scripts').mkdir(parents=True)
    for name in ('local-common.ps1', 'prepare-idea.ps1', 'prepare-idea.cmd'):
        shutil.copyfile(ROOT / 'scripts' / name, portable / 'scripts' / name)
    dist = portable / 'web/dist'
    dist.mkdir(parents=True)
    (dist / 'index.html').write_text('<div id="app">fixture</div>', encoding='utf-8')
    local_env = portable / 'fixture.env'
    local_env.write_text('SERVER_PORT=18089\nSTORAGE_LOCAL_DIR=./files with spaces/\n', encoding='utf-8')

    # Environment values are data. A malformed later line must not partially apply earlier lines.
    (portable / 'valid.env').write_text('COURSE_DEPLOY_A=file\nCOURSE_DEPLOY_LITERAL="$(Get-Date)"\nCOURSE_DEPLOY_BLANK=\n', encoding='utf-8')
    (portable / 'bad.env').write_text('COURSE_DEPLOY_PARTIAL=must-not-apply\nnot an assignment\n', encoding='utf-8')
    (portable / 'protected.env').write_text('COURSE_DEPLOY_PARTIAL=must-not-apply\nPATH=invalid\n', encoding='utf-8')
    environment_test = portable / 'environment-test.ps1'
    environment_test.write_text("""$ErrorActionPreference='Stop'
. ./scripts/local-common.ps1
$env:COURSE_DEPLOY_A='process'
[Environment]::SetEnvironmentVariable('COURSE_DEPLOY_BLANK',$null,'Process')
Import-LocalEnvironment ./valid.env -Required
if ($env:COURSE_DEPLOY_A -ne 'process' -or $env:COURSE_DEPLOY_LITERAL -ne '$(Get-Date)') { throw 'data/precedence' }
if ([Environment]::GetEnvironmentVariables('Process').Contains('COURSE_DEPLOY_BLANK')) { throw 'empty value must not mask application defaults' }
foreach ($file in @('./bad.env','./protected.env')) {
    $rejected=$false
    try { Import-LocalEnvironment $file -Required } catch { $rejected=$true }
    if (-not $rejected -or $env:COURSE_DEPLOY_PARTIAL) { throw 'atomic validation' }
}
Import-LocalEnvironment ./optional-missing.env
$rejected=$false
try { Import-LocalEnvironment ./required-missing.env -Required } catch { $rejected=$true }
if (-not $rejected) { throw 'required path' }
""", encoding='utf-8-sig')
    run('environment-validation', ps + [str(environment_test)], cwd=portable)
    if modern_shell:
        run('environment-validation-powershell7', [modern_shell, '-NoProfile', '-File', str(environment_test)], cwd=portable)
    run('idea-portable-first', ps + [str(portable / 'scripts/prepare-idea.ps1'), '-EnvFile', str(local_env)])
    key = (portable / '.local/jwt-secret').read_bytes()
    assert len(key) >= 32
    properties = (portable / '.local/idea.properties').read_text(encoding='ascii')
    assert 'STORAGE_LOCAL_DIR=./files\\ with\\ spaces/' in properties
    assert 'JWT_SECRET=' in properties
    assert (portable / '.local/idea-web-classpath/static/index.html').read_bytes() == (dist / 'index.html').read_bytes()
    run_file = portable / '.run/KnowledgeApplication_IDEA.run.xml'
    config = ET.parse(run_file)
    options = {item.attrib['name']: item.attrib['value'] for item in config.findall('.//configuration/option')}
    assert options['ACTIVE_PROFILES'] == 'bundle,h2'
    assert options['ALTERNATIVE_JRE_PATH'] == env['JAVA_HOME']
    assert options['WORKING_DIRECTORY'] == 'file://$PROJECT_DIR$'
    assert key.decode().strip() not in run_file.read_text(encoding='utf-8')
    original_config = run_file.read_bytes()
    run('idea-portable-repeat', ps + [str(portable / 'scripts/prepare-idea.ps1'), '-EnvFile', str(local_env)])
    assert (portable / '.local/jwt-secret').read_bytes() == key
    assert run_file.read_bytes() == original_config
    checks.append({'check': 'idea-link-key-config-preserved', 'passed': True})
    run('explicit-missing-start-env', ps + [str(ROOT / 'scripts/start-local.ps1'), '-EnvFile', str(folder / 'absent.env')], expected=1)
    run('explicit-missing-dev-env', ps + [str(ROOT / 'scripts/dev.ps1'), '-EnvFile', str(folder / 'absent.env')], expected=1)
    invalid_env = folder / 'invalid-port.env'
    invalid_env.write_text('SERVER_PORT=99999\n', encoding='utf-8')
    run('invalid-port-rejected', ps + [str(ROOT / 'scripts/start-local.ps1'), '-EnvFile', str(invalid_env)], expected=1)
    invalid_java = env.copy()
    invalid_java['JAVA_HOME'] = str(folder / 'not-a-jdk')
    run('invalid-jdk-rejected', ps + [str(ROOT / 'scripts/start-local.ps1')], expected=1, environment=invalid_java)
    no_frontend = folder / 'unbuilt'
    (no_frontend / 'scripts').mkdir(parents=True)
    for name in ('local-common.ps1', 'prepare-idea.ps1'):
        shutil.copyfile(ROOT / 'scripts' / name, no_frontend / 'scripts' / name)
    run('idea-unbuilt-rejected', ps + [str(no_frontend / 'scripts/prepare-idea.ps1')], expected=1)
    assert not (no_frontend / '.local/idea.properties').exists()

    runtime = None
    dev = None
    dev_output = None
    secret = uuid.uuid4().hex + uuid.uuid4().hex
    password = 'Deploy_' + uuid.uuid4().hex
    try:
        runtime = Runtime(folder / 'runtime', secret, password, {})
        token = runtime.login(password)
        category = runtime.api('POST', '/api/categories', {'name': 'Deployment fixture'}, token, expected=201)
        content = b'Deployment checks verify local startup, storage and recovery.\n'
        doc_id = runtime.upload(token, category['id'], 'Deployment fixture', 'deployment.txt', content)
        status, raw = runtime.request('GET', f'/api/documents/{doc_id}/download', token=token)
        assert status == 200 and raw == content
        result = runtime.api('POST', '/api/qa', {'question': 'What do deployment checks verify?'}, token)
        assert result['status'] == 'FAILED' and result['errorCode'] == 'MODEL_NOT_CONFIGURED'
        run('live-package-health', ['cmd.exe', '/c', str(ROOT / 'scripts/check-local.cmd'), '-BaseUrl', runtime.base])
        if modern_shell:
            run('live-package-health-powershell7', [modern_shell, '-NoProfile', '-File', str(ROOT / 'scripts/check-local.ps1'), '-BaseUrl', runtime.base])

        # Exercise the real Vite proxy against a non-default backend port.
        dev_env = folder / 'dev.env'
        dev_env.write_text('SERVER_PORT=' + runtime.base.rsplit(':', 1)[1] + '\n', encoding='utf-8')
        dev_output = (folder / 'dev.log').open('wb')
        dev = subprocess.Popen(['cmd.exe', '/c', str(ROOT / 'scripts/dev.cmd'), '-EnvFile', str(dev_env)],
                               cwd=ROOT, env=env, stdout=dev_output, stderr=subprocess.STDOUT,
                               creationflags=subprocess.CREATE_NO_WINDOW)
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            assert dev.poll() is None, 'Vite failed; inspect isolated dev log (port may already be in use)'
            text = (folder / 'dev.log').read_text(encoding='utf-8', errors='replace')
            if re.search(r'Local:\s+http://localhost:5174/', text): break
            time.sleep(.2)
        else: raise AssertionError('Vite startup timeout')
        import urllib.request
        with urllib.request.urlopen('http://localhost:5174/api/actuator/health', timeout=10) as response:
            assert response.status == 200 and json.load(response)['status'] == 'UP'
        checks.append({'check': 'custom-port-real-vite-proxy', 'passed': True})
        run('vite-port-conflict-rejected', ['cmd.exe', '/c', str(ROOT / 'scripts/dev.cmd'), '-EnvFile', str(dev_env)], expected=1)
        runtime.api('POST', '/api/auth/logout', token=token)
        runtime.close()
        runtime = Runtime(folder / 'runtime', secret, password, {})
        assert runtime.request('GET', '/api/auth/me', token=token)[0] == 401
        token = runtime.login(password)
        status, raw = runtime.request('GET', f'/api/documents/{doc_id}/download', token=token)
        assert status == 200 and raw == content
        checks.append({'check': 'original-start-restart-data-and-logout-persistence', 'passed': True})
        run('invalid-health-url-rejected', ps + [str(ROOT / 'scripts/check-local.ps1'), '-BaseUrl', 'file:///not-http'], expected=1)
    finally:
        if dev is not None:
            if dev.poll() is None:
                subprocess.run(['taskkill', '/PID', str(dev.pid), '/T', '/F'], stdout=subprocess.DEVNULL,
                               stderr=subprocess.DEVNULL, timeout=20, check=True)
            dev.wait(timeout=10)
        if dev_output is not None: dev_output.close()
        if runtime is not None: runtime.close()

    for path in folder.glob('*.log'):
        raw = path.read_bytes()
        assert secret.encode() not in raw and password.encode() not in raw
    jar = ROOT / 'apps/server/target/fast-knowledge-server-1.0.0.jar'
    result = {'passed': True, 'run': folder.name, 'checks': checks, 'businessDatabaseUsed': False,
              'jarSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
              'realModelCalled': False, 'processesStopped': True, 'existingConfigurationOverwritten': False,
              'differentPhysicalDeviceTested': False}
    (ROOT / '.local/deployment-verification.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
