# ADR-051: Per-user voice phrase training: the phone records, the server trains, the phone checks and installs the model

**Status:** accepted (decided, not built)
**Date:** 2026-11-19
**Approved by:** project owner (typed, 2026-11-18 and 2026-11-19)

Read with ARCHITECTURE.md section 8 (the upgrade path), section 13 (F16), ADR-014 (multi-user isolation), ADR-022 (microphone), ADR-047 (retention) and ADR-050 (the speech models, their own pins and notices).

## Context

The wake phrase "Breaker Breaker" and the send phrase "And I'm Gone" run today on the keyword model of training option C. That is a pretrained open-vocabulary keyword spotter, run with the library's default tuning, and the two phrases are fixed token lines. It is the default, it is built in code, and it is the only phrase model the phone has. Nothing downloads that model yet, and no device measurement exists for it.

ARCHITECTURE.md section 8 names a per-user trained keyword model as the upgrade path. Section 13 (F16) describes it. The user records the two phrases in their own surroundings, the server trains a small model for that user, and the phone downloads it.

sherpa-onnx is inference-only. The phone can record, but it cannot train a keyword model. So the training runs on the server, as section 13 says.

The project owner chose both paths. Typed 2026-11-18, the owner's words on training: "both": option C (default keyword spotting) now and option A (per-user training) after, "that way it's more accessible to more people once it's built".

On 2026-11-19 the owner asked where training runs, typed: "What does the architecture say I am assuming that it is on the server where that has to happen but it can be through the phone which is what I think the architecture calls for basically uses the phone mic in a sentence of the server." This ADR follows section 13 as it is written.

## Decision

Section 13 is accepted as the design for training option A. This ADR is decided, not built. No line of code for it exists yet.

A. **Scope.** Section 13 is accepted as the design. The two modules already registered in `modules.toml` take the work: `android_training_client` (the phone side) and `server_training` (the training container). The API is new. It lives in `shared/modules/api-contracts`, and the spec `openapi.yaml` has no training operation today.

B. **Phone recording.** The phone records each phrase N times, with N from 10 to 20. The build fixes the exact count after a check of the recording length. Each sample is 16 kHz mono, the same format as a dictation take, and the microphone rules of ADR-022 apply. Samples are kept only in the app's private storage until they are uploaded. The phone deletes each sample after the server confirms it. Nothing is uploaded unless the user starts training and is signed in to their server.

C. **API.** The new operations are, in `shared/modules/api-contracts` first: upload samples, start a training job, read a job's status, download the trained model (the response carries the model's sha256 and byte size), delete my samples, and delete my model. Every operation uses the user's token and acts only on that user's own data. The contract step sets the final paths. The two training paths the api-contracts card names today are a starting point, not final.

D. **Server training.** The server trains on the CPU by default (section 13, D26). The trained model is stored in the user's namespace. The download gives the model's sha256 and byte size. The server uses a GPU only if a later measurement shows the model needs one, and then through Local Inference, as section 13 says (see J).

E. **Phone install and fallback.** The phone installs the user's model as its own keyword model. It is not an entry of the shipped model registry, which stays reviewed in the repository. The phone checks the download against the sha256 and byte size that the server gave, and applies the same extraction limits as the keyword model. A model that is not verified is never used (root AGENTS.md rule 7: models are untrusted). The phrase detector switches to the verified model. If anything fails (the model is missing or refused, it does not verify, there is no server, or the user is signed out), the default keyword model of option C is used. A settings control returns the phone to the default.

F. **Delete.** One user action deletes the samples and the model on the server, and the model on the phone. The phone deletes its own copy first, then asks the server. If the server cannot be reached, the delete stays pending and is retried, and the app can show that the server delete is not confirmed yet.

G. **No server, or not signed in.** Training is not offered. Option C keeps working. This is why option C stays the default for everyone.

H. **New user-facing words.** The training screen, its buttons and its messages are new words in the app. The owner approves them before the ui step is built. The build is split, so the parts with no user-facing words go first.

I. **Build order.** The owner's priority is app first, then shared, then the container.
   1. `android_training_client`: the recorder, the sample store and the upload client, built against the operations in C, with a fake server in the tests.
   2. `shared/modules/api-contracts`: the training operations in the spec and their Kotlin types.
   3. `server_training`: the container, last.
   The training screen waits for the owner's approval of its words (H).

J. **Not decided here.** These are left open:
   - the exact sample count (10 to 20 per phrase; the build fixes it);
   - the model size and the CPU training time, measured in the container build;
   - whether a GPU path is ever needed (section 13 says Local Inference if so);
   - how long the server keeps the samples after training. ADR-047 sets the phone window for transcriptions only, and this ADR sets no window for samples or models.
   - the training client's module edges for the real network and contract adapters (http, api-contracts); today it depends on android and core only.

## Reasons

- The phone can record but cannot train a keyword model. The server does the training (section 13).
- Option C works for every user from the start, and training adds to it. The owner asked for both, in that order.
- The owner's words: "that way it's more accessible to more people once it's built".
- A model that is not verified is never used, and every failure falls back to option C. The user is never left with no phrase detection because training went wrong.
- Samples and models belong to one user, and the user can delete them, which keeps the privacy rule of section 13 (see ADR-014 for the server-enforced isolation).
- The CPU default keeps the server light (section 13, D26).

## Consequences

Rules in: training is offered only to a user who is signed in to a server. Each sample and each model belongs to one user, and one action deletes them. The phone checks every downloaded model before it is used. Option C stays the default and the fallback. A user model never goes into the shipped model registry. Each new user-facing word waits for the owner's approval before its screen is built. The build order is the one in I.

Rules out: training with no server or while signed out; uploading a recording before the user starts training; a user model listed in the shipped registry; using a user model that has not matched its checksum; removing option C as the fallback; a GPU path before the owner approves it; a sample count presented as final before the build measures it.
