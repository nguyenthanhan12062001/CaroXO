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

  // Signed-in host can check non-existent room code before createRoom
  await assertSucceeds(aliceDb.collection("rooms").doc("482910").get());

  // Host creates room 482910
  await assertSucceeds(
    aliceDb.collection("rooms").doc("482910").set(buildRoomPayload("482910", ALICE_UID))
  );

  // Non-numeric room code rejected
  await assertFails(
    aliceDb.collection("rooms").doc("ABCDEF").set(buildRoomPayload("ABCDEF", ALICE_UID))
  );

  // Signed-in guest can check non-existent room code and WAITING room before joinRoom
  await assertSucceeds(bobDb.collection("rooms").doc("999999").get());
  await assertSucceeds(bobDb.collection("rooms").doc("482910").get());

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

test("Friendships enforce deterministic sorted IDs, recipient-only accept, member-scoped queries, and unfriend", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();

  // Seed leaderboard entries for Alice and Bob
  await aliceDb.collection("leaderboard").doc(ALICE_UID).set(buildLeaderboardPayload(ALICE_UID, "Alice"));
  await bobDb.collection("leaderboard").doc(BOB_UID).set(buildLeaderboardPayload(BOB_UID, "Bob"));

  const now = new Date(Date.now() - 1000);
  const friendshipId = `${ALICE_UID}_${BOB_UID}`;
  const validFriendship = {
    userIds: [ALICE_UID, BOB_UID],
    requesterUid: ALICE_UID,
    recipientUid: BOB_UID,
    status: "PENDING",
    user1Uid: ALICE_UID,
    user1Nickname: "Alice",
    user1AvatarId: "ninja",
    user1Points: 100,
    user2Uid: BOB_UID,
    user2Nickname: "Bob",
    user2AvatarId: "robot",
    user2Points: 100,
    createdAt: now,
    updatedAt: now,
  };

  // Unauthenticated denied
  await assertFails(unauthDb.collection("friendships").doc(friendshipId).get());
  await assertFails(unauthDb.collection("friendships").doc(friendshipId).set(validFriendship));

  // Unfiltered list denied
  await assertFails(aliceDb.collection("friendships").get());

  // Non-member Charlie cannot get non-existent friendship doc of Alice & Bob
  await assertFails(charlieDb.collection("friendships").doc(friendshipId).get());

  // Cannot send friend request to non-existent user
  await assertFails(
    aliceDb.collection("friendships").doc(`${ALICE_UID}_ghost_user`).set({
      ...validFriendship,
      userIds: [ALICE_UID, "ghost_user"],
      recipientUid: "ghost_user",
      user2Uid: "ghost_user",
    })
  );

  // Alice sends valid friend request to Bob
  await assertSucceeds(aliceDb.collection("friendships").doc(friendshipId).set(validFriendship));

  // Alice (requester) cannot self-accept her own outgoing request
  await assertFails(
    aliceDb.collection("friendships").doc(friendshipId).update({
      status: "ACCEPTED",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Charlie (non-member) cannot read or accept
  await assertFails(charlieDb.collection("friendships").doc(friendshipId).get());
  await assertFails(
    charlieDb.collection("friendships").doc(friendshipId).update({
      status: "ACCEPTED",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Bob accepts the pending request
  await assertSucceeds(
    bobDb.collection("friendships").doc(friendshipId).update({
      status: "ACCEPTED",
      updatedAt: new Date(Date.now() - 400),
    })
  );

  // Aligned array-contains query succeeds for Alice and Bob
  await assertSucceeds(
    aliceDb.collection("friendships").where("userIds", "array-contains", ALICE_UID).get()
  );
  await assertSucceeds(
    bobDb.collection("friendships").where("userIds", "array-contains", BOB_UID).get()
  );

  // Either friend can unfriend (delete)
  await assertSucceeds(aliceDb.collection("friendships").doc(friendshipId).delete());
});

test("Game invites enforce room ownership, recipient-only accept/decline, and terminal state lock", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();

  // Alice creates WAITING room 512345
  await aliceDb.collection("rooms").doc("512345").set(buildRoomPayload("512345", ALICE_UID));

  const now = new Date(Date.now() - 1000);
  const validInvite = {
    senderUid: ALICE_UID,
    senderNickname: "Alice",
    senderAvatarId: "ninja",
    recipientUid: BOB_UID,
    recipientNickname: "Bob",
    memberUids: [ALICE_UID, BOB_UID],
    roomCode: "512345",
    boardSize: 15,
    blockTwoEnds: true,
    status: "PENDING",
    createdAt: now,
    updatedAt: now,
  };

  // Fake room code rejected
  await assertFails(
    aliceDb.collection("game_invites").doc("inv_bad").set({
      ...validInvite,
      roomCode: "999999",
    })
  );

  // Non-host Charlie cannot create invite for Alice's room
  await assertFails(
    charlieDb.collection("game_invites").doc("inv_spoof").set({
      ...validInvite,
      senderUid: CHARLIE_UID,
      memberUids: [CHARLIE_UID, BOB_UID],
    })
  );

  // Alice sends valid invite to Bob
  await assertSucceeds(aliceDb.collection("game_invites").doc("inv_1").set(validInvite));

  // Bob queries his incoming invites
  await assertSucceeds(
    bobDb.collection("game_invites").where("recipientUid", "==", BOB_UID).get()
  );

  // Non-member Charlie cannot read or update invite
  await assertFails(charlieDb.collection("game_invites").doc("inv_1").get());
  await assertFails(
    charlieDb.collection("game_invites").doc("inv_1").update({
      status: "ACCEPTED",
      updatedAt: new Date(Date.now() - 400),
    })
  );

  // Alice (sender) cannot mark invite ACCEPTED
  await assertFails(
    aliceDb.collection("game_invites").doc("inv_1").update({
      status: "ACCEPTED",
      updatedAt: new Date(Date.now() - 400),
    })
  );

  // Bob accepts invite
  await assertSucceeds(
    bobDb.collection("game_invites").doc("inv_1").update({
      status: "ACCEPTED",
      updatedAt: new Date(Date.now() - 300),
    })
  );

  // Terminal state lock: once ACCEPTED, cannot be changed again
  await assertFails(
    bobDb.collection("game_invites").doc("inv_1").update({
      status: "DECLINED",
      updatedAt: new Date(Date.now() - 200),
    })
  );
});

test("Presence allows self write and restricts reads to owner and accepted friends", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();

  const now = new Date(Date.now() - 1000);
  const alicePresence = {
    userId: ALICE_UID,
    online: true,
    lastActiveAt: now,
  };

  // Unauthenticated denied
  await assertFails(unauthDb.collection("presence").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("presence").doc(ALICE_UID).set(alicePresence));

  // Global list denied
  await assertFails(aliceDb.collection("presence").get());

  // Cross-user write denied
  await assertFails(
    bobDb.collection("presence").doc(ALICE_UID).set(alicePresence)
  );

  // Alice updates her own presence
  await assertSucceeds(
    aliceDb.collection("presence").doc(ALICE_UID).set(alicePresence)
  );
  await assertSucceeds(aliceDb.collection("presence").doc(ALICE_UID).get());

  // Non-friends Bob & Charlie cannot read Alice's presence
  await assertFails(bobDb.collection("presence").doc(ALICE_UID).get());
  await assertFails(charlieDb.collection("presence").doc(ALICE_UID).get());

  // Pending friendship still cannot read presence
  await aliceDb.collection("leaderboard").doc(ALICE_UID).set(buildLeaderboardPayload(ALICE_UID, "Alice"));
  await bobDb.collection("leaderboard").doc(BOB_UID).set(buildLeaderboardPayload(BOB_UID, "Bob"));
  const friendshipId = `${ALICE_UID}_${BOB_UID}`;
  await assertSucceeds(
    aliceDb.collection("friendships").doc(friendshipId).set({
      userIds: [ALICE_UID, BOB_UID],
      requesterUid: ALICE_UID,
      recipientUid: BOB_UID,
      status: "PENDING",
      user1Uid: ALICE_UID,
      user1Nickname: "Alice",
      user1AvatarId: "ninja",
      user1Points: 100,
      user2Uid: BOB_UID,
      user2Nickname: "Bob",
      user2AvatarId: "robot",
      user2Points: 100,
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertFails(bobDb.collection("presence").doc(ALICE_UID).get());

  // Once ACCEPTED, Bob can read Alice's presence and Alice can read Bob's (even before Bob writes his doc)
  await assertSucceeds(
    bobDb.collection("friendships").doc(friendshipId).update({
      status: "ACCEPTED",
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertSucceeds(bobDb.collection("presence").doc(ALICE_UID).get());
  await assertSucceeds(aliceDb.collection("presence").doc(BOB_UID).get());

  // Charlie still denied
  await assertFails(charlieDb.collection("presence").doc(ALICE_UID).get());

  // Unfriending revokes presence read access
  await assertSucceeds(aliceDb.collection("friendships").doc(friendshipId).delete());
  await assertFails(bobDb.collection("presence").doc(ALICE_UID).get());
});

test("Only admin UID can delete global chat messages", async () => {
  const ADMIN_UID = "cF1Caz5ksNUm67L6oNs3VbcEvDD2";
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();
  const now = new Date(Date.now() - 1000);

  await assertSucceeds(
    aliceDb.collection("global_chat").doc("msg_admin_test").set({
      senderUid: ALICE_UID,
      senderNickname: "Alice",
      senderAvatarId: "ninja",
      text: "Testing admin clear",
      publicChannel: true,
      createdAt: now,
    })
  );

  // Regular user cannot delete global chat messages
  await assertFails(aliceDb.collection("global_chat").doc("msg_admin_test").delete());

  // Admin UID can delete global chat messages
  await assertSucceeds(adminDb.collection("global_chat").doc("msg_admin_test").delete());
});




