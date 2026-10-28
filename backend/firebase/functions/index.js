const { randomUUID } = require("node:crypto");
const { setGlobalOptions } = require("firebase-functions/v2");
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { initializeApp } = require("firebase-admin/app");
const { FieldValue, Timestamp, getFirestore } = require("firebase-admin/firestore");

initializeApp();
setGlobalOptions({ region: "asia-south1", maxInstances: 10 });

const db = getFirestore();
const MAX_FOCUS_MINUTES = 180;
const INVITE_LIFETIME_MS = 7 * 24 * 60 * 60 * 1000;
const HISTORY_LIMIT = 100;

function requireUser(request) {
  if (!request.auth) throw new HttpsError("unauthenticated", "Sign in before using Focus League.");
  return request.auth.uid;
}

function cleanUsername(value) {
  const username = String(value || "").trim().replace(/^@/, "");
  if (!/^[a-zA-Z0-9_]{3,20}$/.test(username)) {
    throw new HttpsError("invalid-argument", "Use 3–20 letters, numbers, or underscores.");
  }
  return username;
}

function todayKey() {
  // One worldwide board. The League UI makes the UTC daily reset explicit.
  return new Date().toISOString().slice(0, 10);
}

function number(value) {
  return Number(value || 0);
}

function timestampMillis(value) {
  return value && typeof value.toMillis === "function" ? value.toMillis() : null;
}

function playerFromSnapshot(snap, uid, day) {
  const data = snap.data();
  return {
    uid: snap.id,
    username: data.username,
    points: data.scoreDay === day ? number(data.todayPoints) : 0,
    isYou: snap.id === uid
  };
}

function rankPlayers(players) {
  return players.sort((a, b) => b.points - a.points || a.username.localeCompare(b.username));
}

exports.bootstrapProfile = onCall(async (request) => {
  const uid = requireUser(request);
  const username = cleanUsername(request.data?.username);
  const normalized = username.toLowerCase();
  const userRef = db.collection("users").doc(uid);
  const handleRef = db.collection("handles").doc(normalized);

  await db.runTransaction(async (tx) => {
    const [userSnap, handleSnap] = await Promise.all([tx.get(userRef), tx.get(handleRef)]);
    if (handleSnap.exists && handleSnap.data().uid !== uid) {
      throw new HttpsError("already-exists", "That username is already taken.");
    }
    const previousHandle = userSnap.data()?.handle;
    if (previousHandle && previousHandle !== normalized) {
      tx.delete(db.collection("handles").doc(previousHandle));
    }
    tx.set(handleRef, { uid, username, updatedAt: FieldValue.serverTimestamp() });
    tx.set(userRef, {
      uid,
      username,
      handle: normalized,
      updatedAt: FieldValue.serverTimestamp(),
      createdAt: userSnap.exists ? userSnap.data().createdAt : FieldValue.serverTimestamp(),
      scoreDay: userSnap.data()?.scoreDay || todayKey(),
      todayPoints: number(userSnap.data()?.todayPoints),
      totalFocusMinutes: number(userSnap.data()?.totalFocusMinutes),
      startedRounds: number(userSnap.data()?.startedRounds),
      completedRounds: number(userSnap.data()?.completedRounds),
      bestDayMinutes: number(userSnap.data()?.bestDayMinutes)
    }, { merge: true });
  });
  return { username };
});

