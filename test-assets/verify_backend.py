"""Isolated Windows backend acceptance. No business database or protected docs are read.

Run after scripts/build.cmd -RunTests:
  python test-assets/verify_backend.py --previous-jar .local/ch34-previous-application.jar --real-model
Real provider credentials are read only from the root .env.local and never printed.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid
import zipfile


ROOT = Path(__file__).resolve().parents[1]


def digest(value):
    return hashlib.sha256(value).hexdigest()


def p95(values):
    return sorted(values)[math.ceil(len(values) * .95) - 1] if values else None


def pdf(text):
    stream = ('BT /F1 12 Tf 40 700 Td (' + text.replace('\\', '\\\\').replace('(', '\\(').replace(')', '\\)') + ') Tj ET\n').encode('ascii')
    objects = [b'<< /Type /Catalog /Pages 2 0 R >>', b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
               b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>',
               ('<< /Length ' + str(len(stream)) + ' >>\nstream\n').encode() + stream + b'endstream',
               b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>']
    output = b'%PDF-1.4\n'
    offsets = []
    for index, obj in enumerate(objects, 1):
        offsets.append(len(output))
        output += str(index).encode() + b' 0 obj\n' + obj + b'\nendobj\n'
    xref = len(output)
    output += b'xref\n0 6\n0000000000 65535 f \n'
    for offset in offsets:
        output += f'{offset:010d} 00000 n \n'.encode()
    return output + b'trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n' + str(xref).encode() + b'\n%%EOF\n'


class Runtime:
    def __init__(self, folder, secret, password, provider, jar=None, maven=False):
        self.folder = folder
        folder.mkdir(parents=True, exist_ok=True)
        settings = {'SERVER_PORT': '0', 'DB_URL': 'jdbc:h2:file:' + str(folder / 'course_rag_test').replace('\\', '/') + ';MODE=MySQL;DATABASE_TO_LOWER=TRUE',
                    'DB_USER': 'sa', 'DB_PASSWORD': '', 'JWT_SECRET': secret, 'APP_ADMIN_USERNAME': 'accept_admin',
                    'APP_ADMIN_PASSWORD': password, 'STORAGE_PROVIDER': 'local', 'CACHE_PROVIDER': 'caffeine',
                    'STORAGE_LOCAL_DIR': str(folder / 'files').replace('\\', '/')}
        env = os.environ.copy()
        for name in list(env):
            if name.startswith(('SPRING_', 'KNOWLEDGE_', 'DB_', 'STORAGE_', 'CACHE_', 'JWT_', 'APP_ADMIN_', 'DEEPSEEK_', 'LLM_')) or name in ('SERVER_PORT', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS'):
                env.pop(name, None)
        env.update(settings)
        java = str(Path(env['JAVA_HOME']) / 'bin/java.exe') if env.get('JAVA_HOME') else shutil.which('java')
        self.java = java
        version = subprocess.run([java, '-version'], capture_output=True, text=True, check=True)
        assert re.search(r'version "21\.', version.stderr + version.stdout), 'Use the configured JDK 21 environment'
        env['PATH'] = str(Path(java).parent) + os.pathsep + env.get('PATH', '')
        # Auth fixture creation shares one loopback IP. QA/list/download endpoints have no rate-limit annotation.
        env['SPRING_APPLICATION_JSON'] = json.dumps({'knowledge': {'security': {'rate-limit-enabled': False}}})
        env.update({'DEEPSEEK_API_URL': '', 'DEEPSEEK_API_KEY': '', 'DEEPSEEK_MODEL': 'deepseek-flash'})
        env.update(provider)
        self.log = folder / ('start-' + uuid.uuid4().hex[:6] + '.log')
        self.secrets = [secret, password, provider.get('DEEPSEEK_API_KEY', '')]
        self.output = self.log.open('w', encoding='utf-8')
        if maven:
            env['SPRING_PROFILES_ACTIVE'] = 'h2'
            command = ['cmd.exe', '/c', 'mvn', '-B', '-pl', 'apps/server', 'spring-boot:run']
        elif jar:
            command = [java, '-jar', str(jar), '--spring.profiles.active=bundle,h2']
        else:
            env_file = folder / 'test.env'
            env_file.write_text(''.join(key + '=' + value + '\n' for key, value in settings.items()), encoding='utf-8')
            command = ['cmd.exe', '/c', str(ROOT / 'scripts/start-local.cmd'), '-EnvFile', str(env_file)]
        self.process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=self.output, stderr=subprocess.STDOUT,
                                        creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        try:
            deadline = time.monotonic() + 90
            while time.monotonic() < deadline:
                if self.process.poll() is not None:
                    raise RuntimeError('Isolated startup exited; inspect its local log')
                text = self.log.read_text(encoding='utf-8', errors='replace')
                match = re.search(r'Tomcat started on port (\d+)', text)
                if match and 'Started KnowledgeApplication' in text:
                    self.base = 'http://127.0.0.1:' + match.group(1)
                    break
                time.sleep(.2)
            else:
                raise RuntimeError('Isolated startup deadline exceeded')
        except Exception:
            self.close()
            raise

    def close(self):
        if self.process.poll() is None:
            subprocess.run(['taskkill', '/PID', str(self.process.pid), '/T', '/F'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=20, check=True)
        self.process.wait(timeout=10)
        self.output.close()
        text = self.log.read_text(encoding='utf-8', errors='replace')
        assert all(value not in text for value in getattr(self, 'secrets', []) if value), 'A credential appeared in a local application log'

    def request(self, method, path, body=None, token=None, content_type=None):
        headers = {'Accept': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        if body is not None:
            if not isinstance(body, bytes):
                body = json.dumps(body).encode()
                content_type = 'application/json'
            headers['Content-Type'] = content_type
        try:
            response = urllib.request.urlopen(urllib.request.Request(self.base + path, data=body, headers=headers, method=method), timeout=40)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.status, response.read()

    def api(self, method, path, body=None, token=None, expected=200):
        status, raw = self.request(method, path, body, token)
        assert status == expected, (path, status, expected)
        result = json.loads(raw)
        assert result['code'] == 0, (path, result['code'])
        return result['data']

    def login(self, password, username='accept_admin'):
        # Spring logs Started before first-admin ApplicationRunner has necessarily finished.
        if username == 'accept_admin':
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                status, raw = self.request('POST', '/api/auth/login', {'username': username, 'password': password})
                if status == 200:
                    return json.loads(raw)['data']['token']
                assert status == 401, ('fixture administrator readiness', status)
                time.sleep(.1)
            raise RuntimeError('Fixture administrator initialization deadline exceeded')
        return self.api('POST', '/api/auth/login', {'username': username, 'password': password})['token']

    def upload(self, token, category, title, name, content):
        boundary = 'accept' + uuid.uuid4().hex
        payload = ('--' + boundary + '\r\nContent-Disposition: form-data; name="title"\r\n\r\n' + title + '\r\n--' + boundary
                   + '\r\nContent-Disposition: form-data; name="categoryId"\r\n\r\n' + str(category) + '\r\n--' + boundary
                   + '\r\nContent-Disposition: form-data; name="file"; filename="' + name + '"\r\nContent-Type: '
                   + ('application/pdf' if name.endswith('.pdf') else 'text/plain') + '\r\n\r\n').encode() + content + ('\r\n--' + boundary + '--\r\n').encode()
        status, raw = self.request('POST', '/api/documents', payload, token, 'multipart/form-data; boundary=' + boundary)
        assert status == 202
        document = json.loads(raw)['data']
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            state = self.api('GET', '/api/documents/' + str(document['id']) + '/status', token=token)
            if state['latestVersion']['status'] in ('READY', 'FAILED'):
                assert state['latestVersion']['status'] == 'READY'
                return document['id']
            time.sleep(.05)
        raise RuntimeError('Synthetic document parsing deadline exceeded')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--previous-jar', type=Path)
    parser.add_argument('--real-model', action='store_true')
    parser.add_argument('--maven-probe', action='store_true')
    parser.add_argument('--maven-only', action='store_true')
    parser.add_argument('--output', type=Path, help='Result path under .local; defaults preserve existing commands')
    args = parser.parse_args()
    folder = ROOT / '.local' / ('ch34-acceptance-' + uuid.uuid4().hex[:10])
    result = {'run': folder.name, 'java': '21', 'os': platform.system() + ' ' + platform.release(), 'logicalCpus': os.cpu_count(),
              'springBoot': '3.5.11', 'flyway': '11.7.2', 'documents': 0, 'users': 0, 'formats': {},
              'jarSha256': digest((ROOT / 'apps/server/target/fast-knowledge-server-1.0.0.jar').read_bytes()), 'questions': []}
    output = (ROOT / args.output).resolve() if args.output else ROOT / ('.local/ch34-maven-probe-results.json' if args.maven_only else '.local/ch34-acceptance-results.json')
    if not output.is_relative_to((ROOT / '.local').resolve()):
        raise ValueError('Acceptance results must stay under the local evidence directory')
    output.parent.mkdir(parents=True, exist_ok=True)
    def save():
        output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    secret = uuid.uuid4().hex + uuid.uuid4().hex
    password = 'Fixture_' + uuid.uuid4().hex
    provider = {}
    if args.real_model:
        local = {}
        for line in (ROOT / '.env.local').read_text(encoding='utf-8-sig').splitlines():
            if '=' in line and not line.strip().startswith('#'):
                key, value = line.split('=', 1)
                local[key.strip()] = value.strip().strip('"').strip("'")
        assert local.get('DEEPSEEK_API_URL') and local.get('DEEPSEEK_API_KEY'), 'Configure model locally first'
        provider = {key: local.get(key, 'deepseek-flash' if key.endswith('MODEL') else '') for key in ('DEEPSEEK_API_URL', 'DEEPSEEK_API_KEY', 'DEEPSEEK_MODEL')}
    if args.maven_probe or args.maven_only:
        runtime = Runtime(folder / 'maven', secret, password, {}, maven=True)
        try:
            status, raw = runtime.request('GET', '/api/actuator/health')
            assert status == 200 and json.loads(raw)['status'] == 'UP'
            assert runtime.request('GET', '/api/documents')[0] == 401
            admin = runtime.login(password)
            category = runtime.api('POST', '/api/categories', {'name': 'Empty model smoke'}, admin, 201)['id']
            content = b'Functional requirements specify services and observable system behavior.'
            identity = runtime.upload(admin, category, 'Synthetic smoke material', 'smoke.txt', content)
            status, downloaded = runtime.request('GET', '/api/documents/' + str(identity) + '/download', token=admin)
            assert status == 200 and digest(downloaded) == digest(content)
            failure = runtime.api('POST', '/api/qa', {'question': 'What do functional requirements specify?'}, admin)
            assert failure['status'] == 'FAILED' and failure['errorCode'] == 'MODEL_NOT_CONFIGURED'
            assert runtime.api('GET', '/api/documents', token=admin)['total'] == 1
            result['documents'], result['users'] = 1, 1
            result['emptyModelFailure'] = failure['errorCode']
            result['emptyModelDocumentsAndDownload'] = True
            result['springBootRun'] = True
            save()
            print('PASS spring-boot:run with isolated H2 and empty model configuration', flush=True)
        finally:
            runtime.close()
        if args.maven_only:
            result['runtime'] = 'maven-classes'
            result['migrationClassSha256'] = digest((ROOT / 'apps/server/target/classes/com/fast/knowledge/course/CourseMigrations.class').read_bytes())
            result.pop('jarSha256', None)
            save()
            return
    fixtures = json.loads((ROOT / 'test-assets/b05/evaluation.json').read_text(encoding='utf-8'))
    identities, originals = {}, {}
    runtime = Runtime(folder / 'runtime', secret, password, {}, jar=args.previous_jar)
    try:
        token = runtime.login(password)
        category = runtime.api('POST', '/api/categories', {'name': 'Original synthetic acceptance'}, token, 201)['id']
        for index, document in enumerate(fixtures['documents']):
            # Preserve all fixture content, including Chinese; ASCII-only examples also exercise PDF.
            is_pdf = document['text'].isascii() and index % 2 == 0
            content = pdf(document['text']) if is_pdf else document['text'].encode('utf-8')
            name = document['key'] + ('.pdf' if is_pdf else '.txt')
            result['formats'][document['key']] = 'PDF' if is_pdf else 'TXT'
            identity = runtime.upload(token, category, document['title'], name, content)
            identities[document['key']], originals[identity] = identity, digest(content)
        result['documents'] = len(identities)
        failed = runtime.api('POST', '/api/qa', {'question': fixtures['questions'][0]['question']}, token)
        assert failed['status'] == 'FAILED' and failed['errorCode'] == 'MODEL_NOT_CONFIGURED'
        result['emptyModelFailure'] = failed['errorCode']
        if args.previous_jar:
            runtime.close()
            runtime = Runtime(folder / 'runtime', secret, password, provider)
            assert runtime.api('GET', '/api/auth/me', token=token)['role'] == 'ADMIN'
            assert runtime.api('GET', '/api/qa/' + str(failed['qaId']), token=token)['errorCode'] == 'MODEL_NOT_CONFIGURED'
            result['legacyUpgradePreservedAccountTokenHistory'] = True
        elif provider:
            runtime.close()
            runtime = Runtime(folder / 'runtime', secret, password, provider)
        for identity, expected in originals.items():
            status, raw = runtime.request('GET', '/api/documents/' + str(identity) + '/download', token=token)
            assert status == 200 and digest(raw) == expected
        with urllib.request.urlopen(urllib.request.Request(runtime.base + '/', headers={'Accept': 'text/html'}), timeout=10) as home:
            assert home.status == 200 and b'<html' in home.read().lower()
            result['homepageHttp'] = 200
        result['downloadHashesPreserved'] = True
        print('PASS twelve authorized synthetic PDF/TXT originals preserved across startup/upgrade', flush=True)
        if args.real_model:
            for index, question in enumerate(fixtures['questions'], 1):
                started = time.monotonic()
                answer = runtime.api('POST', '/api/qa', {'question': question['question']}, token)
                elapsed = round((time.monotonic() - started) * 1000)
                valid = answer['status'] == 'NO_EVIDENCE' if question['expected'] is None else answer['status'] == 'SUCCESS' and identities[question['expected']] in [source['documentId'] for source in answer['sources']]
                for source in answer['sources']:
                    chunk = runtime.api('GET', '/api/documents/' + str(source['documentId']) + '/chunks?chunkIndex=' + str(source['chunkIndex']), token=token)['items'][0]
                    assert chunk['versionId'] == source['versionId'] and chunk['content'].startswith(source['excerpt']) and chunk['pageNo'] == source['pageNo']
                result['questions'].append({'index': index, 'question': question['question'], 'expected': question['expected'], 'passed': valid, 'wallMs': elapsed, 'result': answer})
                save()
                print(('PASS' if valid else 'FAIL') + ' fixed Q' + str(index) + ' ' + answer['status'] + ' ' + str(elapsed) + 'ms', flush=True)
            result['positivePassed'] = sum(item['passed'] for item in result['questions'] if item['expected'] is not None)
            result['negativePassed'] = sum(item['passed'] for item in result['questions'] if item['expected'] is None)
        tokens = []
        for index in range(20):
            username = 'accept_student_' + str(index)
            runtime.api('POST', '/api/auth/register', {'username': username, 'password': password}, expected=201)
            tokens.append(runtime.login(password, username))
        result['users'] = len(tokens)
        gate = threading.Barrier(20)
        def browse(index):
            gate.wait(timeout=15)
            times = []
            for iteration in range(5):
                started = time.monotonic()
                data = runtime.api('GET', '/api/documents' + ('?keyword=testing' if iteration % 2 else ''), token=tokens[index])
                assert data['total'] >= 1
                times.append(round((time.monotonic() - started) * 1000))
            return times
        with ThreadPoolExecutor(max_workers=20) as pool:
            times = [value for group in pool.map(browse, range(20)) for value in group]
        result['browse'] = {'concurrentUsers': 20, 'samples': len(times), 'p95Ms': p95(times), 'maxMs': max(times), 'errors': 0}
        print('PASS twenty-user list/search P95=' + str(p95(times)) + 'ms', flush=True)
        if args.real_model:
            positive = [item for item in fixtures['questions'] if item['expected'] is not None]
            gate = threading.Barrier(20)
            def ask(index):
                item = positive[index % len(positive)]
                gate.wait(timeout=15)
                started = time.monotonic()
                try:
                    answer = runtime.api('POST', '/api/qa', {'question': item['question']}, tokens[index])
                except Exception as error:
                    # Measurement failure, explicitly distinct from a saved server QA record.
                    answer = {'status': 'HTTP_FAILURE', 'errorCode': type(error).__name__, 'sources': []}
                elapsed = round((time.monotonic() - started) * 1000)
                valid = answer['status'] == 'SUCCESS' and identities[item['expected']] in [source['documentId'] for source in answer['sources']]
                return {'user': index, 'question': item['question'], 'wallMs': elapsed, 'passed': valid, 'result': answer}
            with ThreadPoolExecutor(max_workers=20) as pool:
                samples = list(pool.map(ask, range(20)))
            success = [item['wallMs'] for item in samples if item['result']['status'] == 'SUCCESS']
            failed = [item for item in samples if item['result']['status'] in ('FAILED', 'HTTP_FAILURE')]
            refused = [item for item in samples if item['result']['status'] == 'NO_EVIDENCE']
            result['qaConcurrent'] = {'concurrentUsers': 20, 'samples': samples, 'successCount': len(success), 'failedCount': len(failed), 'refusalCount': len(refused),
                                      'successP95Ms': p95(success), 'failedErrorCodes': [item['result']['errorCode'] for item in failed], 'errorRate': len(failed) / 20,
                                      'successLatencyMs': success, 'refusalLatencyMs': [item['wallMs'] for item in refused], 'failedLatencyMs': [item['wallMs'] for item in failed]}
            # Every concurrent response is also checked against actual saved original chunks.
            for item in samples:
                for source in item['result']['sources']:
                    chunk = runtime.api('GET', '/api/documents/' + str(source['documentId']) + '/chunks?chunkIndex=' + str(source['chunkIndex']), token=tokens[item['user']])['items'][0]
                    assert chunk['versionId'] == source['versionId'] and chunk['content'].startswith(source['excerpt']) and chunk['pageNo'] == source['pageNo']
            print('RESULT twenty-user real QA success=' + str(len(success)) + '/20 P95=' + str(p95(success)) + 'ms', flush=True)
            successful = next((item['result'] for item in result['questions'] if item['result']['status'] == 'SUCCESS'), None)
            if successful:
                qa = successful['qaId']
                source = successful['sources'][0]
                for _ in range(2):
                    assert runtime.api('PUT', '/api/qa/' + str(qa) + '/favorite', token=token)['favorited']
                assert runtime.api('GET', '/api/favorites', token=token)['total'] == 1
                first = runtime.api('POST', '/api/qa/' + str(qa) + '/feedback', {'rating': 'HELPFUL'}, token)
                latest = runtime.api('POST', '/api/qa/' + str(qa) + '/feedback', {'rating': 'UNHELPFUL', 'reason': 'Synthetic current feedback'}, token)
                assert first['id'] == latest['id']
                detail = runtime.api('GET', '/api/qa/' + str(qa), token=token)
                assert detail['favorited'] and detail['feedback']['rating'] == 'UNHELPFUL'
                assert runtime.request('GET', '/api/qa/' + str(qa), token=tokens[0])[0] == 404
                assert runtime.request('GET', '/api/admin/stats', token=tokens[0])[0] == 403
                stats = runtime.api('GET', '/api/admin/stats', token=token)
                assert stats['documentCount'] == 12 and stats['userCount'] == 21
                assert stats['qaSuccessCount'] == sum(item['result']['status'] == 'SUCCESS' for item in result['questions']) + len(success)
                assert stats['qaNoEvidenceCount'] == sum(item['result']['status'] == 'NO_EVIDENCE' for item in result['questions']) + len(refused)
                assert stats['qaFailedCount'] == 1 + sum(item['result']['status'] == 'FAILED' for item in result['questions']) + sum(item['result']['status'] == 'FAILED' for item in failed)
                assert stats['feedbackHelpfulCount'] == 0 and stats['feedbackUnhelpfulCount'] == 1
                for _ in range(2):
                    assert not runtime.api('DELETE', '/api/qa/' + str(qa) + '/favorite', token=token)['favorited']
                identity = source['documentId']
                runtime.api('PATCH', '/api/documents/' + str(identity), {'title': 'Renamed synthetic material', 'categoryId': category}, token)
                boundary = 'replacement' + uuid.uuid4().hex
                new_bytes = b'Updated synthetic material after the acceptance measurement.'
                payload = ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="new.txt"\r\nContent-Type: text/plain\r\n\r\n').encode() + new_bytes + ('\r\n--' + boundary + '--\r\n').encode()
                assert runtime.request('PUT', '/api/documents/' + str(identity) + '/file', payload, token, 'multipart/form-data; boundary=' + boundary)[0] == 202
                deadline = time.monotonic() + 30
                while time.monotonic() < deadline:
                    state = runtime.api('GET', '/api/documents/' + str(identity) + '/status', token=token)
                    if state['latestVersion']['status'] in ('READY', 'FAILED'):
                        assert state['latestVersion']['status'] == 'READY'
                        break
                    time.sleep(.05)
                else:
                    raise RuntimeError('Replacement deadline exceeded')
                snapshot_path = '/api/qa/' + str(qa) + '/sources/' + str(source['sourceId'])
                snapshot = runtime.api('GET', snapshot_path, token=token)
                assert snapshot['availability'] == 'UPDATED' and snapshot['excerpt'] == source['excerpt'] and snapshot['documentTitle'] == source['documentTitle']
                assert runtime.request('GET', '/api/documents/' + str(identity) + '/download', token=token)[1] == new_bytes
                for _ in range(2):
                    runtime.api('DELETE', '/api/documents/' + str(identity), token=token)
                assert runtime.request('GET', '/api/documents/' + str(identity) + '/download', token=tokens[0])[0] == 404
                assert runtime.api('GET', snapshot_path, token=token)['availability'] == 'OFFLINE'
                result['finalWorkflow'] = {'favoriteIdempotency': True, 'feedbackUpsert': True, 'ownerIsolation': True,
                                           'statistics': stats, 'replacementAndOffline': True, 'historySnapshot': True}
                print('PASS final JAR owner isolation, favorites, feedback, statistics and replacement/offline snapshots', flush=True)
        result['passed'] = result['browse']['p95Ms'] <= 2000 and (not args.real_model or (result['positivePassed'] >= 12 and result['negativePassed'] == 5
                           and result['qaConcurrent']['successCount'] == 20 and all(item['passed'] for item in result['qaConcurrent']['samples']) and result['qaConcurrent']['successP95Ms'] <= 30000))
        save()
        assert result['passed'], 'Acceptance gate failed; inspect local structured results'
    finally:
        runtime.close()
    # Force-stop was intentional: verify acknowledged writes survived, using a separate read-only JVM.
    fields = {'documents': 'SELECT COUNT(*) FROM kb_document', 'users': 'SELECT COUNT(*) FROM kb_user',
              'versions': 'SELECT COUNT(*) FROM kb_document_version', 'hashedVersions': 'SELECT COUNT(*) FROM kb_document_version WHERE sha256 IS NOT NULL',
              'sources': 'SELECT COUNT(*) FROM kb_qa_source', 'favorites': 'SELECT COUNT(*) FROM kb_favorite',
              'feedback': 'SELECT COUNT(*) FROM kb_feedback', 'success': "SELECT COUNT(*) FROM kb_qa_record WHERE status='SUCCESS'",
              'noEvidence': "SELECT COUNT(*) FROM kb_qa_record WHERE status='NO_EVIDENCE'", 'failed': "SELECT COUNT(*) FROM kb_qa_record WHERE status='FAILED'",
              'offline': 'SELECT COUNT(*) FROM kb_document WHERE enabled=0'}
    sql = "SELECT CONCAT('POST_SHUTDOWN='," + ",',',".join('(' + value + ')' for value in fields.values()) + ') AS audit_marker'
    with zipfile.ZipFile(ROOT / 'apps/server/target/fast-knowledge-server-1.0.0.jar') as archive:
        h2_name = next(name for name in archive.namelist() if re.fullmatch(r'BOOT-INF/lib/h2-[0-9.]+\.jar', name))
        h2_jar = folder / 'h2-readonly-tool.jar'
        h2_jar.write_bytes(archive.read(h2_name))
    database_url = 'jdbc:h2:file:' + str(runtime.folder / 'course_rag_test').replace('\\', '/') + ';MODE=MySQL;DATABASE_TO_LOWER=TRUE;ACCESS_MODE_DATA=r;IFEXISTS=TRUE'
    audit = subprocess.run([runtime.java, '-cp', str(h2_jar), 'org.h2.tools.Shell', '-url', database_url, '-user', 'sa', '-password', '', '-sql', sql],
                           cwd=ROOT, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=30,
                           creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    (folder / 'post-shutdown-audit.log').write_text(audit.stdout + audit.stderr, encoding='utf-8')
    match = re.search(r'POST_SHUTDOWN=([0-9,]+)', audit.stdout)
    assert audit.returncode == 0 and match, 'Read-only post-shutdown audit could not run'
    counts = dict(zip(fields, map(int, match.group(1).split(','))))
    workflow = bool(result.get('finalWorkflow'))
    qa_records = [item['result'] for item in result['questions']] + [item['result'] for item in result.get('qaConcurrent', {}).get('samples', [])]
    expected = {'documents': 12, 'users': 21, 'versions': 12 + int(workflow),
                'hashedVersions': (0 if args.previous_jar else 12) + int(workflow),
                'sources': sum(len(item['sources']) for item in qa_records), 'favorites': 0, 'feedback': int(workflow),
                'success': sum(item['status'] == 'SUCCESS' for item in qa_records), 'noEvidence': sum(item['status'] == 'NO_EVIDENCE' for item in qa_records),
                'failed': 1 + sum(item['status'] == 'FAILED' for item in qa_records), 'offline': int(workflow)}
    result['postShutdown'] = {'counts': counts, 'expected': expected, 'passed': counts == expected}
    result['passed'] = result['passed'] and counts == expected
    save()
    assert result['postShutdown']['passed'], 'Acknowledged writes did not survive process termination'
    print('PASS separate read-only JVM verified all final records and new file hashes after force-stop', flush=True)
    print('PASS isolated acceptance processes stopped; business database untouched', flush=True)


if __name__ == '__main__':
    main()
