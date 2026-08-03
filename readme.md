# CI/CD Pipeline: Jenkins Shared Library + SonarQube + Docker + ArgoCD

A Flask to-do API deployed through a fully automated GitOps pipeline —
code push to running pod, with zero manual deployment steps.

## Architecture

```
GitHub push (python-app-code)
      ↓
Jenkins (shared library pipeline)
      ↓
Unit Tests (pytest) → SonarQube Scan + Quality Gate
      ↓
Docker Build → Docker Push (Docker Hub)
      ↓
Update image tag in gitops-manifests repo (Helm values.yaml)
      ↓
ArgoCD detects change → auto-syncs → deploys to Kubernetes
```

**Three repos work together:**
- `python-app-code` — the Flask app, tests, Dockerfile, Jenkinsfile
- `shared-pipeline-idp` — the reusable Jenkins pipeline library
- `gitops-manifests` — Helm chart + values, what ArgoCD watches

## 1. The pipeline logic (shared library)

The core pipeline stages live in a reusable Jenkins shared library, not
copy-pasted into every project's Jenkinsfile.

![Shared library pipeline code](./screenshots/01-shared-library-code.png)

## 2. Registering the library in Jenkins

Configured under Manage Jenkins → System → Global Trusted Pipeline
Libraries, pointing at the `shared-pipeline-idp` repo.

![Jenkins Global Library config](./screenshots/02-global-library-config.png)
![Library repo URL and credentials](./screenshots/03-library-repo-url.png)

## 3. App code, tests, and pushing a change

Flask app with a `/health` endpoint and task routes, tested with pytest
(13 tests, including new coverage for the API layer added on top of the
original in-memory to-do functions).

![Code, tests, and git push](./screenshots/04-code-and-push.png)

## 4. Pipeline history

```
#35  ✓  10:53am
#34  ✗  10:50am
#33  ✓  10:10am
```

A realistic build history — not every run passed on the first try, and
that's part of the story (see the issues table below).

![Jenkins build history](./screenshots/05-build-history.png)

## 5. A fully green pipeline run

All 8 stages passing in one run: Checkout → Install Dependencies →
Unit Test → SonarQube Scan → Quality Gate → Docker Build → Docker Push →
Update Helm Values for ArgoCD.

![Full pipeline, all stages green](./screenshots/06-pipeline-green.png)

## 6. SonarQube — Quality Gate passed

Static analysis with 0 new issues, 0 accepted issues, 0% duplication.

![SonarQube dashboard passed](./screenshots/07-sonarqube-passed.png)

## 7. ArgoCD — synced and healthy

ArgoCD detects the commit Jenkins pushed to `gitops-manifests`
(`Update image tag to 35 [ci skip]`, authored by `jenkins-ci`) and
auto-syncs the new image into the cluster.

![ArgoCD synced and healthy](./screenshots/08-argocd-synced.png)

## 8. The app, actually running

Hitting the live `/health` endpoint through the NodePort service:

![Live health endpoint response](./screenshots/09-live-response.png)

```json
{"status": "hello from jenkins shared pipeline"}
```

## Deployment failure: CrashLoopBackOff — full breakdown

This was the first real production-style failure hit in this project, and
the most important one to understand deeply, since it comes up often in
interviews ("walk me through a deployment failure you debugged").

**WHAT happened**
After the first deploy, the pod never reached a stable `Running` state.
`kubectl get pods` showed it cycling through `CrashLoopBackOff`, restarting
every few seconds.

**Diagnosis steps taken**
```bash
kubectl describe pod <pod-name>
kubectl logs <pod-name>
kubectl logs <pod-name> --previous
```
The logs showed no error at all — just the app's own output, printed once,
then nothing. No traceback, no exception.

**WHY it was crashing**
The application was a plain Python script: it ran its logic (add a few
tasks, print them) and then reached the end of `main()` and exited —
exactly like running any normal script from a terminal. But Kubernetes
Deployments assume the container's main process is long-running (like a
web server that keeps listening). When a container's process exits — even
cleanly, with no error — Kubernetes treats that as "the container stopped"
and restarts it, per the default `restartPolicy: Always`. The script would
run, print its output, exit, restart, print again, exit again — forever.

**HOW it was fixed**
The app was rewritten as a small Flask web service (`app.run(host="0.0.0.0",
port=5000)`), which starts a server that blocks and listens for requests
indefinitely. The container's main process never exits on its own anymore,
so Kubernetes has nothing to restart. A `/health` route was added
specifically so Kubernetes liveness/readiness probes have something to
check.

**Follow-on deployment failures caused by the same fix, until fully resolved:**

1. **Service unreachable even though the pod was `Running`.**
   `kubectl describe svc` showed `TargetPort: 3000`, but Flask listens on
   `5000` — the Helm chart's `service.yaml` template read the same `port`
   value for both `port` and `targetPort`. Fixed by splitting them into
   separate `port` and `targetPort` fields in `values.yaml`, and updating
   the template to reference each correctly.

2. **`ModuleNotFoundError: No module named 'flask'` — inside the deployed
   container only, even though it worked in Jenkins.**
   The Dockerfile had a hardcoded `RUN pip install --no-cache-dir pytest`,
   completely ignoring `requirements.txt` (which had Flask added to it).
   Jenkins' own test stage installed from `requirements.txt` correctly, so
   tests passed there — but the Docker image shipped to Kubernetes never
   got Flask at all. Fixed by changing the Dockerfile to
   `COPY requirements.txt .` then `pip install -r requirements.txt`,
   so the image and the Jenkins test environment install from the exact
   same source.

**Why this sequence matters for the interview story:** each fix exposed
the next problem — the crash loop hid the port mismatch, and the port
mismatch investigation is what led to actually running the container
locally and discovering Flask wasn't even installed. Debugging this
end-to-end, one layer at a time, is a more realistic and defensible story
than claiming it was fixed in one step.

## Real issues hit and fixed

| Issue | Root cause | Fix |
|---|---|---|
| Pod stuck in `CrashLoopBackOff` | App was a script that ran once and exited — Kubernetes expects a long-running process | Wrapped the app in a Flask server that listens continuously |
| `externally-managed-environment` error installing pip packages | Python 3.14 blocks direct system-wide installs (PEP 668) | Used an isolated `venv` per build |
| `ensurepip is not available` | Missing OS package on the Jenkins agent | Installed `python3.14-venv` on the host |
| `sed: unknown option to 's'` | Broken quote escaping across Groovy → shell → sed | Rewrote the sed expression using single quotes |
| Helm values never updated in Git | Assumed the GitOps repo was already present in the workspace | Added an explicit `git clone` of `gitops-manifests` before editing |
| `Could not find credentials entry with ID 'github-creds'` | Referenced a Jenkins credential that didn't exist | Reused the existing `github` credential after upgrading its token scope to `repo` (read/write) |
| Service unreachable despite pod `Running` | `targetPort` in the Helm chart matched the Service `port` instead of the actual Flask port | Separated `port` and `targetPort` in `values.yaml` and the Service template |
| Flask app crashed in the deployed container only | Dockerfile hardcoded `pip install pytest`, ignoring `requirements.txt` entirely | Changed Dockerfile to install from `requirements.txt`, so Flask ships with the image |

## How to run this yourself

1. Clone all three repos
2. Point Jenkins at a repo with this Jenkinsfile:
   ```groovy
   @Library('Jenkins-shared-library') _
   pythonAppPipeline()
   ```
3. Configure SonarQube server, Docker Hub, and GitHub credentials in Jenkins
4. Push a change — the pipeline runs end to end automatically