exports.startFocusSession = onCall(async (request) => {
  const uid = requireUser(request);
  const task = String(request.data?.task || "").trim().slice(0, 140);
  const durationMinutes = Number(request.data?.durationMinutes);
  if (!task || !Number.isInteger(durationMinutes) || durationMinutes < 5 || durationMinutes > MAX_FOCUS_MINUTES) {
    throw new HttpsError("invalid-argument", "Use a task and a focus length between 5 and 180 minutes.");
  }

  const userRef = db.collection("users").doc(uid);
  const sessionRef = db.collection("focusSessions").doc(randomUUID());
  const startedAt = Timestamp.now();
  const endsAt = Timestamp.fromMillis(startedAt.toMillis() + durationMinutes * 60_000);

  await db.runTransaction(async (tx) => {
    const userSnap = await tx.get(userRef);
    if (!userSnap.exists) throw new HttpsError("failed-precondition", "Create your League profile first.");
    const activeId = userSnap.data().activeSessionId;
    if (activeId) {
      const activeSnap = await tx.get(db.collection("focusSessions").doc(activeId));
      if (activeSnap.exists && activeSnap.data().status === "active") {
        throw new HttpsError("failed-precondition", "Finish your current verified focus round first.");
      }
    }
    tx.set(sessionRef, { uid, task, durationMinutes, startedAt, endsAt, status: "active" });
    tx.set(userRef, {
      activeSessionId: sessionRef.id,
      activeSessionEndsAt: endsAt,
      startedRounds: number(userSnap.data().startedRounds) + 1,
      updatedAt: FieldValue.serverTimestamp()
    }, { merge: true });
  });
  return { sessionId: sessionRef.id, endsAtMillis: endsAt.toMillis() };
});

exports.completeFocusSession = onCall(async (request) => {
  const uid = requireUser(request);
  const sessionId = String(request.data?.sessionId || "");
  const sessionRef = db.collection("focusSessions").doc(sessionId);
  let result;
  await db.runTransaction(async (tx) => {
    const sessionSnap = await tx.get(sessionRef);
    if (!sessionSnap.exists || sessionSnap.data().uid !== uid) {
      throw new HttpsError("not-found", "Focus session not found.");
    }
    const session = sessionSnap.data();
    if (session.status === "completed") {
      result = { awarded: 0, alreadyCompleted: true };
      return;
    }
    if (session.status !== "active") throw new HttpsError("failed-precondition", "This session is no longer active.");
    if (Timestamp.now().toMillis() < session.endsAt.toMillis()) {
      throw new HttpsError("failed-precondition", "This focus round is still running.");
    }

    const userRef = db.collection("users").doc(uid);
    const userSnap = await tx.get(userRef);
    if (!userSnap.exists) throw new HttpsError("failed-precondition", "Create your League profile first.");
    const user = userSnap.data();
    const freshDay = user.scoreDay === todayKey();
    const award = session.durationMinutes;
    const nextTodayPoints = (freshDay ? number(user.todayPoints) : 0) + award;
    tx.update(sessionRef, { status: "completed", completedAt: FieldValue.serverTimestamp() });
    tx.set(userRef, {
      activeSessionId: user.activeSessionId === sessionId ? FieldValue.delete() : user.activeSessionId || FieldValue.delete(),
      activeSessionEndsAt: user.activeSessionId === sessionId ? FieldValue.delete() : user.activeSessionEndsAt || FieldValue.delete(),
      scoreDay: todayKey(),
      todayPoints: nextTodayPoints,
      totalFocusMinutes: number(user.totalFocusMinutes) + award,
      completedRounds: number(user.completedRounds) + 1,
      bestDayMinutes: Math.max(number(user.bestDayMinutes), nextTodayPoints),
      updatedAt: FieldValue.serverTimestamp()
    }, { merge: true });
    result = { awarded: award, alreadyCompleted: false };
  });
  return result;
});

exports.endFocusSession = onCall(async (request) => {
  const uid = requireUser(request);
  const sessionId = String(request.data?.sessionId || "");
  const sessionRef = db.collection("focusSessions").doc(sessionId);
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(sessionRef);
    if (!snap.exists || snap.data().uid !== uid) throw new HttpsError("not-found", "Focus session not found.");
    if (snap.data().status === "active") {
      tx.update(sessionRef, { status: "endedEarly", endedAt: FieldValue.serverTimestamp() });
      const userRef = db.collection("users").doc(uid);
      const userSnap = await tx.get(userRef);
      if (userSnap.exists && userSnap.data().activeSessionId === sessionId) {
        tx.set(userRef, {
          activeSessionId: FieldValue.delete(),
          activeSessionEndsAt: FieldValue.delete(),
          updatedAt: FieldValue.serverTimestamp()
        }, { merge: true });
      }
    }
  });
  return { ok: true };
});

