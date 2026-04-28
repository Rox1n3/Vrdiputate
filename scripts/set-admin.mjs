// Run: node scripts/set-admin.mjs
// Sets danilka030308@gmail.com as admin

import { initializeApp, cert } from 'firebase-admin/app';
import { getFirestore } from 'firebase-admin/firestore';
import { getAuth } from 'firebase-admin/auth';
import { readFileSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));

// Load env variables from .env (correctly handles quoted values)
const envPath = resolve(__dirname, '../.env');
const envContent = readFileSync(envPath, 'utf8');
const env = {};
envContent.split('\n').forEach(line => {
  const trimmed = line.trim();
  if (!trimmed || trimmed.startsWith('#')) return;
  const eqIdx = trimmed.indexOf('=');
  if (eqIdx === -1) return;
  const key = trimmed.slice(0, eqIdx).trim();
  let val = trimmed.slice(eqIdx + 1).trim();
  // Strip surrounding quotes (single or double)
  if ((val.startsWith('"') && val.endsWith('"')) ||
      (val.startsWith("'") && val.endsWith("'"))) {
    val = val.slice(1, -1);
  }
  env[key] = val;
});

const privateKey = env.FIREBASE_PRIVATE_KEY?.replace(/\\n/g, '\n');

const app = initializeApp({
  credential: cert({
    projectId: env.FIREBASE_PROJECT_ID,
    clientEmail: env.FIREBASE_CLIENT_EMAIL,
    privateKey: privateKey,
  }),
});

const db = getFirestore(app);
const auth = getAuth(app);

const EMAIL = 'danilka030308@gmail.com';

async function main() {
  try {
    const user = await auth.getUserByEmail(EMAIL);
    await db.collection('users').doc(user.uid).set({ role: 'admin' }, { merge: true });
    console.log(`Admin role set for ${EMAIL} (uid: ${user.uid})`);
  } catch (e) {
    console.error('Error:', e.message);
    console.log('Note: The user may need to register first. Once registered, run this script again.');
  }
  process.exit(0);
}

main();
