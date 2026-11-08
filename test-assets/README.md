# Backend acceptance fixtures

Windows deployment regression (JDK 21 / Python 3, after the build):

```powershell
python test-assets/verify_deployment.py
python test-assets/verify_frontend.py
```

It creates disposable local project directories and H2 databases, checks dotenv validation, portable IDEA setup, the original startup command, the real Vite proxy on a custom backend port, and restart persistence. It stops its own processes and never copies private project documents or reads the business database. Port 5174 must be free for the development proxy check.

The frontend verifier runs the real Axios transport and account/store integration suites against separately isolated backends with model credentials blank. It uses the Node runtime prepared by the build. Both verifiers save local evidence under `.local/` and stop their own backend processes.

Development dependency compatibility after `npm ci` can be checked with the bundled Node:

```powershell
& .\apps\server\target\frontend\node\node.exe .\test-assets\verify_lint_tools.mjs
```

This checks that lint still rejects course API `any` and Vue prop mutations, generated declarations stay ignored, and the patched glob/EditorConfig APIs match normal project files. `minimatch` and `glob` overrides keep known vulnerable transitive versions out of the development tool graph; review these overrides when upstream tool ranges adopt the fixed major versions.

The `b03/` and `b05/` directories contain original synthetic material dedicated to CC0-1.0. They are independent of private project documentation and real classroom files.

After building with the existing `scripts/build.cmd -RunTests`, run in a JDK 21 / Python 3 environment:

```powershell
python test-assets/verify_backend.py
python test-assets/verify_backend.py --maven-only
python test-assets/verify_backend.py --real-model
```

The first command checks isolated uploads/downloads, missing model configuration and 20-user list/search behavior. The second checks the documented Maven run command in a separate database. The third additionally makes real provider calls: the fixed 20-question set and one 20-user question wave. It reads the existing root `.env.local` privately and sends only synthetic fixture questions and evidence to the configured provider. Credentials and tokens are never printed or placed in the JSON results.

An optional `--previous-jar <local-path>` starts a previously built application on a newly created test database, then upgrades that same database using the current application's original `start-local.cmd` command. It verifies preserved accounts, JWT access, history and all original file hashes. The prior JAR must be the trusted course application compatible with these fixtures.

Every run creates a unique `.local/ch34-acceptance-*` directory with a `course_rag_test` H2 file, local files and private fixture configuration. It never opens an existing business database. Child processes are stopped on success or failure. After force-stop, a separate read-only H2 JVM verifies that acknowledged records, versions, hashes, snapshots and feedback survived. In-process responses alone do not pass that durability gate. Results are local in `.local/ch34-acceptance-results.json`, or `.local/ch34-maven-probe-results.json` for the Maven-only run. Preserve results under a different local name before comparing multiple runs.

Reports distinguish successful, failed and refused questions. Evidence ID/excerpt checks do not establish semantic correctness: inspect the recorded answers against the expected answer points in `b05/evaluation.json`. The measured sample is a demonstration workload, not a continuous-service guarantee or a test of real classroom material.
