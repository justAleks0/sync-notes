import { initializeApp } from 'firebase/app'
import { getAuth } from 'firebase/auth'
import {
  initializeFirestore,
  persistentLocalCache,
  persistentMultipleTabManager,
} from 'firebase/firestore'

// Firebase web config is public by design — it identifies the project, it does not
// grant access. Access is controlled by the security rules in ../../firestore.rules.
const firebaseConfig = {
  apiKey: 'AIzaSyDnKHB2hMzgH8BYhP2DAaSBn_B2LPw9vJY',
  authDomain: 'sync-notes-c252b.firebaseapp.com',
  projectId: 'sync-notes-c252b',
  storageBucket: 'sync-notes-c252b.firebasestorage.app',
  messagingSenderId: '830656809377',
  appId: '1:830656809377:web:83ff1e9ef6fda66de363e0',
}

export const app = initializeApp(firebaseConfig)
export const auth = getAuth(app)

// Offline cache: notes stay readable and editable with no network, and writes are
// replayed when the connection comes back. Multi-tab manager keeps browser tabs in sync.
export const db = initializeFirestore(app, {
  localCache: persistentLocalCache({ tabManager: persistentMultipleTabManager() }),
})
