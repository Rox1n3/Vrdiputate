/**
 * One-time admin script: deletes all documents in users/*/history subcollections.
 *
 * Usage:
 *   1. Place your Firebase service account key at: scripts/serviceAccountKey.json
 *      (download from Firebase Console → Project Settings → Service accounts → Generate new private key)
 *   2. npm install firebase-admin   (only once)
 *   3. node scripts/clear_history.js
 *
 * This script is safe to re-run — it will simply delete 0 documents if history is already empty.
 */

const admin = require('firebase-admin');
const serviceAccount = require('./serviceAccountKey.json');

admin.initializeApp({
    credential: admin.credential.cert(serviceAccount)
});

const db = admin.firestore();

async function deleteCollection(collRef, batchSize = 100) {
    const snapshot = await collRef.limit(batchSize).get();
    if (snapshot.empty) return 0;

    const batch = db.batch();
    snapshot.docs.forEach(doc => batch.delete(doc.ref));
    await batch.commit();

    const deleted = snapshot.size;
    if (deleted === batchSize) {
        // There may be more — recurse
        return deleted + await deleteCollection(collRef, batchSize);
    }
    return deleted;
}

async function clearAllHistory() {
    console.log('Loading all users...');
    const usersSnap = await db.collection('users').get();

    if (usersSnap.empty) {
        console.log('No users found.');
        return;
    }

    let totalDeleted = 0;
    for (const userDoc of usersSnap.docs) {
        const historyRef = db.collection('users').doc(userDoc.id).collection('history');
        const deleted = await deleteCollection(historyRef);
        if (deleted > 0) {
            console.log(`  User ${userDoc.id}: deleted ${deleted} history record(s)`);
        }
        totalDeleted += deleted;
    }

    console.log(`\nDone. Total deleted: ${totalDeleted} history records across ${usersSnap.size} users.`);
}

clearAllHistory().catch(err => {
    console.error('Error:', err);
    process.exit(1);
});
