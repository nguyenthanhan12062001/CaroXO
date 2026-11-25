const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_uid_123";
const BOB_UID = "bob_uid_456";
const CHARLIE_UID = "charlie_uid_789";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

function buildProfilePayload(uid, nickname = "Alice", overrides = {}) {
  const now = new Date(Date.now() - 2000);
  return {
    userId: uid,
    nickname,
    avatarId: "ninja",
    pieceStyle: "CLASSIC",
    wins: 0,
    losses: 0,
    draws: 0,
    points: 100,
    bestRank: 0,
    bestPoints: 100,
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

function buildLeaderboardPayload(uid, nickname = "Alice", overrides = {}) {
  const now = new Date(Date.now() - 2000);
  return {
    userId: uid,
    nickname,
    avatarId: "ninja",
    pieceStyle: "CLASSIC",
    wins: 0,
    losses: 0,
    draws: 0,
    points: 100,
    publicEntry: true,
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

function buildRoomPayload(roomCode, hostUid, overrides = {}) {
  const now = new Date(Date.now() - 2000);
  return {
    roomCode,
    hostUid,
    guestUid: "",
    memberUids: [hostUid],
    status: "WAITING",
    boardSize: 15,
    blockTwoEnds: true,
    hostNickname: "Alice",
    hostAvatarId: "ninja",
    hostStyle: "CLASSIC",
    guestNickname: "",
    guestAvatarId: "",
    guestStyle: "CLASSIC",
    currentTurn: "X",
    moves: [],
    lastMoveRow: -1,
    lastMoveCol: -1,
    winnerUid: "",
    turnStartedAt: now,
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

test("Unauthenticated user cannot read or write users, leaderboard, or rooms", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).set(buildProfilePayload(ALICE_UID)));
  await assertFails(unauthDb.collection("leaderboard").where("publicEntry", "==", true).get());
  await assertFails(unauthDb.collection("rooms").doc("123456").get());
  await assertFails(unauthDb.collection("rooms").doc("123456").set(buildRoomPayload("123456", ALICE_UID)));
});

test("Users collection enforces owner-only access, strict keys, and immutable createdAt/userId", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set(buildProfilePayload(ALICE_UID))
  );
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).get());

  // Cross-user read/write denied
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).update({ points: 9999 }));

  // Ghost field rejected (Shadow Update test)
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      nickname: "AlicePro",
      isAdmin: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Immutable createdAt cannot change
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Valid partial update succeeds including VIP piece style and peak rank
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      nickname: "AlicePro",
      avatarId: "robot",
      pieceStyle: "VIP_CELESTIAL_MONARCH",
      wins: 1,
      points: 110,
      bestRank: 1,
      bestPoints: 110,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Invalid pieceStyle or negative bestRank rejected
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      pieceStyle: "UNKNOWN_STYLE",
      updatedAt: new Date(Date.now() - 300),
    })
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      bestRank: -1,
      updatedAt: new Date(Date.now() - 300),
    })
  );
});

test("Leaderboard enforces owner writes and aligned publicEntry query", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertSucceeds(
    aliceDb.collection("leaderboard").doc(ALICE_UID).set(buildLeaderboardPayload(ALICE_UID))
  );

  // Aligned query succeeds
  await assertSucceeds(
    bobDb.collection("leaderboard").where("publicEntry", "==", true).orderBy("points", "desc").limit(20).get()
  );

  // Unfiltered list fails
  await assertFails(bobDb.collection("leaderboard").get());

  // Cross-user write fails
  await assertFails(
    bobDb.collection("leaderboard").doc(ALICE_UID).update({ points: 5000 })
  );
});

