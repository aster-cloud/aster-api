# Lexicon Hot-plug Recovery Runbook

## When you need this

You uploaded a lexicon jar via `POST /api/v1/admin/lexicons` and the response was HTTP 500 with body containing a `recoveryId`:

```json
{
  "status": "rejected",
  "outcome": "backup_restore_io_failed",
  "message": "new jar rejected (...) AND backup restore IO failed — manual recovery required, see server log with recoveryId for affected paths",
  "fileName": "zh-CN.jar",
  "sha256": "...",
  "recoveryId": "8a1c4f3e-2b6d-4f72-9c01-3e8f7b1d5a90"
}
```

This means:

1. The new jar you uploaded was rejected (validation, strict-drift, etc.)
2. The server tried to restore the previous jar from a backup
3. The restore itself failed (disk full, IO error, permission denied, ...)
4. Disk state is **half-committed**: canonical jar may be the rejected new version, the old version may be at the backup path but not yet swapped back

## How to recover

1. **Find the affected paths** by greping server logs for the `recoveryId`:

   ```bash
   kubectl logs -n aster-cloud deploy/aster-api | grep "recoveryId=8a1c4f3e-..."
   ```

   Look for the log line containing `target=<path>` and `backup=<path>`.

2. **Inspect both files** on the pod (single-replica deployment; for multi-replica see "Cross-replica" below):

   ```bash
   kubectl exec -n aster-cloud deploy/aster-api -- ls -la /var/aster/lexicons/jars/
   kubectl exec -n aster-cloud deploy/aster-api -- ls -la /var/aster/lexicons/jars/pending/
   ```

3. **Restore the backup** (the path from the log under `backup=...`):

   ```bash
   kubectl exec -n aster-cloud deploy/aster-api -- mv \
     /var/aster/lexicons/jars/pending/zh-CN.jar.<uuid>.backup \
     /var/aster/lexicons/jars/zh-CN.jar
   ```

   The WatchService will detect the change and reload automatically. To verify the registry picked it up:

   ```bash
   curl -s https://policy.aster-lang.dev/api/v1/lexicons | jq .
   ```

4. If the backup file is also missing or corrupted, restore from a known-good jar from your jar artifact store (S3 / OCI registry) and POST it again.

## Cross-replica deployments

`aster-api` defaults to **single-writer ReadWriteOnce** for the hotplug dir. The `recoveryId` recovery flow above assumes one pod.

If you are running multi-replica with a shared `ReadWriteMany` PVC (not officially supported), the `cross_replica_busy` (HTTP 409) outcome will fire when two pods race the same `fileName`. The recovery is the same — but verify the canonical jar on **every** replica's local mount.

The container image is distroless and may lack `sha256sum` / `jq`. Use whichever hash tool is available in your image:

```bash
# Option 1: sha256sum (GNU coreutils — typical Debian-based base images)
for pod in $(kubectl get pods -n aster-cloud -l app=aster-api -o name); do
  kubectl exec -n aster-cloud $pod -- sha256sum /var/aster/lexicons/jars/zh-CN.jar
done

# Option 2: shasum -a 256 (BSD / macOS / Alpine)
for pod in $(kubectl get pods -n aster-cloud -l app=aster-api -o name); do
  kubectl exec -n aster-cloud $pod -- shasum -a 256 /var/aster/lexicons/jars/zh-CN.jar
done

# Option 3: openssl (most distroless / minimal images)
for pod in $(kubectl get pods -n aster-cloud -l app=aster-api -o name); do
  kubectl exec -n aster-cloud $pod -- openssl dgst -sha256 /var/aster/lexicons/jars/zh-CN.jar
done

# Option 4: API-level smoke check (NOT a byte-level hash)
# This only confirms each replica's registry currently exposes the locale.
# It does NOT prove the on-disk jar bytes match across replicas — use one of
# Options 1-3 (or scp the jar locally and hash) for that.
curl -s https://policy.aster-lang.dev/api/v1/lexicons | grep -i zh-cn
```

If hashes disagree, manually pick the correct version and `kubectl cp` it to each pod.

## Parsing JSON without jq

If your local shell doesn't have `jq`, use one of:

```bash
# Pretty-print with python
curl -s https://policy.aster-lang.dev/api/v1/lexicons | python3 -m json.tool

# Or just grep for the field
curl -s https://policy.aster-lang.dev/api/v1/lexicons | grep -oE '"id":"[^"]+"'
```

## Prevention