exports.createFriendInvite = onCall(async (request) => {
  const uid = requireUser(request);
  const owner = await db.collection("users").doc(uid).get();
  if (!owner.exists) throw new HttpsError("failed-precondition", "Create your League profile first.");
  const inviteRef = db.collection("friendInvites").doc(randomUUID());
  const expiresAt = Timestamp.fromMillis(Date.now() + INVITE_LIFETIME_MS);
  await inviteRef.set({ ownerUid: uid, createdAt: FieldValue.serverTimestamp(), expiresAt, acceptedBy: null });
  return { inviteCode: inviteRef.id, expiresAtMillis: expiresAt.toMillis() };
});

exports.acceptFriendInvite = onCall(async (request) => {
  const uid = requireUser(request);
  const inviteRef = db.collection("friendInvites").doc(String(request.data?.inviteCode || ""));
  await db.runTransaction(async (tx) => {
    const inviteSnap = await tx.get(inviteRef);
    if (!inviteSnap.exists) throw new HttpsError("not-found", "That invite does not exist.");
    const invite = inviteSnap.data();
    if (invite.ownerUid === uid) throw new HttpsError("invalid-argument", "You cannot accept your own invite.");
    if (invite.acceptedBy) throw new HttpsError("already-exists", "That invite was already used.");
    if (Timestamp.now().toMillis() > invite.expiresAt.toMillis()) throw new HttpsError("deadline-exceeded", "That invite expired.");

    const [ownerSnap, guestSnap] = await Promise.all([
      tx.get(db.collection("users").doc(invite.ownerUid)),
      tx.get(db.collection("users").doc(uid))
    ]);
    if (!ownerSnap.exists || !guestSnap.exists) throw new HttpsError("failed-precondition", "Both people need League profiles.");
    tx.set(db.collection("users").doc(uid).collection("friends").doc(invite.ownerUid), {
      uid: invite.ownerUid, username: ownerSnap.data().username, addedAt: FieldValue.serverTimestamp()
    });
    tx.set(db.collection("users").doc(invite.ownerUid).collection("friends").doc(uid), {
      uid, username: guestSnap.data().username, addedAt: FieldValue.serverTimestamp()
    });
    tx.update(inviteRef, { acceptedBy: uid, acceptedAt: FieldValue.serverTimestamp() });
  });
  return { ok: true };
});

exports.getLeague = onCall(async (request) => {
  const uid = requireUser(request);
  const friendSnaps = await db.collection("users").doc(uid).collection("friends").limit(100).get();
  const ids = [uid, ...friendSnaps.docs.map((doc) => doc.id)];
  const users = await Promise.all(ids.map((id) => db.collection("users").doc(id).get()));
  const day = todayKey();
  const players = rankPlayers(users.filter((snap) => snap.exists).map((snap) => playerFromSnapshot(snap, uid, day)));
  return { day, players };
});

exports.getProfile = onCall(async (request) => {
  const uid = requireUser(request);
  const [userSnap, sessionSnaps] = await Promise.all([
    db.collection("users").doc(uid).get(),
    db.collection("focusSessions")
      .where("uid", "==", uid)
      .orderBy("startedAt", "desc")
      .limit(HISTORY_LIMIT)
      .get()
  ]);
  if (!userSnap.exists) throw new HttpsError("failed-precondition", "Create your League profile first.");
  const user = userSnap.data();
  const day = todayKey();
  const history = sessionSnaps.docs.map((snap) => {
    const session = snap.data();
    return {
      task: session.task,
      durationMinutes: number(session.durationMinutes),
      status: session.status,
      startedAtMillis: timestampMillis(session.startedAt),
      endedAtMillis: timestampMillis(session.completedAt) || timestampMillis(session.endedAt) || timestampMillis(session.endsAt)
    };
  });
  return {
    profile: {
      username: user.username,
      todayPoints: user.scoreDay === day ? number(user.todayPoints) : 0,
      totalFocusMinutes: number(user.totalFocusMinutes),
      startedRounds: number(user.startedRounds),
      completedRounds: number(user.completedRounds),
      bestDayMinutes: number(user.bestDayMinutes)
    },
    history
  };
});