test("Rooms lifecycle: create, join, turn-ordered moves, terminal state lock, and non-member denial", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();

  // Host creates room 482910
  await assertSucceeds(
    aliceDb.collection("rooms").doc("482910").set(buildRoomPayload("482910", ALICE_UID))
  );

  // Non-numeric room code rejected
  await assertFails(
    aliceDb.collection("rooms").doc("ABCDEF").set(buildRoomPayload("ABCDEF", ALICE_UID))
  );

  // Guest Bob joins WAITING room
  await assertSucceeds(
    bobDb.collection("rooms").doc("482910").update({
      guestUid: BOB_UID,
      memberUids: [ALICE_UID, BOB_UID],
      status: "PLAYING",
      guestNickname: "Bob",
      guestAvatarId: "cat",
      guestStyle: "WOOD_CLASSIC",
      turnStartedAt: new Date(Date.now() - 1000),
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Non-member Charlie cannot read PLAYING room
  await assertFails(charlieDb.collection("rooms").doc("482910").get());

  // Bob (O) cannot move on X's turn
  await assertFails(
    bobDb.collection("rooms").doc("482910").update({
      moves: ["7,7,1"],
      lastMoveRow: 7,
      lastMoveCol: 7,
      currentTurn: "O",
      turnStartedAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Alice (X) makes valid move 1
  await assertSucceeds(
    aliceDb.collection("rooms").doc("482910").update({
      moves: ["7,7,1"],
      lastMoveRow: 7,
      lastMoveCol: 7,
      currentTurn: "O",
      turnStartedAt: new Date(Date.now() - 400),
      updatedAt: new Date(Date.now() - 400),
    })
  );

  // Bob (O) makes move 2 and wins (simulated finish)
  await assertSucceeds(
    bobDb.collection("rooms").doc("482910").update({
      moves: ["7,7,1", "7,8,2"],
      lastMoveRow: 7,
      lastMoveCol: 8,
      currentTurn: "X",
      status: "FINISHED",
      winnerUid: BOB_UID,
      turnStartedAt: new Date(Date.now() - 200),
      updatedAt: new Date(Date.now() - 200),
    })
  );

  // Terminal state locking: no updates allowed after FINISHED
  await assertFails(
    aliceDb.collection("rooms").doc("482910").update({
      moves: ["7,7,1", "7,8,2", "6,6,1"],
      lastMoveRow: 6,
      lastMoveCol: 6,
      currentTurn: "O",
      turnStartedAt: new Date(Date.now() - 100),
      updatedAt: new Date(Date.now() - 100),
    })
  );
});

test("Global chat enforces sign-in, valid payload bounds, immutable messages, and aligned queries", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = new Date(Date.now() - 1000);

  const validMsg = {
    senderUid: ALICE_UID,
    senderNickname: "Alice",
    senderAvatarId: "ninja",
    text: "Hello everyone! Ready for CaroXO?",
    publicChannel: true,
    createdAt: now,
  };

  // Unauthenticated denied
  await assertFails(
    unauthDb.collection("global_chat").where("publicChannel", "==", true).get()
  );
  await assertFails(unauthDb.collection("global_chat").doc("msg_1").set(validMsg));

  // Alice creates valid global message
  await assertSucceeds(aliceDb.collection("global_chat").doc("msg_1").set(validMsg));

  // Bob can query aligned global chat
  await assertSucceeds(
    bobDb.collection("global_chat")
      .where("publicChannel", "==", true)
      .orderBy("createdAt", "desc")
      .limit(60)
      .get()
  );

  // Unfiltered list rejected
  await assertFails(bobDb.collection("global_chat").get());

  // Spoofed senderUid rejected
  await assertFails(
    bobDb.collection("global_chat").doc("msg_spoof").set({
      ...validMsg,
      senderUid: ALICE_UID,
    })
  );

  // Oversized text (>300 chars) rejected
  await assertFails(
    aliceDb.collection("global_chat").doc("msg_long").set({
      ...validMsg,
      text: "x".repeat(301),
    })
  );

  // Empty text rejected
  await assertFails(
    aliceDb.collection("global_chat").doc("msg_empty").set({
      ...validMsg,
      text: "",
    })
  );

  // Shadow/ghost field rejected
  await assertFails(
    aliceDb.collection("global_chat").doc("msg_ghost").set({
      ...validMsg,
      isModerator: true,
    })
  );

  // Immutable: update & delete rejected even by author
  await assertFails(aliceDb.collection("global_chat").doc("msg_1").update({ text: "Edited" }));
  await assertFails(aliceDb.collection("global_chat").doc("msg_1").delete());
});

test("In-room chat restricts reads/writes to active room members and validates payload", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();

  await aliceDb.collection("rooms").doc("482910").set(buildRoomPayload("482910", ALICE_UID));
  await bobDb.collection("rooms").doc("482910").update({
    guestUid: BOB_UID,
    memberUids: [ALICE_UID, BOB_UID],
    status: "PLAYING",
    guestNickname: "Bob",
    guestAvatarId: "cat",
    guestStyle: "WOOD_CLASSIC",
    turnStartedAt: new Date(Date.now() - 1000),
    updatedAt: new Date(Date.now() - 1000),
  });

  const now = new Date(Date.now() - 500);
  const aliceRoomMsg = {
    senderUid: ALICE_UID,
    senderNickname: "Alice",
    senderAvatarId: "ninja",
    text: "Good luck Bob!",
    roomCode: "482910",
    memberUids: [ALICE_UID, BOB_UID],
    createdAt: now,
  };

  // Alice sends room message
  await assertSucceeds(
    aliceDb.collection("rooms").doc("482910").collection("messages").doc("rmsg_1").set(aliceRoomMsg)
  );

  // Bob can query room messages using array-contains on memberUids
  await assertSucceeds(
    bobDb.collection("rooms").doc("482910").collection("messages")
      .where("memberUids", "array-contains", BOB_UID)
      .orderBy("createdAt", "desc")
      .limit(100)
      .get()
  );

  // Non-member Charlie cannot read or write in-room messages
  await assertFails(
    charlieDb.collection("rooms").doc("482910").collection("messages").doc("rmsg_1").get()
  );
  await assertFails(
    charlieDb.collection("rooms").doc("482910").collection("messages").get()
  );
  await assertFails(
    charlieDb.collection("rooms").doc("482910").collection("messages")
      .where("memberUids", "array-contains", ALICE_UID)
      .get()
  );
  await assertFails(
    charlieDb.collection("rooms").doc("482910").collection("messages").doc("rmsg_2").set({
      ...aliceRoomMsg,
      senderUid: CHARLIE_UID,
      memberUids: [ALICE_UID, BOB_UID, CHARLIE_UID],
    })
  );

  // Cannot tamper memberUids to omit guest
  await assertFails(
    aliceDb.collection("rooms").doc("482910").collection("messages").doc("rmsg_bad_members").set({
      ...aliceRoomMsg,
      memberUids: [ALICE_UID],
    })
  );

  // Immutable: update & delete rejected
  await assertFails(
    aliceDb.collection("rooms").doc("482910").collection("messages").doc("rmsg_1").update({ text: "Tampered" })
  );
  await assertFails(
    aliceDb.collection("rooms").doc("482910").collection("messages").doc("rmsg_1").delete()
  );
});

