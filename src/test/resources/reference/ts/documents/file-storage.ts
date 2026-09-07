import { randomUUID } from "node:crypto";
import type { ReadStream } from "node:fs";
import { createReadStream, existsSync } from "node:fs";
import { mkdir, unlink, writeFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import { NotFoundError } from "../errors.js";

/**
 * Local-filesystem blob storage for supporting-document files. The
 * DeedPollSupportingDocument row keeps the metadata; the bytes live under
 * DATA_DIR/documents keyed by an opaque storageKey.
 *
 * This is the seam a production deployment replaces with real object storage — nothing
 * else in the codebase touches the filesystem, so swapping the three functions below is
 * the whole change. Note that local disk does not survive AKS: pods are ephemeral and
 * replicas do not share a volume, so this cannot go to a deployed environment as-is.
 *
 * DATA_DIR mirrors the prototype's convention (persistent mount, gitignored).
 */
const root = resolve(process.env.DATA_DIR ?? join(process.cwd(), "data"), "documents");

/** Persist bytes under a fresh storage key and return it. */
export async function saveFile(buffer: Buffer): Promise<string> {
  await mkdir(root, { recursive: true });
  const key = randomUUID();
  await writeFile(pathForKey(key), buffer);
  return key;
}

/** Open a read stream for a stored blob; throws NotFoundError if the key is unknown. */
export function createFileReadStream(key: string): ReadStream {
  const path = pathForKey(key);
  if (!existsSync(path)) {
    throw new NotFoundError("Stored file not found");
  }
  // node:fs createReadStream, not this function — the two names differ deliberately.
  return createReadStream(path);
}

/** Delete a stored blob. A missing file is a no-op, so delete stays idempotent. */
export async function deleteFile(key: string | null | undefined): Promise<void> {
  if (!key) return;
  const path = pathForKey(key);
  if (existsSync(path)) await unlink(path);
}

/**
 * Resolve a key to an absolute path, guarding against path traversal: the resolved path
 * must stay inside the storage root.
 */
function pathForKey(key: string): string {
  const path = resolve(root, key);
  if (path !== root && !path.startsWith(`${root}/`)) {
    throw new NotFoundError("Invalid storage key");
  }
  return path;
}
