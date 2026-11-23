// The Windows loopback bridge is reachable by other local users. A fresh key is
// inherited only by this worker's children. Authenticate and encrypt each frame;
// bind requests to the server's one-use connection challenge and replies to the
// opposite direction so a captured request cannot be replayed or reflected.
import { createCipheriv, createDecipheriv, randomBytes } from 'node:crypto';
export function seal(key: Buffer, context: string, data: string): string {
  const iv = randomBytes(12); const cipher = createCipheriv('aes-256-gcm', key, iv);
  cipher.setAAD(Buffer.from(context));
  const body = Buffer.concat([cipher.update(data, 'utf8'), cipher.final()]);
  return Buffer.concat([iv, cipher.getAuthTag(), body]).toString('base64');
}
export function open(key: Buffer, context: string, data: string): string {
  const envelope = Buffer.from(data, 'base64');
  const cipher = createDecipheriv('aes-256-gcm', key, envelope.subarray(0, 12));
  cipher.setAAD(Buffer.from(context)); cipher.setAuthTag(envelope.subarray(12, 28));
  return Buffer.concat([cipher.update(envelope.subarray(28)), cipher.final()]).toString('utf8');
}
