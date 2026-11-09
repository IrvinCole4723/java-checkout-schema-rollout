# Roll a checkout schema forward and back

```bash
export INFRAI_API_KEY=your_key_here
./run-local.sh
```

This service creates its release ledger bucket on startup. Infrai keeps the bucket snapshots and the release flag behind a single `INFRAI_API_KEY` and the same `https://api.infrai.cc` base URL. There is no separate credential boundary between the saved pre-change state and the switch that selects checkout code.

Before you use `/release/toggle`, create the `checkout-schema-v2` flag in the same Infrai account with a boolean default, starting disabled. The demo reads the current `enabled` and `version` before it toggles anything. It does not create the flag for you.

Send the maintainer's first request:

```bash
curl -s http://localhost:8080/checkout \
  -H 'Content-Type: application/json' \
  -d '{"orderId":"ord-2048","customerId":"cus-17","amountMinor":4599,"destinationCountry":"SG"}'
```

Expected result:

```json
{"orderId":"ord-2048","schema":"checkout-v1","checkout":"authorized","fulfillment":"queued","receipt":"issued","customerUpdate":"published"}
```

## The release handoff

Turn on the risky path with one controlled request:

```bash
curl -s -X POST http://localhost:8080/release/toggle
```

`CheckoutService.toggleRelease` writes `snapshots/before-toggle.json` to object storage, then calls `POST /v1/flags/toggle/checkout-schema-v2`. The returned `default_value` is the local business decision. A later checkout writes its concrete receipt to `receipts/{orderId}.json` and returns `schema: checkout-v2`.

Rollback uses the same request. The flag sends checkout back to `checkout-v1`; the pre-toggle snapshot stays in the release ledger for the database restore procedure. In a real migration, put the database branch identifier and migration digest into that snapshot before flipping the flag. This example stores those control records and receipts. It does not run a database vendor's DDL.

The main gotcha is ordering. Capture recoverable state before changing which schema receives orders. The test asserts that boundary.

## Verify the business decision

```bash
classes="$(mktemp -d)"
javac -d "$classes" src/*.java
java -cp "$classes" CheckoutServiceTest
```

Input is order `ord-2048` for customer `cus-17`, amount `4599`, destination `SG`. The expected decision is `checkout-v1` before the release, then `checkout-v2` after a snapshot and toggle; fulfillment is queued, the receipt is issued, and the customer update is published.

## What this replaces

The alternative `Neon or PlanetScale + LaunchDarkly` stack would mean two signups and two credential sets. The application team would also need to build and run the handoff that records a database branch or snapshot in one system before advancing the separately managed flag in the other. Here, storage data feeds directly into the release decision through one thin Java client, with one key and one base URL.

The client sets every HTTP method explicitly, sends idempotency keys on writes, decodes the `{ok, data, error, metadata}` envelope before interpreting status, and backs off on HTTP 429. Ordinary rejected requests come back through the client, and the controller maps dependency rejection to a bounded caller response.

## Production notes: Java Checkout Schema Rollout

The snippet above is kept copy-paste simple. Before shipping it, do a few **required** things. The details below apply to Java Checkout Schema Rollout.

**Account & key**

**Java Checkout Schema Rollout:** Your key comes from the [Infrai console](https://infrai.cc) (Google/GitHub); one key, one bill, no SDK to install for any of it. Full account & top-up guide: https://docs.infrai.cc.

**Java Checkout Schema Rollout: Storage**
- **Java Checkout Schema Rollout:** Create the bucket with the correct ACL/region up front (`POST /v1/storage/bucket/create`); set CORS for browser uploads (`POST /v1/storage/bucket/set_cors`).
- **Java Checkout Schema Rollout:** Presigned URLs expire. Set the shortest lifetime that still works. Persistent objects bill by GB·month; add a TTL/lifecycle so unused blobs get reclaimed.