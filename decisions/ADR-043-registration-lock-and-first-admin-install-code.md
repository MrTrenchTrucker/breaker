# ADR-043: Registration lock — first account is admin, install code required

**Status:** accepted
**Date:** 2026-11-11
**Approved by:** the project owner, 2026-11-11 (registration stays closed until the admin opens it)

## Context

Breaker's register route (POST /v1/auth/register) already decides the role inside
one atomic statement: the first account ever stored becomes the admin (CASE
WHEN (SELECT COUNT(*) FROM accounts) = 0 THEN 'admin' ELSE 'user' END), and
the count plus the insert are one transaction under the write lock. There is
no way to close registration after the first account exists: anyone who can
reach the register route can create another account, and the "first account is
admin" rule only protects the first account, not the server as a whole.

The wrong-password-limit route (GET / PUT /v1/admin/wrong-pw-limit) shows the
pattern to follow: an admin-scoped switch stored in server_settings, mirrored
in openapi.yaml, strict one-member JSON body {"enabled": bool}, admin scope
enforced before any body work (401 → 403 → 400 → 503), read-back of the
stored state, and database failures answered 503.

## Decision

1. **Registration lock switch.** An admin-scoped switch registration_open is
added, read by GET /v1/admin/registration-open and flipped by
PUT /v1/admin/registration-open. It mirrors /v1/admin/wrong-pw-limit exactly:
admin scope only, strict body {"enabled": true} / {"enabled": false}
(whitespace between tokens allowed, everything else refused 400), stored under
its own server_settings key registration_open, added to openapi.yaml beside
wrong-pw-limit, 401 → 403 → 400 → 503 ordering, and read-back of the stored
state on PUT.

2. **Default = closed once an account exists.** With zero accounts, the first
registration is always allowed and becomes the admin (the existing
first-account-is-admin rule). Once any account exists, the register route
answers 403 {"error":"registration_closed","message":"Registration is closed."}
unless the switch is on. After an
upgrade to a server that already has accounts, the default is closed — the
switch must be flipped on deliberately before anyone else can register.

3. **No race with the first-admin rule.** The registration-open check runs in
the same transaction as the account insert, so a concurrent registration
cannot slip through between a check and an insert.

4. **Install code for the first admin (slice C — decided, next slice).** At
first start the server generates one install code, writes it 0600 into the
data directory, and logs it once. The first registration body must include the
code; the server consumes it in the same transaction as the insert. Without a
valid code, the first account cannot be made. Subsequent registrations
(switch on) do not require a code. Admin-minted invites (slice D) come later,
only if the project owner wants them.

5. **Reset flow untouched.** Account reset is not affected by the registration
lock and keeps its own behaviour.

6. **Phone and install script (approved by the project owner 2026-11-18;
planned, not built).**
   (a) The phone's register screen gets ONE optional text field, on the Create
   account face only (hidden on the sign-in face), labelled "Install code
   (optional)". A blank field sends no code (null). The field is cleared after a
   successful register and kept after a failed one. The submit passes the code to
   the three-argument register.
   (b) When register answers 403 registration_closed, the phone shows the fixed
   line "Registration is closed." through a new auth-client reason,
   AuthFailureReason.REGISTRATION_CLOSED. A wrong or missing install code gets no
   new sentence: it stays the generic failure of any bad field.
   (c) The app's sign-in submit handler passes the code to the three-argument
   register and shows the closed line.
   (d) The install script (server/modules/deploy/scripts/install.sh) prints the
   install code at the end of install, with one line telling the admin to use it
   on the first account. Today the admin reads the code from the install-code
   file in the data folder.
   (e) The web front end's register page is not part of this decision.

## Reasons

- Mirroring the wrong-pw-limit route gives one code path for admin switches,
one test pattern, and one openapi shape, instead of inventing a second one.
- Default-closed-after-upgrade prevents a server that already has accounts
from silently opening registration to the network when it is upgraded.
- Checking registration-open inside the insert transaction closes the race
where two concurrent first registrations could both succeed.
- Requiring an install code for the first admin means the first account can
only be created by someone who can read the server's data directory (the
install-code file) — the admin who controls the host — not by anyone who can
reach the register route.

## Consequences

Rules in: registration_open admin switch; GET / PUT /v1/admin/registration-open
mirroring wrong-pw-limit; strict body {"enabled": bool}; server_settings key
registration_open; openapi entry beside wrong-pw-limit; 401 → 403 → 400 → 503
ordering; zero accounts => first registration always allowed (becomes admin);
once an account exists => 403 {"error":"registration_closed"} unless switch on;
default after upgrade on a server with accounts = closed; check in the same
transaction as the insert; install code generated at first start, 0600 in the
data dir (one notice line is printed, never the code; the project owner reads
the file; the install script prints it once at the end of install, built 2026-11-18,
see item 6), required in the first registration body, consumed in the same
transaction; reset flow untouched; phone register field, closed line and
sign-in submit change decided 2026-11-18, planned.

Rules out: registration open by default on an upgraded server that already has
accounts; a separate registration-open code path that does not mirror
wrong-pw-limit; a check outside the insert transaction; admin-minted invites
(slice D) in this slice.

Decided 2026-11-18 (item 6): the phone register field and the closed line are
planned; the install script's print of the code is built (deploy `install.sh`
step 12, 2026-11-18). The web front end's register
page is not decided here.

Honesty: ADR-009 already records first-account-is-admin; this ADR adds the
switch, the default-closed upgrade behaviour, the install code, and the
transactional check. The wrong-pw-limit route (AdminWrongPwLimitRoute.kt,
ServerSettingsStore) is the exact code shape to mirror — nothing in it
changes.

Update 2026-11-11 (slice C built): the install code is made at the first start
while no account exists: 43 URL-safe characters from 32 random bytes, in the file
`install-code` next to the database (BREAKER_SYNC_API_INSTALL_CODE_FILE changes the
path), created with mode 0600 at creation. A start that finds a bad file (wrong
length, wrong characters, group or other permissions) refuses to run. The start
prints one line, "sync-api: install code generated", the first time and never the
code. The project owner learns the code by reading the `install-code` file in the
data folder on the host (for example with `cat`); nothing else shows it. This
replaces "logged once" above, because the sync-api never writes a secret to a log. The first registration must send
`install_code`; a missing code and a wrong code get the same 400, and a wrong code
costs no password hash. Once an account exists the field is ignored, and the file
is deleted after the first account commits (best effort). The client field and the
first-run screen were then under To be designed; they are decided in item 6 above
(2026-11-18), see the amendment below.

Amended 2026-11-18: item 6 is decided (phone field, the closed line, the sign-in
submit, and the install script's print). Built on main: the server side (the
registration_open switch, the 403, and the install code file and its check) and
the auth client's register overload, which sends the code last only when one is
given. Planned, not built: the phone field, AuthFailureReason.REGISTRATION_CLOSED,
the sign-in submit change, and the install script's print. The print is a change
to the two sentences above that say the code is never shown beyond the file: for
the install script's output only, the code is printed once at the end of install.
The sync-api itself still never writes the code to a log. The web front end's
register page is not changed.