- Always upload jars via the official admin API; do **not** write to `/var/aster/lexicons/jars/` directly with `kubectl cp` (the WatchService will load partial writes).
- For multi-replica safety, deploy `aster-api` with one replica owning hotplug writes (e.g., StatefulSet with leader election, or a single dedicated "admin" deployment whose jars get rsync'd to read-only replicas).
- Monitor server logs for `recoveryId=` patterns; alert on any occurrence — it's always operator-visible.

## Outcomes that produce recoveryId

| Outcome | Meaning | HTTP status |
|---------|---------|-------------|
| `backup_restore_load_failed` | New jar rejected, backup restored to disk, but loader rejected the old bytes too. Memory has unknown state. | 500 |
| `backup_restore_io_failed` | New jar rejected, but `Files.move(backup → target)` itself threw IOException. Canonical path holds rejected bytes; backup file still in `pending/`. | 500 |
| `rollback_failed` | Single-load case where in-transaction rollback to old loader registered only a subset of old lexicons. Registry reports `missing=<set>` in the response message. | 500 |

For `rollback_failed` the runbook is different: the registry is in a partial-old state, but the on-disk jar is still the previous accepted version. Re-uploading the previous jar usually re-registers the missing lexicons.

## Lockfile maintenance (`.cross-lock`)

`pending/{fileName}.cross-lock` files are the cross-replica coordination primitive. **They are intentionally never deleted at runtime** — not by `CrossReplicaLock.close()`, not by `cleanupPendingDir()` on startup. Each unique `fileName` ever uploaded leaves one empty (zero-byte) `.cross-lock` file behind.

### Why they must persist

The locks are POSIX `fcntl` locks keyed by inode. If we deleted the lockfile after release, a new acquirer would create a fresh inode at the same path while a slow pod is still holding the (now-unlinked) old inode. Both pods would then think they hold the same logical lock — but they'd be holding different inodes. The 2025-05 review caught this as a Critical split-brain race; the fix is to **always re-open the same inode**.

### When this is a problem

For a typical Aster deployment (~10 locales, stable jar names like `zh-CN.jar`, `de-DE.jar`), you'll have ~10 empty files. Negligible.

If an admin uploads many ad-hoc names over time (test jars, experimentation), the count grows monotonically. Each file is empty, so disk usage is bounded by the directory's inode budget rather than space.

### Current deployment shape (k3s)

The shipped `aster-api` Deployment at `k3s/apps/aster-lang/cloud/deployment.yaml` runs as:

- **namespace**: `aster-cloud` (NOT `aster-api`)
- **replicas: 1** — single-writer enforced at deployment level
- **securityContext.readOnlyRootFilesystem: true** — the container root FS is read-only
- **hotplug dir**: `/var/aster/lexicons/jars`, backed by a dedicated `emptyDir` volume named `lexicon-hotplug` (sizeLimit 3Gi). Required because `readOnlyRootFilesystem` blocks the default container layer.

What this means in practice:

1. Cross-replica locking is defensive code; the deployment topology already prevents concurrent writers.
2. Uploaded jars survive in-process restarts of the JVM but **NOT pod restarts** — `kubectl rollout restart` wipes the `emptyDir` along with all uploaded jars.
3. Lockfile cleanup is automatic: every fresh pod starts with an empty `/var/aster/lexicons/jars` directory.
4. The `emptyDir` is bounded at 3Gi (≈ 50 jars × 50 MiB max + headroom). If an admin floods uploads past this limit, Kubernetes evicts the pod rather than filling the node disk.

If you need persistent jars across pod restarts, mount a PVC at `/var/aster/lexicons/jars` (single-writer ReadWriteOnce) in place of the `emptyDir`. See [aster-deploy RFC: pluggable-language-modules.md](../../../aster-deploy/docs/rfc/pluggable-language-modules.md).

### Maintenance procedure (single-replica, ephemeral storage — current k3s deploy)

For the **current** k3s deployment shape (no shared PVC), lockfile cleanup is trivial — restart the pod:

```bash
# Wipes /var/aster/lexicons/jars/* including all .cross-lock files.
# Also forces re-load of any jars baked into the image at startup.
kubectl rollout restart deploy/aster-api -n aster-cloud
kubectl rollout status deploy/aster-api -n aster-cloud
```

Anything you uploaded via `POST /api/v1/admin/lexicons` since the last restart is lost — you must re-upload after rollout completes.

### Maintenance procedure (multi-replica + shared PVC — aspirational, not currently deployed)

If a future deployment adds a `ReadWriteMany` PVC mounted at the hotplug dir on multiple replicas, the lockfile cleanup procedure looks like this. **This is not currently supported by the shipped manifests — you must adapt the namespace, deployment name, PVC claimName, and mountPath to your actual setup before running.**

```bash
# 1. Scale to zero to ensure no in-flight uploads
kubectl scale deploy/aster-api -n <your-namespace> --replicas=0

# 2. Wait for termination
kubectl wait --for=delete pod -l app=aster-api -n <your-namespace> --timeout=120s

# 3. Mount the PVC via a one-shot maintenance pod
kubectl run lexicon-maint -n <your-namespace> \
  --rm -i --restart=Never --image=busybox \
  --overrides='{
    "spec": {
      "containers": [{
        "name": "lexicon-maint",
        "image": "busybox",
        "command": ["sh","-c","rm -f /jars/pending/*.cross-lock && ls -la /jars/pending/"],
        "volumeMounts": [{"name":"pvc","mountPath":"/jars"}]
      }],
      "volumes": [{
        "name": "pvc",
        "persistentVolumeClaim": {"claimName": "<your-pvc-claim-name>"}
      }]
    }
  }'

# 4. Scale back up
kubectl scale deploy/aster-api -n <your-namespace> --replicas=1
```

**Do not run this while pods are up.** Deleting an active `.cross-lock` file re-introduces the split-brain race.
